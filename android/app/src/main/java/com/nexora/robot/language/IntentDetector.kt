package com.nexora.robot.language

/**
 * On-device intent detection for English, Hindi and Hinglish (Roman-script Hindi).
 *
 * This is deliberately a transparent keyword classifier, not a model: it is fast,
 * explainable, works offline, and it never sends anything anywhere by itself. Its only
 * jobs are to (a) choose an animation, (b) decide whether live information is needed and
 * (c) decide whether a camera or location permission request is justified.
 */
object IntentDetector {

    // --- vision ---------------------------------------------------------
    private val VISION_PHRASES = setOf(
        "what is this", "what's this", "whats this", "what is that", "look at this",
        "see this", "identify this", "read this", "what am i holding", "what do you see",
        "yah kya hai", "ye kya hai", "yeh kya hai", "dekho ise", "dekho isko",
        "यह क्या है", "ये क्या है", "देखो इसे", "इसे पहचानो",
    )

    // --- memory ---------------------------------------------------------
    private val MEMORY_DELETE_PHRASES = setOf(
        "forget that", "forget this", "forget what", "delete my memory", "delete that memory",
        "erase everything", "erase my memory", "remove that memory", "clear your memory",
        "bhool jao", "bhul jao", "hata do", "mita do",
        "भूल जाओ", "हटा दो", "मिटा दो",
    )
    private val MEMORY_LIST_PHRASES = setOf(
        "what do you remember", "what have you remembered", "what all do you remember",
        "show me my memory", "show my memory", "list what you remember", "what do you know about me",
        "tumhe kya yaad hai", "तुम्हें क्या याद है", "क्या याद है",
    )
    private val MEMORY_RECALL_PHRASES = setOf(
        "do you remember", "did i tell you", "remember what i said", "recall my", "recall that",
        "kya tumhe yaad hai", "क्या तुम्हें याद है", "याद है",
    )
    private val MEMORY_SAVE_WORDS = setOf("remember", "note", "save", "yaad", "याद")
    private val MEMORY_SAVE_PHRASES = setOf(
        "remember that", "remember this", "remember i", "remember my", "note this", "note that",
        "save this", "keep this in mind", "yaad rakhna", "yaad rakho", "याद रखो", "याद रखना",
    )

    // --- privacy & permissions ------------------------------------------
    private val PERMISSION_WORDS = setOf("permission", "permissions", "अनुमति", "इजाजत")
    private val PERMISSION_PHRASES = setOf(
        "why do you need", "why do you want", "permission kyu", "kya tum recording",
    )
    private val PRIVACY_WORDS = setOf(
        "privacy", "private", "data", "गोपनीयता", "निजता", "डेटा",
    )
    private val PRIVACY_PHRASES = setOf(
        "what do you store", "are you recording", "are you listening", "who can see",
        "delete my data", "my personal data", "what can you see", "is my data safe",
        "mera data", "tum kya store",
    )

    // --- voice / settings commands --------------------------------------
    private val SETTINGS_PHRASES = setOf(
        "stop listening", "pause listening", "resume listening", "be quiet", "quiet mode",
        "go to sleep", "sleep mode", "rest mode", "wake up", "reduced motion", "reduce motion",
        "switch to hindi", "hindi mode", "switch to english", "english mode", "turn off voice",
        "turn on voice", "voice off", "voice on", "mute yourself", "open settings",
        "ruk jao", "band karo", "chup ho jao", "so jao", "utho",
        "रुको", "बंद करो", "चुप हो जाओ", "सो जाओ", "उठो",
    )
    private val TIMER_WORDS = setOf("timer", "countdown", "टाइमर", "काउंटडाउन")
    private val TIMER_PHRASES = setOf(
        "set a timer", "start a timer", "remind me in", "timer laga", "टाइमर लगाओ",
    )
    private val MUSIC_WORDS = setOf(
        "music", "song", "sing", "gaana", "gana", "गीत", "गाना", "संगीत",
    )

    // --- live information -----------------------------------------------
    private val WEATHER_WORDS = setOf(
        "weather", "temperature", "rain", "raining", "rains", "forecast", "humidity", "storm",
        "mausam", "barish", "baarish", "garmi", "sardi", "thand", "dhoop",
        "मौसम", "बारिश", "तापमान", "गर्मी", "सर्दी", "ठंड", "धूप", "तूफान",
    )
    private val NEWS_WORDS = setOf(
        "news", "headlines", "samachar", "khabar", "khabrein", "समाचार", "खबर", "खबरें",
    )
    private val TIME_WORDS = setOf(
        "today", "tonight", "tomorrow", "yesterday", "now", "latest", "current", "currently",
        "live", "recent", "breaking", "update", "updates", "score", "scores", "price", "prices",
        "rate", "rates", "result", "results", "election", "aaj", "abhi", "kal", "taza", "tajaa",
        "nateeja", "keemat", "daam", "chunav",
        "आज", "अभी", "कल", "ताज़ा", "ताजा", "नतीजा", "कीमत", "दाम", "चुनाव",
    )
    private val LOCATION_WORDS = setOf(
        "where", "nearby", "location", "distance", "direction", "kahan", "kaha", "paas",
        "कहाँ", "कहां", "दूरी", "नजदीक", "निकट",
    )

    // --- subjects -------------------------------------------------------
    private val MATHS_WORDS = setOf(
        "maths", "math", "mathematics", "arithmetic", "algebra", "geometry", "fraction",
        "fractions", "equation", "percentage", "multiply", "division", "गणित", "हिसाब", "अंकगणित",
    )
    private val SCIENCE_WORDS = setOf(
        "science", "physics", "chemistry", "biology", "atom", "molecule", "cell", "photosynthesis",
        "gravity", "विज्ञान", "भौतिकी", "रसायन", "जीवविज्ञान", "कोशिका",
    )
    private val CODING_WORDS = setOf(
        "code", "coding", "program", "programming", "python", "java", "kotlin", "javascript",
        "function", "variable", "loop", "bug", "debug", "algorithm", "कोड", "प्रोग्राम",
    )
    private val CREATIVITY_WORDS = setOf(
        "story", "poem", "poetry", "draw", "drawing", "paint", "idea", "imagine", "creative",
        "कविता", "कहानी", "चित्र", "कला",
    )
    private val BOOK_WORDS = setOf(
        "book", "chapter", "page", "textbook", "pdf", "document", "किताब", "अध्याय", "पन्ना", "दस्तावेज़",
    )
    private val STUDY_WORDS = setOf(
        "study", "studies", "revise", "revision", "homework", "exam", "exams", "syllabus",
        "notes", "class", "subject", "assignment", "padhai", "pariksha",
        "पढ़ाई", "परीक्षा", "होमवर्क", "सिलेबस", "नोट्स",
    )

    // --- social ---------------------------------------------------------
    private val FAREWELL_PHRASES = setOf(
        "good night", "goodbye", "bye bye", "see you", "talk later", "good night nex",
        "alvida", "phir milenge", "shubh ratri", "शुभ रात्रि", "अलविदा", "फिर मिलेंगे",
    )
    private val FAREWELL_WORDS = setOf("bye", "goodnight", "alvida", "अलविदा")
    private val GRATITUDE_WORDS = setOf(
        "thanks", "thank", "thankyou", "shukriya", "dhanyavad", "dhanyawad", "धन्यवाद", "शुक्रिया",
    )
    private val GRATITUDE_PHRASES = setOf("thank you", "thanks nex", "dhanyavad", "शुक्रिया", "धन्यवाद")
    private val GREETING_PHRASES = setOf(
        "good morning", "good afternoon", "good evening", "namaste", "namaskar", "suprabhat",
        "सुप्रभात", "नमस्ते", "नमस्कार", "प्रणाम",
    )
    private val GREETING_WORDS = setOf(
        "hello", "hi", "hey", "namaste", "namaskar", "hii", "hlo", "नमस्ते", "हैलो",
    )
    private val SMALLTALK_PHRASES = setOf(
        "how are you", "are you ok", "are you okay", "who are you", "what can you do",
        "tell me a joke", "say something funny", "kaise ho", "tum kaun ho", "kaisa hai",
        "कैसे हो", "तुम कौन हो",
    )

    /**
     * Classify one utterance. Order matters: specific commands and privacy questions are
     * matched before subjects, and subjects before generic time words.
     */
    fun detect(text: String): UserIntent {
        val normalized = TextNormalizer.normalize(text)
        if (normalized.isBlank()) return UserIntent.GENERAL
        val tokens = normalized.split(' ')

        return when {
            TextNormalizer.containsPhrase(normalized, VISION_PHRASES) -> UserIntent.VISION_REQUEST
            TextNormalizer.containsPhrase(normalized, PERMISSION_PHRASES) ||
                TextNormalizer.containsToken(tokens, PERMISSION_WORDS) -> UserIntent.PERMISSION_HELP
            TextNormalizer.containsPhrase(normalized, PRIVACY_PHRASES) ||
                TextNormalizer.containsToken(tokens, PRIVACY_WORDS) -> UserIntent.PRIVACY_HELP
            TextNormalizer.containsPhrase(normalized, MEMORY_DELETE_PHRASES) -> UserIntent.MEMORY_DELETE
            TextNormalizer.containsPhrase(normalized, MEMORY_LIST_PHRASES) -> UserIntent.MEMORY_LIST
            TextNormalizer.containsPhrase(normalized, MEMORY_RECALL_PHRASES) -> UserIntent.MEMORY_RECALL
            TextNormalizer.containsPhrase(normalized, MEMORY_SAVE_PHRASES) -> UserIntent.MEMORY_SAVE
            TextNormalizer.containsPhrase(normalized, SETTINGS_PHRASES) -> UserIntent.SETTINGS_OR_VOICE_COMMAND
            TextNormalizer.containsPhrase(normalized, TIMER_PHRASES) ||
                TextNormalizer.containsToken(tokens, TIMER_WORDS) -> UserIntent.TIMER_START
            TextNormalizer.containsToken(tokens, WEATHER_WORDS) -> UserIntent.WEATHER
            TextNormalizer.containsToken(tokens, NEWS_WORDS) -> UserIntent.NEWS
            TextNormalizer.containsToken(tokens, MUSIC_WORDS) -> UserIntent.MUSIC
            TextNormalizer.containsToken(tokens, LOCATION_WORDS) -> UserIntent.LOCATION_QUERY
            TextNormalizer.containsToken(tokens, MATHS_WORDS) -> UserIntent.MATHS
            TextNormalizer.containsToken(tokens, SCIENCE_WORDS) -> UserIntent.SCIENCE
            TextNormalizer.containsToken(tokens, CODING_WORDS) -> UserIntent.CODING
            TextNormalizer.containsToken(tokens, CREATIVITY_WORDS) -> UserIntent.CREATIVITY
            TextNormalizer.containsToken(tokens, BOOK_WORDS) -> UserIntent.BOOK_OR_DOCUMENT
            TextNormalizer.containsToken(tokens, STUDY_WORDS) -> UserIntent.STUDY_HELP
            TextNormalizer.containsToken(tokens, TIME_WORDS) -> UserIntent.TIME_SENSITIVE_QUERY
            TextNormalizer.containsPhrase(normalized, FAREWELL_PHRASES) ||
                TextNormalizer.containsToken(tokens, FAREWELL_WORDS) -> UserIntent.FAREWELL
            TextNormalizer.containsPhrase(normalized, GRATITUDE_PHRASES) ||
                TextNormalizer.containsToken(tokens, GRATITUDE_WORDS) -> UserIntent.GRATITUDE
            TextNormalizer.containsPhrase(normalized, GREETING_PHRASES) ||
                TextNormalizer.containsToken(tokens, GREETING_WORDS) -> UserIntent.GREETING
            TextNormalizer.containsPhrase(normalized, SMALLTALK_PHRASES) -> UserIntent.OFFLINE_SMALLTALK
            // A bare "remember ..." with no explicit phrase is still a save request.
            TextNormalizer.containsToken(tokens, MEMORY_SAVE_WORDS) -> UserIntent.MEMORY_SAVE
            else -> UserIntent.GENERAL
        }
    }

    /** Coarse weather flavour, used only to pick a screen animation. */
    fun detectWeatherKind(text: String): WeatherKind {
        val normalized = TextNormalizer.normalize(text)
        val tokens = normalized.split(' ')
        val storm = setOf("storm", "thunder", "lightning", "tufan", "toofan", "bijli", "तूफान", "बिजली", "गरज")
        val rain = setOf("rain", "raining", "rains", "shower", "barish", "baarish", "बारिश")
        val cloud = setOf("cloud", "clouds", "cloudy", "overcast", "badal", "बादल")
        val sun = setOf("sun", "sunny", "heat", "hot", "dhoop", "garmi", "धूप", "गर्मी")
        return when {
            TextNormalizer.containsToken(tokens, storm) -> WeatherKind.STORM
            TextNormalizer.containsToken(tokens, rain) -> WeatherKind.RAIN
            TextNormalizer.containsToken(tokens, cloud) -> WeatherKind.CLOUD
            TextNormalizer.containsToken(tokens, sun) -> WeatherKind.SUN
            else -> WeatherKind.UNKNOWN
        }
    }

    /** True when the utterance is a direct request for a place, needed for location consent. */
    fun isDirectLocationRequest(text: String): Boolean {
        val normalized = TextNormalizer.normalize(text)
        val tokens = normalized.split(' ')
        val locationIntentWords = setOf(
            "where", "nearby", "location", "कहाँ", "कहां", "नजदीक", "निकट",
        )
        val weatherOrPlace = TextNormalizer.containsToken(tokens, WEATHER_WORDS)
        return TextNormalizer.containsToken(tokens, locationIntentWords) ||
            (weatherOrPlace && normalized.contains("here")) ||
            (weatherOrPlace && normalized.contains("yahan")) ||
            normalized.contains("मेरे पास") ||
            normalized.contains("मेरी जगह")
    }
}
