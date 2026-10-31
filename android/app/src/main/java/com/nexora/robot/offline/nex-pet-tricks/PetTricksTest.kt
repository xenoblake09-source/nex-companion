package com.nexora.robot.offline

import com.nexora.robot.animation.AnimationCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The party-trick table and its matching rules.
 *
 * The rules matter as much as the content: a pet app that shouts "Woof!" whenever the word
 * "dog" appears in ordinary conversation would be unbearable, and a greeting parser that
 * fires on the conjunction "or" would be broken. These tests pin both directions.
 */
class PetTricksTest {

    @Test
    fun cataloguesHoldExactlyTwentyOfEach() {
        assertEquals("animal catalogue size", 20, PetTricks.ANIMALS.size)
        assertEquals("greeting catalogue size", 20, PetTricks.GREETINGS.size)
    }

    @Test
    fun namesAndTagsAreUnique() {
        assertEquals(
            "duplicate animal name",
            PetTricks.ANIMALS.size,
            PetTricks.ANIMALS.map { it.name }.distinct().size,
        )
        assertEquals(
            "duplicate greeting tag",
            PetTricks.GREETINGS.size,
            PetTricks.GREETINGS.map { it.tag }.distinct().size,
        )
    }

    @Test
    fun everyEntryCarriesSpeechAndScreenContent() {
        PetTricks.ANIMALS.forEach { animal ->
            assertTrue("${animal.name} has a blank sound", animal.sound.isNotBlank())
            assertTrue("${animal.name} has a blank emoji", animal.emoji.isNotBlank())
            assertTrue("${animal.name} has no aliases", animal.aliases.isNotEmpty())
            assertTrue(
                "${animal.name} puts an emoji in the spoken sound",
                !animal.sound.any { it.code > 0x2100 },
            )
        }
        PetTricks.GREETINGS.forEach { greeting ->
            assertTrue("${greeting.tag} has a blank text", greeting.text.isNotBlank())
            assertTrue("${greeting.tag} has a blank flag", greeting.flag.isNotBlank())
            assertTrue("${greeting.tag} has no aliases", greeting.aliases.isNotEmpty())
        }
    }

    @Test
    fun animalAsksAreParsed() {
        val trick = PetTricks.parse("Sound like a dog!")
        assertTrue("expected an animal trick, got $trick", trick is PetTrick.AnimalVoice)
        trick as PetTrick.AnimalVoice
        assertEquals("Woof! Woof!", trick.animal.sound)
        assertEquals(AnimationCue.PLAYFUL, trick.cue)

        val cow = PetTricks.parse("what sound does a cow make")
        assertEquals("Moo!", (cow as PetTrick.AnimalVoice).animal.sound)

        val hindi = PetTricks.parse("कुत्ते की आवाज़ बोलो")
        assertEquals("Woof! Woof!", (hindi as PetTrick.AnimalVoice).animal.sound)
    }

    @Test
    fun mentioningAnAnimalWithoutAskingForItsSoundIsNotATrick() {
        assertNull(PetTricks.parse("I have a dog at home"))
        assertNull(PetTricks.parse("cows eat grass"))
        assertNull(PetTricks.parse("the category is birds"))
    }

    @Test
    fun helloInALanguageIsAGreetingTrick() {
        val trick = PetTricks.parse("hello in Tamil")
        assertTrue("expected a greeting trick, got $trick", trick is PetTrick.GreetingIn)
        trick as PetTrick.GreetingIn
        assertEquals("ta", trick.greeting.tag)
        assertEquals("வணக்கம்!", trick.greeting.text)
        assertEquals(AnimationCue.FRIENDLY_WAVE, trick.cue)

        val french = PetTricks.parse("say hello in french")
        assertEquals("Bonjour!", (french as PetTrick.GreetingIn).greeting.text)
    }

    @Test
    fun hindiPhrasingIsUnderstood() {
        val trick = PetTricks.parse("तमिल में नमस्ते बोलो")
        assertTrue("expected a greeting trick, got $trick", trick is PetTrick.GreetingIn)
        assertEquals("ta", (trick as PetTrick.GreetingIn).greeting.tag)
    }

    @Test
    fun plainGreetingsStayOrdinaryConversation() {
        assertNull(PetTricks.parse("hello"))
        assertNull(PetTricks.parse("hey NEX"))
        assertNull(PetTricks.parse("good morning"))
        assertNull(PetTricks.parse(""))
    }

    @Test
    fun theConjunctionOrNeverTriggersOdia() {
        assertNull(PetTricks.parse("hello or goodbye"))
        assertNull(PetTricks.parse("they say it rains or snows"))
    }

    @Test
    fun greetingsWinOverAnimalsWhenBothCouldMatch() {
        // "say hello like a chicken" asks for a chicken only because no language matched;
        // with a language present the greeting must win.
        val trick = PetTricks.parse("say hello like a chicken in spanish")
        assertTrue("expected a greeting trick, got $trick", trick is PetTrick.GreetingIn)
        assertEquals("es", (trick as PetTrick.GreetingIn).greeting.tag)
    }

    @Test
    fun unsupportedLineAdmitsTheLimitInBothUiLanguages() {
        val bhojpuri = PetTricks.GREETINGS.first { it.tag == "bho" }
        val english = bhojpuri.unsupportedLine("en")
        val hindi = bhojpuri.unsupportedLine("hi")
        assertTrue("english line names the language", english.contains("Bhojpuri"))
        assertTrue("english line never claims speech", english.contains("cannot speak"))
        assertTrue("hindi line names the language", hindi.contains("भोजपुरी"))
        assertTrue("gloss carries the flag", bhojpuri.gloss.contains(bhojpuri.flag))
    }
}
