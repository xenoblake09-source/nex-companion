package com.nexora.robot.language

/**
 * Decides whether a turn may ask the server for live, grounded information.
 *
 * The signal is conservative: it is only raised for intents and wording that genuinely
 * need up-to-date facts. The server still decides whether search grounding really helps,
 * and it will never claim sources when grounding did not happen.
 */
data class CurrentInfoDecision(
    val needed: Boolean,
    val reason: String,
    val intent: UserIntent,
)

object CurrentInfoDetector {

    /** Intents whose answers change with time or place. */
    val LIVE_INTENTS: Set<UserIntent> = setOf(
        UserIntent.WEATHER,
        UserIntent.NEWS,
        UserIntent.LOCATION_QUERY,
        UserIntent.TIME_SENSITIVE_QUERY,
    )

    /** Wording that implies "as of now", in English, Hindi and Hinglish. */
    val TIME_MARKERS: Set<String> = setOf(
        "today", "tonight", "tomorrow", "yesterday", "now", "right now", "latest", "current",
        "currently", "live", "recent", "breaking", "update", "updates", "score", "price",
        "rate", "result", "results", "election", "this week", "this month", "so far",
        "aaj", "abhi", "kal", "taza", "tajaa", "nateeja", "keemat", "daam", "chunav", "ab tak",
        "आज", "अभी", "कल", "ताज़ा", "ताजा", "नतीजा", "कीमत", "दाम", "चुनाव",
    )

    fun decide(text: String, explicitSignal: Boolean = false): CurrentInfoDecision {
        val intent = IntentDetector.detect(text)
        if (explicitSignal) {
            return CurrentInfoDecision(true, "explicit-client-signal", intent)
        }
        if (intent in LIVE_INTENTS) {
            return CurrentInfoDecision(true, "intent:${intent.name.lowercase()}", intent)
        }
        val normalized = TextNormalizer.normalize(text)
        val tokens = TextNormalizer.tokens(text)
        // Single words must match as whole tokens ("now" inside "know" is not a time marker);
        // multi-word markers still match as phrases.
        val marker = TIME_MARKERS.firstOrNull { candidate ->
            if (candidate.contains(' ')) normalized.contains(candidate) else candidate in tokens
        }
        if (marker != null) {
            return CurrentInfoDecision(true, "time-marker:$marker", intent)
        }
        return CurrentInfoDecision(false, "no-current-info-needed", intent)
    }

    fun needsCurrentInfo(text: String, explicitSignal: Boolean = false): Boolean =
        decide(text, explicitSignal).needed
}
