package com.nexora.robot.offline

import com.nexora.robot.animation.AnimationCue

/**
 * One animal NEX can imitate.
 *
 * @property sound exactly what TTS speaks; plain words only, never an emoji, so every
 *   engine pronounces it instead of skipping glyphs
 * @property emoji shown on screen next to the transcript line
 * @property aliases whole-word matchers, lower case: English singular + plural, a pet
 *   word, and a Hindi name so "कुत्ते की आवाज़" works too
 */
data class AnimalSound(
    val name: String,
    val emoji: String,
    val sound: String,
    val aliases: List<String>,
)

/**
 * One language NEX can say hello in.
 *
 * @property tag BCP-47 style tag handed to [com.nexora.robot.voice.TtsVoice]; the engine
 *   decides whether it owns a voice for it
 * @property text the greeting in its own script, spoken verbatim when supported
 */
data class Greeting(
    val tag: String,
    val englishName: String,
    val flag: String,
    val text: String,
    val aliases: List<String>,
) {
    /** One-line gloss for the screen, e.g. "Hello in Tamil 🇮🇳". */
    val gloss: String get() = "Hello in $englishName $flag"

    /**
     * What NEX says when this device has no TTS voice for [tag]. Honest, never a mangled
     * attempt: the script stays on screen, the voice admits its limit in the user's language.
     */
    fun unsupportedLine(language: String): String =
        if (language.lowercase().startsWith("hi")) {
            "इस फ़ोन की आवाज़ अभी $englishName नहीं बोल सकती। $englishName में नमस्ते स्क्रीन पर लिखा है।"
        } else {
            "This phone's voice cannot speak $englishName yet. Hello in $englishName is on the screen."
        }
}

/**
 * A parsed party trick. Both variants are answered entirely on-device: nothing is sent to
 * the server, so these work with no network and cost no quota.
 */
sealed interface PetTrick {
    val cue: AnimationCue

    data class AnimalVoice(val animal: AnimalSound) : PetTrick {
        override val cue: AnimationCue get() = AnimationCue.PLAYFUL
    }

    data class GreetingIn(val greeting: Greeting) : PetTrick {
        override val cue: AnimationCue get() = AnimationCue.FRIENDLY_WAVE
    }
}

/**
 * NEX's party tricks: twenty animal voices and hello in twenty languages.
 *
 * Matching rules, deliberately strict and fully unit tested ([PetTricksTest]):
 *  * every ASCII alias and trigger is matched as a **whole word**, so "they", "cows", "or" and
 *    "category" can never fire a trick by accident; non-ASCII aliases match as substrings,
 *    which is safe because each one is a distinctive word of its own;
 *  * an animal trick needs an animal word **and** a sound word ("sound", "like", "bolo",
 *    "आवाज़"…), so "I have a dog at home" stays an ordinary conversation turn;
 *  * a greeting trick needs a hello word **and** a language name, so a plain "hello" still
 *    reaches the normal [com.nexora.robot.language.UserIntent.GREETING] path;
 *  * greetings are checked first, then animals; if neither matches, [parse] returns null and
 *    the turn proceeds exactly as before.
 *
 * Pure Kotlin: no Android imports, no Context, so the whole table is testable on the JVM.
 */
object PetTricks {

    /** The twenty animals, in the order the product list defines them. */
    val ANIMALS: List<AnimalSound> = listOf(
        AnimalSound("Dog", "🐶", "Woof! Woof!", listOf("dog", "dogs", "doggy", "puppy", "kutta", "कुत्ता", "कुत्ते")),
        AnimalSound("Cat", "🐱", "Meowww!", listOf("cat", "cats", "kitten", "kitty", "billi", "बिल्ली")),
        AnimalSound("Cow", "🐮", "Moo!", listOf("cow", "cows", "calf", "gaay", "गाय")),
        AnimalSound("Lion", "🦁", "Roaaar!", listOf("lion", "lions", "sher", "शेर")),
        AnimalSound("Frog", "🐸", "Ribbit! Ribbit!", listOf("frog", "frogs", "mendhak", "मेंढक")),
        AnimalSound("Monkey", "🐵", "Ooh-ooh! Ah-ah!", listOf("monkey", "monkeys", "bandar", "बंदर")),
        AnimalSound("Chicken", "🐔", "Cluck-cluck!", listOf("chicken", "chickens", "murgi", "मुर्गी")),
        AnimalSound("Duck", "🦆", "Quack-quack!", listOf("duck", "ducks", "batakh", "बत्तख")),
        AnimalSound("Elephant", "🐘", "Prrrrrr!", listOf("elephant", "elephants", "haathi", "हाथी")),
        AnimalSound("Sheep", "🐑", "Baaaa!", listOf("sheep", "lamb", "lambs", "bhed", "भेड़")),
        AnimalSound("Horse", "🐴", "Neigh!", listOf("horse", "horses", "pony", "ghoda", "घोड़ा")),
        AnimalSound("Pig", "🐷", "Oink-oink!", listOf("pig", "pigs", "piggy", "suar", "सूअर")),
        AnimalSound("Snake", "🐍", "Hissss!", listOf("snake", "snakes", "saanp", "साँप", "सांप")),
        AnimalSound("Wolf", "🐺", "Awooooo!", listOf("wolf", "wolves", "bhediya", "भेड़िया")),
        AnimalSound("Dolphin", "🐬", "Click-click!", listOf("dolphin", "dolphins")),
        AnimalSound("Bird", "🐦", "Tweet-tweet!", listOf("bird", "birds", "sparrow", "chidiya", "चिड़िया")),
        AnimalSound("Bear", "🐻", "Grrrr!", listOf("bear", "bears", "bhalu", "भालू")),
        AnimalSound("Owl", "🦉", "Hoo-hoo!", listOf("owl", "owls", "ullu", "उल्लू")),
        AnimalSound("Turkey", "🦃", "Gobble-gobble!", listOf("turkey", "turkeys")),
        AnimalSound("Rooster", "🐓", "Cock-a-doodle-doo!", listOf("rooster", "roosters", "cockerel", "murga", "मुर्गा")),
    )

    /** The twenty greetings, in the order the product list defines them. */
    val GREETINGS: List<Greeting> = listOf(
        Greeting("en", "English", "🇬🇧", "Hello!", listOf("english", "angrezi", "अंग्रेज़ी")),
        Greeting("hi", "Hindi", "🇮🇳", "नमस्ते!", listOf("hindi", "हिंदी", "हिन्दी")),
        Greeting("bn", "Bengali", "🇮🇳", "নমস্কার!", listOf("bengali", "bangla", "বাংলা", "बंगाली")),
        Greeting("bho", "Bhojpuri", "🇮🇳", "प्रणाम!", listOf("bhojpuri", "भोजपुरी")),
        Greeting("mai", "Maithili", "🇮🇳", "प्रणाम!", listOf("maithili", "मैथिली")),
        Greeting("mr", "Marathi", "🇮🇳", "नमस्कार!", listOf("marathi", "मराठी")),
        Greeting("ta", "Tamil", "🇮🇳", "வணக்கம்!", listOf("tamil", "तमिल", "தமிழ்")),
        Greeting("te", "Telugu", "🇮🇳", "నమస్కారం!", listOf("telugu", "तेलुगु", "తెలుగు")),
        Greeting("kn", "Kannada", "🇮🇳", "ನಮಸ್ಕಾರ!", listOf("kannada", "कन्नड़", "ಕನ್ನಡ")),
        Greeting("ml", "Malayalam", "🇮🇳", "നമസ്കാരം!", listOf("malayalam", "मलयालम", "മലയാളം")),
        Greeting("pa", "Punjabi", "🇮🇳", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ!", listOf("punjabi", "पंजाबी", "ਪੰਜਾਬੀ")),
        Greeting("gu", "Gujarati", "🇮🇳", "નમસ્તે!", listOf("gujarati", "गुजराती", "ગુજરાતી")),
        Greeting("or", "Odia", "🇮🇳", "ନମସ୍କାର!", listOf("odia", "oriya", "ओडिया", "ଓଡ଼ିଆ")),
        Greeting("as", "Assamese", "🇮🇳", "নমস্কাৰ!", listOf("assamese", "असमिया", "অসমীয়া")),
        Greeting("ur", "Urdu", "🇮🇳", "آداب!", listOf("urdu", "उर्दू", "اردو")),
        Greeting("sa", "Sanskrit", "🇮🇳", "नमस्ते!", listOf("sanskrit", "संस्कृत")),
        Greeting("ja", "Japanese", "🇯🇵", "こんにちは!", listOf("japanese", "जापानी", "日本語")),
        Greeting("zh", "Chinese", "🇨🇳", "你好!", listOf("chinese", "mandarin", "चीनी", "中文")),
        Greeting("es", "Spanish", "🇪🇸", "¡Hola!", listOf("spanish", "स्पेनिश", "espanol", "español")),
        Greeting("fr", "French", "🇫🇷", "Bonjour!", listOf("french", "फ्रेंच", "francais", "français")),
    )

    /** Words that turn an animal mention into "please imitate it". */
    private val SOUND_TRIGGERS: List<String> = listOf(
        "sound", "sounds", "voice", "cry", "call", "like", "imitate", "speak", "say", "noise",
        "bolo", "bol", "bolo na", "awaaz", "aawaz", "avaz", "आवाज़", "आवाज", "बोलो", "बोल",
    )

    /** Words that turn a language mention into "please greet me in it". */
    private val GREETING_TRIGGERS: List<String> = listOf(
        "hello", "hey", "greet", "greets", "greeting", "greetings", "namaste", "namaskar",
        "pranaam", "salaam", "हैलो", "नमस्ते", "नमस्कार", "प्रणाम", "आदाब",
    )

    /** The trick the user asked for, or null when this is ordinary conversation. */
    fun parse(text: String): PetTrick? {
        val normalized = text.lowercase().trim()
        if (normalized.isBlank()) return null
        greetingIn(normalized)?.let { return PetTrick.GreetingIn(it) }
        animalVoice(normalized)?.let { return PetTrick.AnimalVoice(it) }
        return null
    }

    private fun greetingIn(normalized: String): Greeting? {
        if (!GREETING_TRIGGERS.any { wholeWord(normalized, it) }) return null
        return GREETINGS.firstOrNull { greeting -> greeting.aliases.any { wholeWord(normalized, it) } }
    }

    private fun animalVoice(normalized: String): AnimalSound? {
        if (!SOUND_TRIGGERS.any { wholeWord(normalized, it) }) return null
        return ANIMALS.firstOrNull { animal -> animal.aliases.any { wholeWord(normalized, it) } }
    }

    /**
     * Whole-word match for ASCII aliases, so "they" never contains "hey" and "cows" never
     * contains "cow". Devanagari and other scripts get a plain substring match instead:
     * Java's `\b` is defined over ASCII word characters only, so around a Devanagari run
     * there is never a boundary at all and a `\b`-wrapped Devanagari needle could never
     * match. Substring is safe there because every non-ASCII alias is a distinctive word.
     */
    private fun wholeWord(haystack: String, needle: String): Boolean =
        if (needle.all { it.code < 128 }) {
            Regex("\\b" + Regex.escape(needle) + "\\b").containsMatchIn(haystack)
        } else {
            haystack.contains(needle)
        }
}
