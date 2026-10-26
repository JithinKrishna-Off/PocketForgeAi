package com.pocketforge.ai.bench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.inference.LlamaBridge
import com.pocketforge.ai.inference.ModelLoadService
import com.pocketforge.ai.inference.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * PFBENCH PATH A runner (debug builds only): headless engine instrument measurement.
 *
 * Keeps the production path: ModelLoadService.loadInstalled() -> LlamaInferenceEngine ->
 * processUserPrompt() -> generateNextToken() loop. Sinks to a StringBuilder (no UI), logs
 * per-token timing through PfBenchLogger to CSV files in filesDir/bench (pull via run-as).
 */
class BenchService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        @Volatile
        private var runningFlag = false

        fun isBusy(): Boolean = runningFlag

        // P1: short, ~20 tokens input, force 128 generated tokens (deterministic).
        val P1 = "Explain how a refrigerator works in detail."

        // P2: ~120 tokens of fixed filler text plus a one-line question (prefill benchmark).
        private val P2_FILLER = "The bicycle pump works because the air is already moving. " +
            "The next day, the crew returned and removed the scaffold while the robot wrote notes " +
            "no one would read for a few days. Wind blew the notebook across the flat roof and the " +
            "markers fell off the low rail, one by one, while the sun climbed and the telemetry " +
            "pinged at a slow pace. By midmorning the form was dry except for the thin line of dew " +
            "that had settled on its far edge. Later a repair van arrived. "

        val P2 = P2_FILLER + "How many times did the sun leave a thin line of dew?"

        val prompts = mapOf(
            "P1" to P1,
            "P2" to P2,
        )

        const val NB_CHANNEL = "pfbench"
        const val NB_ID = 0x0FB
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                NB_CHANNEL, "PFBENCH", NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BenchService.runningFlag) {
            Log.w("PFBENCH", "bench already running, ignoring")
            return START_NOT_STICKY
        }
        BenchService.runningFlag = true
        val extras: Bundle = intent?.extras ?: Bundle()
        val ts = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
        val uniqueRunId = (extras.getString("run") ?: "run") + "_" + ts
        val notif: Notification = Notification.Builder(this, NB_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("PFBENCH measurement running")
            .setOngoing(true)
            .build()
        startForeground(
            NB_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val app = applicationContext as PocketForgeApp
        executor.execute {
            try {
                Log.i("PFBENCH", "service start: model=${extras.getString("model")} run=$uniqueRunId")
                kotlinx.coroutines.runBlocking {
                    runBench(
                        app,
                        extras.getString("model") ?: "",
                        extras.getString("prompt") ?: "P1",
                        extras.getInt("max_new", 32),
                        uniqueRunId,
                        extras.getInt("ctx", 2048),
                        extras.getLong("budget_ms", 0L),
                    )
                }
            } catch (t: Throwable) {
                Log.e("PFBENCH", "bench failed", t)
                com.pocketforge.ai.bench.PfBenchLogger.aborted("exception: ${t.message}")
            } finally {
                BenchService.runningFlag = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runBench(
        app: PocketForgeApp,
        modelId: String,
        promptId: String,
        maxNew: Int,
        runId: String,
        ctxLen: Int,
        budgetOverrideMs: Long,
    ) {
        val prompt = prompts[promptId] ?: run {
            Log.e("PFBENCH", "unknown prompt id $promptId"); return
        }
        val svc = ModelLoadService(app)
        val dir = File(app.filesDir, "bench").apply { mkdirs() }
        Log.i(
            "PFBENCH",
            "PATH-A start run=$runId model=$modelId prompt=$promptId ctx=$ctxLen max=$maxNew"
        )

        val loaded = svc.loadInstalled(modelId, contextLength = ctxLen, threads = 6)
        when (loaded) {
            is ModelLoadService.Result.Failure -> {
                Log.e("PFBENCH", "load FAILED: ${loaded.message}")
                return
            }
            is ModelLoadService.Result.Loaded ->
                Log.i("PFBENCH", "load OK: ${loaded.summary}")
        }
        // determinism: same sampler for all runs and models
        val samplerErr = LlamaBridge.setSampler(0.0f, 1, 1.0f, 42L)
        if (samplerErr != null) {
            Log.e("PFBENCH", "sampler err: $samplerErr")
            return
        }

        fun newCsv(n: Int): File =
            File(dir, "pfbench_${runId}_${modelId}_${promptId}_$n.csv").let { f ->
                if (f.exists()) f.delete()
                f
            }

        val engine = app.engine
        val maxN = maxNew

        var runIdx = 1
        val capacity = if (promptId == "P1") 3 else 2
        while (runIdx <= capacity) {
            val f = newCsv(runIdx)
            val cfgRun = "pfa_${promptId}_${runIdx}"
            com.pocketforge.ai.bench.PfBenchLogger.arm(f, cfgRun, modelId, promptId, "A", maxN)
            val isWarmup = runIdx == 1 && promptId == "P1"
            Log.i("PFBENCH", "run begin: $cfgRun (warmup=$isWarmup)")

            val sink = StringBuilder()  // the no-render sink per spec
            var first = 0L
            var last = 0L
            val budget = if (budgetOverrideMs > 0) {
                budgetOverrideMs
            } else {
                120_000L + maxN * 2_000L
            }
            val finished = kotlinx.coroutines.withTimeoutOrNull(budget) {
                withContext(Dispatchers.Default) {
                    engine.send(prompt, maxTokens = maxN).collect { ev ->
                        when (ev) {
                            is StreamEvent.Token -> {
                                val t = System.nanoTime()
                                if (first == 0L) first = t
                                last = t
                                sink.append(ev.text)
                            }
                            is StreamEvent.Done -> Log.i(
                                "PFBENCH",
                                "run $cfgRun done text.len=${ev.text.length} reason=${ev.reason}"
                            )
                            is StreamEvent.Error -> Log.e("PFBENCH", "run $cfgRun engine error", ev.error)
                        }
                    }
                }
            }
            if (finished == null) {
                Log.e("PFBENCH", "run $cfgRun TIMEOUT after ${budget}ms; csv saved as aborted")
                PfBenchLogger.aborted("collectTimeout:${budget}ms")
            }
            Log.i(
                "PFBENCH",
                "run $cfgRun: chars=${sink.length} wallMs=${((last - first) / 1e6).toInt()}"
            )
            PfBenchLogger.disarm()
            runIdx++
            // 60s cooldown between runs (short after the P1 warmup discard)
            val shortGap = promptId == "P1" && runIdx == 2
            try {
                Thread.sleep(if (shortGap) 3_000 else 60_000)
            } catch (_: InterruptedException) {
            }
        }

        try {
            engine.unload()
        } catch (t: Throwable) {
            Log.w("PFBENCH", "unload err", t)
        }
        Log.i("PFBENCH", "PATH-A end run=$runId model=$modelId dir=$dir")
    }
}
