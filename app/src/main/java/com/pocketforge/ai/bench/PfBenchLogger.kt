package com.pocketforge.ai.bench

import android.os.Build
import android.util.Log
import com.pocketforge.ai.BuildConfig
import java.io.BufferedWriter
import java.io.File

/**
 * PFBENCH: ultra-light per-token measurement surface used by debug instrumentation only.
 *
 * All paths are no-ops unless [arm] was called and BuildConfig.DEBUG is true. Disabled cost is one
 * boolean read per token; enabled cost is one synchronized structured append per token plus a
 * single flush at turn end (flush happens outside the timed token loop).
 *
 * Schema (pf_bench v2 CSV):
 *   tsNano,runId,piece,phase,path,promptId,modelId,wallNs,cpuNs,pieceChars,textLen,tokens,
 *   tid,utimeK,stimeK,minflt,majflt,nvcsw,nivcsw
 * rows: phase=token (one per generated token), promptStart/promptDone rows,
 * and done/summary rows (rusage deltas + sw columns; sw= utilization/fault extras at end).
 */
object PfBenchLogger {

    private const val TAG = "PFBENCH"
    private const val HEADER =
        "tsNano,runId,piece,phase,path,promptId,modelId,wallNs,cpuNs,pieceChars,textLen,tokens," +
            "tid,utimeK,stimeK,minflt,majflt,nvcsw,nivcsw"

    private val debugEnabled: Boolean
        get() = BuildConfig.DEBUG

    private class TurnCfg(
        val runId: String,
        val modelId: String,
        val promptId: String,
        val path: String,
        val maxTokens: Int,
    )

    private var cfg: TurnCfg? = null
    private var writer: BufferedWriter? = null
    private var csvFile: File? = null
    private var engineTid: Long = 0L

    private var turnStartNano: Long = Long.MIN_VALUE
    private var promptStartNano: Long = Long.MIN_VALUE
    private var firstTokenNano: Long = Long.MIN_VALUE
    private var lastTokenNano: Long = Long.MIN_VALUE
    private var tokenCount: Int = 0
    private var charsCount: Int = 0

    // rusage baselines (engine thread + process) captured at arm()
    private var baseTh: Ru? = null
    private var baseProc: Ru? = null

    private class Ru(
        var utimeK: Long, var stimeK: Long, var minflt: Long, var majflt: Long,
        var nvcsw: Long, var nivcsw: Long,
    )

    fun isArmed(): Boolean = debugEnabled && cfg != null

    @Synchronized
    fun arm(file: File, runId: String, modelId: String, promptId: String, path: String, maxTokens: Int) {
        if (!debugEnabled) return
        cfg = TurnCfg(runId, modelId, promptId, path, maxTokens)
        csvFile = file
        if (!file.exists()) {
            file.appendText(HEADER + "\n")
        }
        writer = file.bufferedWriter()
        turnStartNano = System.nanoTime()
        promptStartNano = turnStartNano
        firstTokenNano = Long.MIN_VALUE
        tokenCount = 0
        charsCount = 0
        baseTh = runRusage(Thread.currentThread().id)
        baseProc = runRusage(0L) // 0 => whole-process stat/status
        Log.i(TAG, "armed run=$runId model=$modelId prompt=$promptId path=$path max=$maxTokens file=${file.name}")
    }

    /** Called at the very start of every turn, right before processUserPrompt. */
    @Synchronized
    fun turnBegin() {
        if (!debugEnabled) return
        if (cfg == null) return
        turnStartNano = System.nanoTime()
        promptStartNano = turnStartNano
        firstTokenNano = Long.MIN_VALUE
        tokenCount = 0
        charsCount = 0
        engineTid = Thread.currentThread().id
        writeRow("promptStart")
    }

    /** After processUserPrompt rc==0, before the first generateNextToken(). */
    @Synchronized
    fun promptDone() {
        if (!debugEnabled) return
        if (cfg == null) return
        writeRow("promptDone")
    }

    /**
     * Exactly ONE call per engine token, immediately after generateNextToken() returns non-null,
     * regardless of whether the piece reaches the UI.
     */
    @Synchronized
    fun token(wallNs: Long, cpuNs: Long, pieceLen: Int) {
        if (!debugEnabled) return
        if (cfg == null) return
        val now = System.nanoTime()
        if (firstTokenNano == Long.MIN_VALUE) firstTokenNano = now
        lastTokenNano = now
        tokenCount++
        charsCount += pieceLen
        val ru = runRusage(Thread.currentThread().id)
        if (ru != null) {
            wRow("token", wallNs, cpuNs, pieceLen, ru)
        } else {
            wRow("token", wallNs, cpuNs, pieceLen, null)
        }
    }

    @Synchronized
    fun done(
        reason: String,
        textLen: Int,
        nPromptTokens: Int = 0,
        nGenTokens: Int = 0,
        tPromptMs: Long = -1,
        tGenMs: Long = -1,
    ) {
        if (!debugEnabled) return
        val c = cfg ?: return
        val d1 = firstTokenNano - promptStartNano
        val d2 = lastTokenNano - firstTokenNano
        val wallPromptMs = if (d1 > 0) d1 / 1_000_000 else -1L
        val wallDecodeMs = if (d2 > 0) d2 / 1_000_000 else -1L
        val decodeTokS = if (tokenCount > 1 && d2 > 0) (tokenCount - 1) * 1e9 / d2 else 0.0
        val endTh = runRusage(engineTid)
        val endProc = runRusage(0L)
        val thD = diff(baseTh, endTh)
        val procD = diff(baseProc, endProc)
        val logLine = "run=${c.runId} model=${c.modelId} prompt=${c.promptId} path=${c.path} " +
            "promptMs=$wallPromptMs decodeMs=$wallDecodeMs tok=$tokenCount " +
            "decodeTokS=" + String.format("%.2f", decodeTokS) + " chars=$charsCount reason=$reason " +
            "np=$nPromptTokens ne=$nGenTokens tpm=$tPromptMs tgm=$tGenMs " +
            "procUk=${procD?.utimeK} procSk=${procD?.stimeK} procMinflt=${procD?.minflt} " +
            "procMajflt=${procD?.majflt} nvcsw=${procD?.nvcsw} nivcsw=${procD?.nivcsw} " +
            "csv=${csvFile?.name ?: "?"}"
        Log.i(TAG, logLine)
        writeRow("done:$reason", extra = "sw:procUtime=${procD?.utimeK};procStime=${procD?.stimeK};" +
            "procMinflt=${procD?.minflt};procMajflt=${procD?.majflt};" +
            "nvcsw=${procD?.nvcsw};nivcsw=${procD?.nivcsw}")
        writeRow("summary", extra = "sw:ttftPromptMs=$wallPromptMs;decodeWallMs=$wallDecodeMs;decodeTokS=$decodeTokS;" +
            "nPEval=$nPromptTokens;nEval=$nGenTokens;tPEvalms=$tPromptMs;tEvalms=$tGenMs;" +
            "procUtimeK=${procD?.utimeK};procStimeK=${procD?.stimeK};procMinflt=${procD?.minflt};procMajflt=${procD?.majflt};" +
            "nvcsw=${procD?.nvcsw};nivcsw=${procD?.nivcsw};threadUtimeK=${thD?.utimeK};threadStimeK=${thD?.stimeK}")
        try { writer?.flush() } catch (_: Throwable) {}
        cfg = null
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
    }

    /** Timeout/abort cleanup: keep CSV rows gathered so far and mark the file aborted. */
    @Synchronized
    fun aborted(reason: String) {
        if (!debugEnabled) return
        if (cfg == null) return
        Log.e(TAG, "run=${cfg?.runId} ABORTED: $reason")
        writeRow("aborted:$reason")
        try { writer?.flush() } catch (_: Throwable) {}
        try { writer?.close() } catch (_: Throwable) {}
        cfg = null
        writer = null
    }

    /** Silent cleanup: closes the CSV without emitting a done row (used after completed runs). */
    @Synchronized
    fun disarm() {
        if (!debugEnabled) return
        cfg = null
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
    }

    private fun diff(ba: Ru?, end: Ru?): Ru? {
        if (ba == null || end == null) return end
        val r = Ru(
            end.utimeK - ba.utimeK, end.stimeK - ba.stimeK,
            end.minflt - ba.minflt, end.majflt - ba.majflt,
            end.nvcsw - ba.nvcsw, end.nivcsw - ba.nivcsw,
        )
        return r
    }

    private fun ruString(ru: Ru?): String =
        if (ru == null) "0,0,0,0,0,0"
        else "${ru.utimeK},${ru.stimeK},${ru.minflt},${ru.majflt},${ru.nvcsw},${ru.nivcsw}"

    /**
     * tid == 0 means whole-process rusage (/proc/self/stat + /proc/self/status);
     * otherwise /proc/self/task/<tid>/stat + status.
     * utimeK/stimeK are clock ticks (100Hz); nvcsw/nivcsw cumulative counters.
     */
    private fun runRusage(tid: Long): Ru? {
        if (!debugEnabled) return null
        val statPath = if (tid == 0L) "/proc/self/stat" else "/proc/self/task/$tid/stat"
        val statPathS = if (tid == 0L) "/proc/self/status" else "/proc/self/task/$tid/status"
        return try {
            val statText = File(statPath).readText()
            val closeParen = statText.lastIndexOf(')')
            val tail = statText.substring(closeParen + 2).trim().split(' ')
            // tail after "pid (comm) " -> 0-based: [state ppid pgrp session tty tpgid flags minflt
            // cminflt majflt cmajflt utime stime ...]
            val minflt = tail[7].toLong()
            val majflt = tail[10].toLong()
            val utime = tail[11].toLong()
            val stime = tail[12].toLong()
            var nvcsw = 0L
            var nivcsw = 0L
            val statusText = File(statPathS).readText()
            for (line in statusText.lines()) {
                if (line.startsWith("voluntary_ctxt_switches:")) nvcsw = line.substringAfter(':').trim().toLong()
                if (line.startsWith("nonvoluntary_ctxt_switches:")) nivcsw = line.substringAfter(':').trim().toLong()
            }
            Ru(utime, stime, minflt, majflt, nvcsw, nivcsw)
        } catch (t: Throwable) {
            null
        }
    }

    private fun writeRow(
        phase: String,
        wallNs: Long = 0,
        cpuNs: Long = 0,
        pieceChars: Int = 0,
        ru: Ru? = null,
        extra: String = "",
    ) {
        val c = cfg ?: return
        val w = writer ?: return
        try {
            val now = System.nanoTime()
            w.write("$now,${c.runId},$tokenCount,$phase,${c.path},${c.promptId},${c.modelId},$wallNs,$cpuNs,$pieceChars,$charsCount,$tokenCount")
            w.write(",")
            w.write("${Thread.currentThread().id},")
            w.write(ruString(ru))
            if (extra.isNotBlank()) w.write(",sw:$extra")
            w.newLine()
        } catch (t: Throwable) {
            Log.w(TAG, "csv write failed", t)
        }
    }

    private fun wRow(
        phase: String,
        wallNs: Long,
        cpuNs: Long,
        pieceChars: Int,
        ru: Ru?,
    ) = writeRow(phase, wallNs, cpuNs, pieceChars, ru)
}
