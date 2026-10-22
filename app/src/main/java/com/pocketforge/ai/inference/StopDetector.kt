package com.pocketforge.ai.inference

/**
 * Detects stop sequences across token boundaries.
 *
 * Adds token text to a pending buffer and computes the safe prefix to emit plus
 * whether a stop sequence terminated the turn. Allows stop strings split over
 * multiple tokens (keeps a tail window pending until safe).
 */
class StopDetector(private val stopSequences: List<String>) {

    private var buffer = StringBuilder()

    /** Result of feeding one token piece. */
    sealed class FeedResult {
        /** Emit text and continue generating. */
        data class Emit(val text: String) : FeedResult()

        /** Stop reached; emit prefix before the first stop match. */
        data class StopTriggered(val text: String, val stopSequence: String) : FeedResult()
    }

    fun feed(piece: String): FeedResult {
        if (stopSequences.isEmpty()) return FeedResult.Emit(piece)
        buffer.append(piece)
        val s = buffer.toString()

        val firstMatch = stopSequences
            .mapNotNull { stop -> if (stop.isNotEmpty()) s.indexOf(stop) else null }
            .minOrNull()
        if (firstMatch != null) {
            val matchedStop = stopSequences.first { stop -> stop.isNotEmpty() && s.indexOf(stop) == firstMatch }
            val text = s.substring(0, firstMatch)
            reset()
            return FeedResult.StopTriggered(text, matchedStop)
        }

        // Emit content that can no longer become part of a stop sequence. A stop sequence
        // can start at most (maxStopLen - 1) chars before the end of the buffer.
        val maxStopLen = stopSequences.maxOf { it.length }
        val safeLen = (s.length - maxStopLen + 1).coerceAtLeast(0)
        if (safeLen > 0) {
            val emit = s.substring(0, safeLen)
            buffer = StringBuilder(s.substring(safeLen))
            return FeedResult.Emit(emit)
        }
        return FeedResult.Emit("")
    }

    /** Flush remaining buffered text (turn ended by EOG/max tokens). */
    fun flush(): String {
        val rest = buffer.toString()
        reset()
        return rest
    }

    fun reset() {
        buffer = StringBuilder()
    }
}
