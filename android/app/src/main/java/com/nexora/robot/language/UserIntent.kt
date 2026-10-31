package com.nexora.robot.language

/**
 * What the user is trying to do, decided on-device from their words.
 *
 * Used by three places, all of which must agree:
 *  * [com.nexora.robot.privacy.PermissionPolicy] — is a camera/location request justified?
 *  * [CurrentInfoDetector] — may the server use live search grounding?
 *  * [com.nexora.robot.offline.OfflineResponses] — what is truthful to say offline?
 */
enum class UserIntent {
    GREETING,
    FAREWELL,
    GRATITUDE,
    WEATHER,
    NEWS,
    TIME_SENSITIVE_QUERY,
    LOCATION_QUERY,
    STUDY_HELP,
    MATHS,
    SCIENCE,
    CODING,
    CREATIVITY,
    BOOK_OR_DOCUMENT,
    MEMORY_SAVE,
    MEMORY_RECALL,
    MEMORY_LIST,
    MEMORY_DELETE,
    VISION_REQUEST,
    PRIVACY_HELP,
    PERMISSION_HELP,
    SETTINGS_OR_VOICE_COMMAND,
    TIMER_START,
    MUSIC,
    OFFLINE_SMALLTALK,
    GENERAL,
}

/** Coarse weather flavour, only used to pick a screen animation. */
enum class WeatherKind {
    SUN,
    RAIN,
    CLOUD,
    STORM,
    UNKNOWN,
}
