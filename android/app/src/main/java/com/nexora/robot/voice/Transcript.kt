package com.nexora.robot.voice

import com.nexora.robot.net.SourceLink

/** Who said a line in the on-screen transcript. */
enum class Speaker { USER, NEX, SYSTEM }

/**
 * One transcript line.
 *
 * Kept in memory only, for the current session, and capped by [SpeechController]. It is never
 * uploaded and never persisted: local memory is a separate, consent-gated store.
 */
data class TranscriptLine(
    val speaker: Speaker,
    val text: String,
    /** True only when the server really used search grounding for this line. */
    val grounded: Boolean = false,
    val sources: List<SourceLink> = emptyList(),
    val atMs: Long = 0L,
)
