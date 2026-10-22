package com.pocketforge.ai.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Measured session facts shared with the Performance screen. Values are measured, never estimated. */
object SessionStats {
    /** Wall clock of the most recent successful model load. */
    private val _lastLoadMillis = MutableStateFlow<Long?>(null)
    val lastLoadMillis: StateFlow<Long?> = _lastLoadMillis

    private val _loadDurationMs = MutableStateFlow<Long?>(null)
    val loadDurationMs: StateFlow<Long?> = _loadDurationMs

    private val _lastTurn = MutableStateFlow<TokenStats?>(null)
    val lastTurn: StateFlow<TokenStats?> = _lastTurn

    private val _gpuProbe = MutableStateFlow<GpuProbeReport?>(null)
    val gpuProbe: StateFlow<GpuProbeReport?> = _gpuProbe

    fun onLoaded(durationMs: Long) {
        _lastLoadMillis.value = System.currentTimeMillis()
        _loadDurationMs.value = durationMs
    }

    fun onTurn(stats: TokenStats) {
        _lastTurn.value = stats
    }

    fun onProbe(report: GpuProbeReport) {
        _gpuProbe.value = report
    }
}
