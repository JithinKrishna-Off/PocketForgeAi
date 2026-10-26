package com.pocketforge.ai.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * PFBENCH PATH A trigger. Thin-forwarder only: logs entry and all guard paths, then hands off to
 * BenchService (debug-only foreground service). Never runs the bench inline.
 *
 *   adb shell am broadcast -a com.pocketforge.ai.BENCH -n com.pocketforge.ai/.bench.BenchReceiver \
 *     --es model <installed-model-id> --es prompt P1|P2 --ei max_new 128 --es run <run-id>
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val model = intent.getStringExtra("model")
        Log.i(
            "PFBENCH",
            "receiver onReceive action=${intent.action} model=$model " +
                "prompt=${intent.getStringExtra("prompt")} max_new=${intent.getIntExtra("max_new", -1)} " +
                "run=${intent.getStringExtra("run")} ctx=${intent.getIntExtra("ctx", -1)} " +
                "serviceBusy=${BenchService.isBusy()}"
        )
        if (model.isNullOrBlank()) {
            Log.w("PFBENCH", "receiver early-return: model extra missing/blank")
            return
        }
        if (BenchService.isBusy()) {
            Log.w("PFBENCH", "receiver early-return: BenchService already running a benchmark")
            return
        }
        val svc = Intent(context, BenchService::class.java)
            .putExtra("model", model)
            .putExtra("prompt", intent.getStringExtra("prompt") ?: "P1")
            .putExtra("max_new", intent.getIntExtra("max_new", 128))
            .putExtra("run", intent.getStringExtra("run") ?: "run")
            .putExtra("ctx", intent.getIntExtra("ctx", 2048))
        try {
            context.startForegroundService(svc)
            Log.i("PFBENCH", "receiver -> BenchService start requested")
        } catch (t: Throwable) {
            Log.e("PFBENCH", "receiver guard: startForegroundService failed", t)
        }
    }
}
