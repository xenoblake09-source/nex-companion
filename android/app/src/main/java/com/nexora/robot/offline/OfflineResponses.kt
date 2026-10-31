package com.nexora.robot.offline

import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.language.UserIntent

/**
 * One offline answer.
 *
 * @property claimsInternet always false — an offline reply never says it searched anything
 * @property speak whether the reply should also be spoken by TTS
 */
data class OfflineReply(
    val text: String,
    val animationCue: AnimationCue,
    val claimsInternet: Boolean = false,
    val speak: Boolean = true,
)

/**
 * What NEX says when there is no server or no network.
 *
 * The honesty rules here are strict and unit tested (`OfflineResponsesTest`):
 *
 *  * live information (weather, news, prices, results, places) is **refused**, never guessed;
 *  * nothing offline ever claims internet access, and [OfflineReply.claimsInternet] is always false;
 *  * what genuinely works offline still works: greetings, privacy and permission help,
 *    study prompts, local memory, settings acknowledgements and the focus timer;
 *  * no reply ever invents a number, a date, a headline or a source.
 *
 * The `when` over [UserIntent] is exhaustive, so a new intent cannot silently fall through
 * to a made-up answer.
 */
object OfflineResponses {

    /** Marker used by tests and logs; never shown to the user. */
    const val OFFLINE_MARKER = "offline"

    fun isHindi(language: String?): Boolean =
        !language.isNullOrBlank() && language.lowercase().startsWith("hi")

    /**
     * The offline answer for one intent.
     *
     * @param timerMinutes set by the caller when a focus timer was actually started locally
     */
    fun forIntent(
        intent: UserIntent,
        language: String = "en",
        timerMinutes: Int? = null,
    ): OfflineReply {
        val hindi = isHindi(language)
        return when (intent) {
            UserIntent.GREETING -> greeting(language)
            UserIntent.FAREWELL -> farewell(language)
            UserIntent.GRATITUDE -> thanks(language)

            // --- live information: refused, never guessed -----------------
            UserIntent.WEATHER -> OfflineReply(
                text = if (hindi) {
                    "मैं ऑफलाइन हूँ, इसलिए मैं अभी मौसम नहीं देख सकता। मैं अनुमान नहीं लगाऊँगा।"
                } else {
                    "I am offline, so I cannot check the weather right now. I will not guess it."
                },
                animationCue = AnimationCue.OFFLINE,
            )
            UserIntent.NEWS -> OfflineReply(
                text = if (hindi) {
                    "मैं ऑफलाइन हूँ, इसलिए मैं ताज़ा खबरें नहीं ला सकता। मैं कोई खबर गढ़ूँगा नहीं।"
                } else {
                    "I am offline, so I cannot fetch news. I will not invent a headline."
                },
                animationCue = AnimationCue.OFFLINE,
            )
            UserIntent.TIME_SENSITIVE_QUERY -> OfflineReply(
                text = if (hindi) {
                    "मैं ऑफलाइन हूँ, इसलिए बदलती हुई जानकारी मैं अभी नहीं देख सकता। कनेक्शन आने पर फिर पूछिए।"
                } else {
                    "I am offline, so I cannot check anything that changes. Ask me again once I am connected."
                },
                animationCue = AnimationCue.OFFLINE,
            )
            UserIntent.LOCATION_QUERY -> OfflineReply(
                text = if (hindi) {
                    "मैं ऑफलाइन हूँ, इसलिए मैं अभी जगहें नहीं ढूँढ़ सकता।"
                } else {
                    "I am offline, so I cannot look up places right now."
                },
                animationCue = AnimationCue.OFFLINE,
            )
            UserIntent.VISION_REQUEST -> visionUnavailable(language)

            // --- things that genuinely work offline -----------------------
            UserIntent.STUDY_HELP,
            UserIntent.MATHS,
            UserIntent.SCIENCE,
            UserIntent.CODING,
            UserIntent.CREATIVITY,
            UserIntent.BOOK_OR_DOCUMENT,
            -> studyPrompt(intent, language)

            UserIntent.MEMORY_SAVE -> OfflineReply(
                text = if (hindi) "ठीक है, मैं इसे सिर्फ़ इस डिवाइस पर याद रखूँगा।"
                else "Okay, I will remember that on this device only.",
                animationCue = AnimationCue.MEMORY_SAVE,
            )
            UserIntent.MEMORY_RECALL, UserIntent.MEMORY_LIST -> OfflineReply(
                text = if (hindi) "मैं सिर्फ़ वही बता सकता हूँ जो इस डिवाइस पर सहेजा गया है।"
                else "I can only tell you what is stored on this device.",
                animationCue = AnimationCue.MEMORY_RECALL,
            )
            UserIntent.MEMORY_DELETE -> memoryDeletedAck(language)

            UserIntent.PRIVACY_HELP -> privacyHelp(language)
            UserIntent.PERMISSION_HELP -> permissionHelp(language)

            UserIntent.SETTINGS_OR_VOICE_COMMAND -> OfflineReply(
                text = if (hindi) "ठीक है, हो गया।" else "Okay, done.",
                animationCue = AnimationCue.SUCCESS,
            )

            UserIntent.TIMER_START -> if (timerMinutes != null && timerMinutes > 0) {
                OfflineReply(
                    text = if (hindi) {
                        "ठीक है, $timerMinutes मिनट का फोकस टाइमर शुरू। मैं काउंटडाउन दिखाऊँगा।"
                    } else {
                        "Okay, a $timerMinutes minute focus timer is running. I will show the countdown."
                    },
                    animationCue = AnimationCue.COUNTDOWN,
                )
            } else {
                OfflineReply(
                    text = if (hindi) "कितने मिनट का टाइमर लगाऊँ?"
                    else "How many minutes should the focus timer be?",
                    animationCue = AnimationCue.COUNTDOWN,
                )
            }

            UserIntent.MUSIC -> OfflineReply(
                text = if (hindi) "इस बिल्ड में संगीत बजाना शामिल नहीं है, इसलिए मैं वह नहीं कर सकता।"
                else "Playing music is not part of this build, so I cannot do that.",
                animationCue = AnimationCue.CONFUSED,
            )

            UserIntent.OFFLINE_SMALLTALK -> OfflineReply(
                text = if (hindi) {
                    "मैं आपके साथ हूँ। ऑफलाइन मैं पढ़ाई के प्रॉम्प्ट, प्राइवेसी के सवाल और सेटिंग्स में मदद कर सकता हूँ।"
                } else {
                    "I am here with you. Offline I can still help with study prompts, privacy questions and settings."
                },
                animationCue = AnimationCue.FRIENDLY_WAVE,
            )

            UserIntent.GENERAL -> offlineNotice(language)
        }
    }

    // ------------------------------------------------------------------
    // Reusable pieces
    // ------------------------------------------------------------------

    fun greeting(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) "नमस्ते! मैं नेक्स हूँ। मैं आपके साथ हूँ।"
        else "Hello! I am NEX. I am right here with you.",
        animationCue = if (isHindi(language)) AnimationCue.HINDI_HELLO else AnimationCue.GREETING,
    )

    fun farewell(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) "अलविदा! आप वापस आएँगे तो मैं यहीं मिलूँगा।"
        else "Goodbye! I will be right here when you come back.",
        animationCue = AnimationCue.FAREWELL,
    )

    fun thanks(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) "आपका स्वागत है। मुझे अच्छा लगा।"
        else "You are welcome. That made me happy.",
        animationCue = AnimationCue.HAPPY_EYE_CRESCENT,
    )

    /** The truthful "I cannot reach my server" notice. */
    fun offlineNotice(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) {
            "मैं अभी ऑफलाइन हूँ, इसलिए मैं अपने सर्वर तक नहीं पहुँच पा रहा। मैं जवाब गढ़ूँगा नहीं।"
        } else {
            "I am offline right now, so I cannot reach my server. I will not make up an answer."
        },
        animationCue = AnimationCue.OFFLINE,
    )

    /** Used when the device has network but the backend did not answer. */
    fun serverUnreachable(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) {
            "मेरा सर्वर अभी जवाब नहीं दे रहा। मैं कुछ भी अनुमान नहीं करूँगा। थोड़ी देर में फिर कोशिश कीजिए।"
        } else {
            "My server is not answering right now. I will not guess anything. Please try again in a little while."
        },
        animationCue = AnimationCue.NETWORK_WAITING,
    )

    fun visionUnavailable(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) {
            "चित्र देखने के लिए मुझे अपने सर्वर की ज़रूरत है, और मैं अभी ऑफलाइन हूँ।"
        } else {
            "I need my server to look at a picture, and I am offline right now."
        },
        animationCue = AnimationCue.OFFLINE,
    )

    fun privacyHelp(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) {
            "मैं माइक्रोफ़ोन सिर्फ़ तब उपयोग करता हूँ जब आप वॉइस मोड चालू करते हैं। कैमरा सिर्फ़ तब, " +
                "जब आप सामने रखी चीज़ के बारे में पूछते हैं। लोकेशन सिर्फ़ तब, जब आप अपने यहाँ का मौसम पूछते हैं। " +
                "जो मैं याद रखता हूँ वह इसी डिवाइस पर रहता है, और आप उसे कभी भी देख या मिटा सकते हैं।"
        } else {
            "I use the microphone only after you start voice mode. I use the camera only when you ask about " +
                "something in front of you. I ask for coarse location only for your local weather. Whatever I " +
                "remember stays on this device, and you can see it or delete it any time.",
        },
        animationCue = AnimationCue.PRIVACY_SHIELD,
    )

    fun permissionHelp(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) {
            "नेक्स माइक्रोफ़ोन की अनुमति सिर्फ़ वॉइस मोड शुरू करने पर माँगता है, और कैमरे की अनुमति सिर्फ़ " +
                "आपके देखने वाले सवाल पर। आप इन्हें कभी भी एंड्रॉइड सेटिंग्स में हटा सकते हैं।"
        } else {
            "NEX asks for the microphone only when you start voice mode, and for the camera only when you ask a " +
                "vision question. You can revoke either at any time in Android settings.",
        },
        animationCue = AnimationCue.PERMISSION_REQUEST,
    )

    fun memorySavedAck(language: String): OfflineReply = forIntent(UserIntent.MEMORY_SAVE, language)

    fun memoryDeletedAck(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) "ठीक है, यह इस डिवाइस से हटा दिया गया है।"
        else "Okay, that is deleted from this device.",
        animationCue = AnimationCue.PRIVACY_SHIELD,
    )

    fun nothingRemembered(language: String): OfflineReply = OfflineReply(
        text = if (isHindi(language)) "अभी मैंने कुछ भी याद नहीं रखा है।"
        else "I do not have anything remembered yet.",
        animationCue = AnimationCue.MEMORY_RECALL,
    )

    /** Fixed, locally written practice prompts. Never generated, never claimed to be research. */
    fun studyPrompt(intent: UserIntent, language: String): OfflineReply {
        val prompts = studyPrompts(intent, language)
        val prefix = if (isHindi(language)) "ऑफलाइन मैं आपके साथ पढ़ाई कर सकता हूँ। " else "We are offline, so here is a practice prompt. "
        return OfflineReply(
            text = prefix + prompts.first(),
            animationCue = when (intent) {
                UserIntent.MATHS -> AnimationCue.MATHS
                UserIntent.SCIENCE -> AnimationCue.SCIENCE
                UserIntent.CODING -> AnimationCue.CODING
                UserIntent.CREATIVITY -> AnimationCue.CREATIVITY
                UserIntent.BOOK_OR_DOCUMENT -> AnimationCue.BOOK_LEARNING
                else -> AnimationCue.STUDY_FOCUS
            },
        )
    }

    /** The prompt bank, exposed so the UI can list all of them instead of just the first. */
    fun studyPrompts(intent: UserIntent, language: String): List<String> {
        if (isHindi(language)) {
            return listOf(
                "अपना सबसे कठिन विषय बताइए, हम वहीं से शुरू करेंगे।",
                "दस मिनट का फोकस टाइमर लगाइए और बिना रुके पढ़िए।",
                "जो आपको पहले से आता है, वह बोलकर बताइए। मैं सुन रहा हूँ।",
            )
        }
        return when (intent) {
            UserIntent.MATHS -> listOf(
                "Say the seven times table out loud, slowly.",
                "Explain one fraction to me in your own words.",
                "Set a ten minute focus timer and finish three sums.",
            )
            UserIntent.SCIENCE -> listOf(
                "Name three things a plant needs to grow.",
                "Explain what an atom is in one sentence.",
                "Walk me through the water cycle in order.",
            )
            UserIntent.CODING -> listOf(
                "Say out loud what a function does.",
                "Describe one bug you fixed this week.",
                "List the steps of a loop in plain words.",
            )
            UserIntent.CREATIVITY -> listOf(
                "Make one sentence that begins with: because the sky.",
                "Describe a colour to someone who cannot see it.",
                "Invent a name for a friendly robot.",
            )
            UserIntent.BOOK_OR_DOCUMENT -> listOf(
                "Summarise the last page you read in one sentence.",
                "Tell me one character and one problem they have.",
                "Read one paragraph aloud, slowly.",
            )
            else -> listOf(
                "Tell me the subject and the chapter, and I will keep you company.",
                "Start with the hardest topic while your mind is fresh.",
                "Say out loud what you already know. I will listen.",
            )
        }
    }
}
