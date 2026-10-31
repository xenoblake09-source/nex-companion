package com.nexora.robot.animation

import com.nexora.robot.language.UserIntent
import com.nexora.robot.language.WeatherKind
import com.nexora.robot.voice.ConversationState

/**
 * The 56 semantic cues NEX can play, one per catalogue entry.
 *
 * The mapping cue -> animation id is a bijection over 1..56 (asserted by
 * `AnimationDirectorTest`), so every curated animation is reachable and no hidden
 * animation exists outside the catalogue.
 */
enum class AnimationCue(val animationId: Int) {
    CALM_IDLE_BREATHING(1),
    SOFT_BLINK(2),
    DOUBLE_BLINK(3),
    CURIOUS_EYEBROW_LIFT(4),
    HAPPY_EYE_CRESCENT(5),
    GREETING(6),
    LISTENING(7),
    WAKE_WORD_DETECTED(8),
    THINKING(9),
    DEEP_THINKING(10),
    PROCESSING(11),
    ANSWER_READY(12),
    SPEAKING(13),
    QUIET_LISTENING(14),
    CONFUSED(15),
    ASKING_FOR_CLARIFICATION(16),
    PROUD(17),
    ENCOURAGEMENT(18),
    CELEBRATION(19),
    STUDY_FOCUS(20),
    BOOK_LEARNING(21),
    MATHS(22),
    SCIENCE(23),
    CODING(24),
    CREATIVITY(25),
    MEMORY_SAVE(26),
    MEMORY_RECALL(27),
    FRIENDLY_WAVE(28),
    PLAYFUL(29),
    SLEEPY(30),
    REST_MODE(31),
    GOOD_MORNING(32),
    GOOD_NIGHT(33),
    WEATHER_SUN(34),
    WEATHER_RAIN(35),
    WEATHER_CLOUD(36),
    WEATHER_STORM(37),
    INTERNET_SEARCH(38),
    SOURCE_FOUND(39),
    CAMERA_ACTIVE(40),
    VISION_ANALYZING(41),
    SAFE_WARNING(42),
    PRIVACY_SHIELD(43),
    PERMISSION_REQUEST(44),
    MICROPHONE_OFF(45),
    VOICE_RECOVERING(46),
    NETWORK_WAITING(47),
    OFFLINE(48),
    ERROR(49),
    SUCCESS(50),
    LANGUAGE_SWITCH(51),
    HINDI_HELLO(52),
    MUSIC(53),
    COUNTDOWN(54),
    TIMER_COMPLETE(55),
    FAREWELL(56),
}

/**
 * Decides which animation NEX shows. Pure Kotlin: no Android imports, no Context,
 * so the whole decision table is unit testable.
 */
object AnimationDirector {

    /** What NEX shows when nothing else applies. */
    val DEFAULT_CUE: AnimationCue = AnimationCue.CALM_IDLE_BREATHING

    fun specFor(cue: AnimationCue): AnimationSpec = AnimationCatalog.byId(cue.animationId)

    fun resolve(cue: AnimationCue, reducedMotion: Boolean): ResolvedAnimation =
        AnimationCatalog.resolve(cue.animationId, reducedMotion)

    /** Deterministic state -> animation mapping for the conversation state machine. */
    fun cueForState(state: ConversationState): AnimationCue = when (state) {
        ConversationState.IDLE -> AnimationCue.CALM_IDLE_BREATHING
        ConversationState.PREPARING_VOICE -> AnimationCue.QUIET_LISTENING
        ConversationState.LISTENING_FOR_WAKE_PHRASE -> AnimationCue.LISTENING
        ConversationState.LISTENING_FOR_CONVERSATION -> AnimationCue.LISTENING
        ConversationState.RECOGNIZING -> AnimationCue.LISTENING
        ConversationState.THINKING -> AnimationCue.THINKING
        ConversationState.SPEAKING -> AnimationCue.SPEAKING
        ConversationState.RECOVERING_VOICE_SERVICE -> AnimationCue.VOICE_RECOVERING
        ConversationState.PAUSED_OR_PERMISSION_BLOCKED -> AnimationCue.MICROPHONE_OFF
        ConversationState.ERROR -> AnimationCue.ERROR
    }

    /** Intent -> animation mapping, used to colour a turn before the reply arrives. */
    fun cueForIntent(intent: UserIntent): AnimationCue = when (intent) {
        UserIntent.GREETING -> AnimationCue.GREETING
        UserIntent.FAREWELL -> AnimationCue.FAREWELL
        UserIntent.GRATITUDE -> AnimationCue.HAPPY_EYE_CRESCENT
        UserIntent.WEATHER -> AnimationCue.WEATHER_CLOUD
        UserIntent.NEWS -> AnimationCue.INTERNET_SEARCH
        UserIntent.TIME_SENSITIVE_QUERY -> AnimationCue.INTERNET_SEARCH
        UserIntent.LOCATION_QUERY -> AnimationCue.INTERNET_SEARCH
        UserIntent.STUDY_HELP -> AnimationCue.STUDY_FOCUS
        UserIntent.MATHS -> AnimationCue.MATHS
        UserIntent.SCIENCE -> AnimationCue.SCIENCE
        UserIntent.CODING -> AnimationCue.CODING
        UserIntent.CREATIVITY -> AnimationCue.CREATIVITY
        UserIntent.BOOK_OR_DOCUMENT -> AnimationCue.BOOK_LEARNING
        UserIntent.MEMORY_SAVE -> AnimationCue.MEMORY_SAVE
        UserIntent.MEMORY_RECALL -> AnimationCue.MEMORY_RECALL
        UserIntent.MEMORY_LIST -> AnimationCue.MEMORY_RECALL
        UserIntent.MEMORY_DELETE -> AnimationCue.PRIVACY_SHIELD
        UserIntent.VISION_REQUEST -> AnimationCue.VISION_ANALYZING
        UserIntent.PRIVACY_HELP -> AnimationCue.PRIVACY_SHIELD
        UserIntent.PERMISSION_HELP -> AnimationCue.PERMISSION_REQUEST
        UserIntent.SETTINGS_OR_VOICE_COMMAND -> AnimationCue.SUCCESS
        UserIntent.TIMER_START -> AnimationCue.COUNTDOWN
        UserIntent.MUSIC -> AnimationCue.MUSIC
        UserIntent.OFFLINE_SMALLTALK -> AnimationCue.FRIENDLY_WAVE
        UserIntent.GENERAL -> AnimationCue.CURIOUS_EYEBROW_LIFT
    }

    /** Weather detail -> animation mapping. */
    fun cueForWeather(kind: WeatherKind): AnimationCue = when (kind) {
        WeatherKind.SUN -> AnimationCue.WEATHER_SUN
        WeatherKind.RAIN -> AnimationCue.WEATHER_RAIN
        WeatherKind.CLOUD -> AnimationCue.WEATHER_CLOUD
        WeatherKind.STORM -> AnimationCue.WEATHER_STORM
        WeatherKind.UNKNOWN -> AnimationCue.WEATHER_CLOUD
    }

    /** Cues used for grounded (live information) turns. */
    fun cueForGrounding(grounded: Boolean, sourceCount: Int): AnimationCue = when {
        grounded && sourceCount > 0 -> AnimationCue.SOURCE_FOUND
        grounded -> AnimationCue.INTERNET_SEARCH
        else -> AnimationCue.THINKING
    }

    fun isLooping(cue: AnimationCue): Boolean = specFor(cue).loop

    fun allCues(): List<AnimationCue> = AnimationCue.entries.toList()
}
