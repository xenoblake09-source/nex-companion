package com.nexora.robot.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intent detection for English, Hindi and Hinglish.
 *
 * The order of the rules is part of the contract: an explicit camera request, a privacy or
 * permission question and a memory command must win over a subject classification, because
 * those three decide whether NEX touches a sensor, explains itself, or writes to local memory.
 */
class IntentDetectorTest {

    @Test
    fun explicitCameraRequestsAreRecognisedInBothScripts() {
        listOf(
            "What is this?",
            "what's this",
            "What is that in front of me",
            "look at this",
            "read this page for me",
            "what am I holding",
            "yah kya hai",
            "ye kya hai",
            "dekho ise",
            "यह क्या है",
            "ये क्या है",
            "इसे पहचानो",
        ).forEach { utterance ->
            assertEquals(
                "'$utterance' must be a vision request",
                UserIntent.VISION_REQUEST,
                IntentDetector.detect(utterance),
            )
        }
    }

    @Test
    fun permissionAndPrivacyQuestionsBeatEverythingElse() {
        assertEquals(UserIntent.PERMISSION_HELP, IntentDetector.detect("why do you need my microphone"))
        assertEquals(UserIntent.PERMISSION_HELP, IntentDetector.detect("what permissions do you have"))
        assertEquals(UserIntent.PERMISSION_HELP, IntentDetector.detect("permission kyu chahiye"))
        assertEquals(UserIntent.PRIVACY_HELP, IntentDetector.detect("are you recording me"))
        assertEquals(UserIntent.PRIVACY_HELP, IntentDetector.detect("are you listening right now"))
        assertEquals(UserIntent.PRIVACY_HELP, IntentDetector.detect("what do you store about me"))
        assertEquals(UserIntent.PRIVACY_HELP, IntentDetector.detect("is my data safe"))
        assertEquals(UserIntent.PRIVACY_HELP, IntentDetector.detect("mera data kahan jata hai"))
        assertEquals(UserIntent.PRIVACY_HELP, IntentDetector.detect("मेरा डेटा सुरक्षित है"))
    }

    @Test
    fun memoryCommandsAreSplitIntoSaveRecallListAndDelete() {
        assertEquals(UserIntent.MEMORY_SAVE, IntentDetector.detect("remember that my exam is on monday"))
        assertEquals(UserIntent.MEMORY_SAVE, IntentDetector.detect("note this for me"))
        assertEquals(UserIntent.MEMORY_SAVE, IntentDetector.detect("yaad rakho mera favourite subject physics hai"))
        assertEquals(UserIntent.MEMORY_RECALL, IntentDetector.detect("do you remember my name"))
        assertEquals(UserIntent.MEMORY_LIST, IntentDetector.detect("what do you remember about me"))
        assertEquals(UserIntent.MEMORY_LIST, IntentDetector.detect("tumhe kya yaad hai"))
        assertEquals(UserIntent.MEMORY_DELETE, IntentDetector.detect("forget that"))
        assertEquals(UserIntent.MEMORY_DELETE, IntentDetector.detect("delete my memory"))
        assertEquals(UserIntent.MEMORY_DELETE, IntentDetector.detect("bhool jao"))
        assertEquals(UserIntent.MEMORY_DELETE, IntentDetector.detect("भूल जाओ"))
    }

    @Test
    fun aBareRememberWordIsStillASaveRequest() {
        assertEquals(UserIntent.MEMORY_SAVE, IntentDetector.detect("please note the following"))
        assertEquals(UserIntent.MEMORY_SAVE, IntentDetector.detect("yaad rakhna"))
    }

    @Test
    fun voiceAndSettingsCommandsAreLocal() {
        listOf(
            "stop listening",
            "be quiet",
            "go to sleep",
            "wake up",
            "reduced motion",
            "switch to hindi",
            "english mode",
            "turn off voice",
            "ruk jao",
            "chup ho jao",
            "सो जाओ",
        ).forEach { utterance ->
            assertEquals(
                "'$utterance' must be a settings command",
                UserIntent.SETTINGS_OR_VOICE_COMMAND,
                IntentDetector.detect(utterance),
            )
        }
    }

    @Test
    fun timerRequestsAreDetectedInBothScripts() {
        assertEquals(UserIntent.TIMER_START, IntentDetector.detect("set a timer for ten minutes"))
        assertEquals(UserIntent.TIMER_START, IntentDetector.detect("start a countdown"))
        assertEquals(UserIntent.TIMER_START, IntentDetector.detect("remind me in twenty minutes"))
        assertEquals(UserIntent.TIMER_START, IntentDetector.detect("टाइमर लगाओ"))
    }

    @Test
    fun liveInformationIntentsAreSeparated() {
        assertEquals(UserIntent.WEATHER, IntentDetector.detect("what is the weather today"))
        assertEquals(UserIntent.WEATHER, IntentDetector.detect("aaj barish hogi kya"))
        assertEquals(UserIntent.WEATHER, IntentDetector.detect("आज मौसम कैसा है"))
        assertEquals(UserIntent.NEWS, IntentDetector.detect("give me the headlines"))
        assertEquals(UserIntent.NEWS, IntentDetector.detect("aaj ki khabar batao"))
        assertEquals(UserIntent.LOCATION_QUERY, IntentDetector.detect("where is the nearest library"))
        assertEquals(UserIntent.LOCATION_QUERY, IntentDetector.detect("what is the distance to the station"))
        assertEquals(UserIntent.TIME_SENSITIVE_QUERY, IntentDetector.detect("what is the latest update"))
        assertEquals(UserIntent.TIME_SENSITIVE_QUERY, IntentDetector.detect("election result kya hai"))
    }

    @Test
    fun studySubjectsAreClassifiedSeparately() {
        assertEquals(UserIntent.MATHS, IntentDetector.detect("explain fractions in maths"))
        assertEquals(UserIntent.MATHS, IntentDetector.detect("गणित का सवाल है"))
        assertEquals(UserIntent.SCIENCE, IntentDetector.detect("what is photosynthesis"))
        assertEquals(UserIntent.SCIENCE, IntentDetector.detect("explain gravity"))
        assertEquals(UserIntent.CODING, IntentDetector.detect("write a python function"))
        assertEquals(UserIntent.CODING, IntentDetector.detect("my code has a bug"))
        assertEquals(UserIntent.CREATIVITY, IntentDetector.detect("tell me a story"))
        assertEquals(UserIntent.CREATIVITY, IntentDetector.detect("write a poem about the moon"))
        assertEquals(UserIntent.BOOK_OR_DOCUMENT, IntentDetector.detect("read chapter three of my book"))
        assertEquals(UserIntent.STUDY_HELP, IntentDetector.detect("help me with my homework"))
        assertEquals(UserIntent.STUDY_HELP, IntentDetector.detect("पढ़ाई में मदद करो"))
    }

    @Test
    fun socialUtterancesAreClassifiedAsSocial() {
        assertEquals(UserIntent.GREETING, IntentDetector.detect("good morning"))
        assertEquals(UserIntent.GREETING, IntentDetector.detect("hello"))
        assertEquals(UserIntent.GREETING, IntentDetector.detect("नमस्ते"))
        assertEquals(UserIntent.GRATITUDE, IntentDetector.detect("thanks nex"))
        assertEquals(UserIntent.GRATITUDE, IntentDetector.detect("dhanyavad"))
        assertEquals(UserIntent.GRATITUDE, IntentDetector.detect("शुक्रिया"))
        assertEquals(UserIntent.FAREWELL, IntentDetector.detect("good night"))
        assertEquals(UserIntent.FAREWELL, IntentDetector.detect("bye"))
        assertEquals(UserIntent.FAREWELL, IntentDetector.detect("alvida"))
        assertEquals(UserIntent.OFFLINE_SMALLTALK, IntentDetector.detect("how are you"))
        assertEquals(UserIntent.OFFLINE_SMALLTALK, IntentDetector.detect("kaise ho"))
        assertEquals(UserIntent.OFFLINE_SMALLTALK, IntentDetector.detect("tum kaun ho"))
    }

    @Test
    fun musicRequestsAreNotTreatedAsStudyOrChat() {
        assertEquals(UserIntent.MUSIC, IntentDetector.detect("play some music"))
        assertEquals(UserIntent.MUSIC, IntentDetector.detect("gaana sunao"))
        assertEquals(UserIntent.MUSIC, IntentDetector.detect("गाना सुनाओ"))
    }

    @Test
    fun unknownAndBlankInputFallsBackToGeneral() {
        assertEquals(UserIntent.GENERAL, IntentDetector.detect(""))
        assertEquals(UserIntent.GENERAL, IntentDetector.detect("   "))
        assertEquals(UserIntent.GENERAL, IntentDetector.detect("…"))
        assertEquals(UserIntent.GENERAL, IntentDetector.detect("xyzzy plugh"))
    }

    @Test
    fun detectionIsCaseAndPunctuationInsensitive() {
        assertEquals(
            IntentDetector.detect("what is the weather today"),
            IntentDetector.detect("WHAT IS THE WEATHER TODAY?!"),
        )
        assertEquals(
            IntentDetector.detect("hello"),
            IntentDetector.detect("  Hello,  "),
        )
    }

    @Test
    fun memoryCommandsWinOverSubjectWordsInTheSameSentence() {
        // "physics" is a science word, but the sentence is a memory command.
        assertEquals(
            UserIntent.MEMORY_SAVE,
            IntentDetector.detect("remember my favourite subject is physics"),
        )
    }

    @Test
    fun weatherFlavoursAreDetectedForTheAnimationChoice() {
        assertEquals(WeatherKind.RAIN, IntentDetector.detectWeatherKind("it is raining outside"))
        assertEquals(WeatherKind.RAIN, IntentDetector.detectWeatherKind("aaj barish ho rahi hai"))
        assertEquals(WeatherKind.SUN, IntentDetector.detectWeatherKind("is it sunny today"))
        assertEquals(WeatherKind.SUN, IntentDetector.detectWeatherKind("bahut garmi hai"))
        assertEquals(WeatherKind.CLOUD, IntentDetector.detectWeatherKind("cloudy morning"))
        assertEquals(WeatherKind.STORM, IntentDetector.detectWeatherKind("thunder and lightning tonight"))
        assertEquals(WeatherKind.STORM, IntentDetector.detectWeatherKind("तूफान आ रहा है"))
        assertEquals(WeatherKind.UNKNOWN, IntentDetector.detectWeatherKind("what is the weather"))
    }

    @Test
    fun stormBeatsRainBecauseItIsTheMoreSpecificCondition() {
        assertEquals(WeatherKind.STORM, IntentDetector.detectWeatherKind("rain and storm warning"))
    }

    @Test
    fun onlyADirectPlaceAskCountsAsALocationRequest() {
        assertTrue(IntentDetector.isDirectLocationRequest("where is the nearest hospital"))
        assertTrue(IntentDetector.isDirectLocationRequest("what is the weather here"))
        assertTrue(IntentDetector.isDirectLocationRequest("mausam yahan kaisa hai"))
        assertTrue(IntentDetector.isDirectLocationRequest("मेरे पास मौसम कैसा है"))
        assertFalse(IntentDetector.isDirectLocationRequest("what is the weather today"))
        assertFalse(IntentDetector.isDirectLocationRequest("explain gravity"))
        assertFalse(IntentDetector.isDirectLocationRequest(""))
    }

    @Test
    fun everyIntentInTheEnumIsReachableFromSomeUtterance() {
        val samples = linkedMapOf(
            UserIntent.GREETING to "hello",
            UserIntent.FAREWELL to "bye",
            UserIntent.GRATITUDE to "thanks",
            UserIntent.WEATHER to "weather",
            UserIntent.NEWS to "news",
            UserIntent.TIME_SENSITIVE_QUERY to "latest update",
            UserIntent.LOCATION_QUERY to "where is it",
            UserIntent.STUDY_HELP to "homework",
            UserIntent.MATHS to "maths",
            UserIntent.SCIENCE to "science",
            UserIntent.CODING to "code",
            UserIntent.CREATIVITY to "story",
            UserIntent.BOOK_OR_DOCUMENT to "book",
            UserIntent.MEMORY_SAVE to "remember that",
            UserIntent.MEMORY_RECALL to "do you remember",
            UserIntent.MEMORY_LIST to "what do you remember",
            UserIntent.MEMORY_DELETE to "forget that",
            UserIntent.VISION_REQUEST to "what is this",
            UserIntent.PRIVACY_HELP to "is my data safe",
            UserIntent.PERMISSION_HELP to "why do you need permission",
            UserIntent.SETTINGS_OR_VOICE_COMMAND to "stop listening",
            UserIntent.TIMER_START to "set a timer",
            UserIntent.MUSIC to "music",
            UserIntent.OFFLINE_SMALLTALK to "how are you",
            UserIntent.GENERAL to "xyzzy plugh",
        )
        assertEquals(UserIntent.entries.toSet(), samples.keys)
        samples.forEach { (expected, utterance) ->
            assertEquals("'$utterance'", expected, IntentDetector.detect(utterance))
        }
    }
}
