package com.nexora.robot.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local memory: consent first, bounded, visible, deletable.
 *
 * The store is tested against [InMemoryKeyValueStore] — the same interface the app implements
 * with `SharedPreferences` — so the rules hold regardless of where the bytes end up.
 */
class LocalMemoryStoreTest {

    private var now = 1_000L

    private fun newStore(): Pair<LocalMemoryStore, InMemoryKeyValueStore> {
        val backing = InMemoryKeyValueStore()
        val store = LocalMemoryStore(store = backing, clock = { now })
        return store to backing
    }

    private fun consentedStore(): LocalMemoryStore {
        val (store, _) = newStore()
        assertEquals(0, store.setConsent(true))
        assertTrue(store.hasConsent())
        return store
    }

    @Test
    fun theContractConstantsAreWhatTheDocsSay() {
        assertEquals("nex.memory.consent", LocalMemoryStore.CONSENT_KEY)
        assertEquals("granted", LocalMemoryStore.CONSENT_GRANTED)
        assertEquals("nex.memory.entry.", LocalMemoryStore.ENTRY_PREFIX)
        assertEquals(24, LocalMemoryStore.MAX_ENTRIES)
        assertEquals(40, LocalMemoryStore.MAX_KEY_CHARS)
        assertEquals(240, LocalMemoryStore.MAX_VALUE_CHARS)
    }

    @Test
    fun nothingIsStoredWithoutConsent() {
        val (store, backing) = newStore()
        assertFalse(store.hasConsent())
        val result = store.remember("favourite subject", "Physics")
        assertTrue(result is MemoryWriteResult.RefusedNoConsent)
        assertEquals(0, store.entryCount())
        assertTrue(store.snapshot().isEmpty())
        assertNull(store.recall("favourite subject"))
        assertTrue("no consent marker may be written by a refused save", backing.snapshot().isEmpty())
    }

    @Test
    fun grantingConsentWritesOnlyTheConsentMarker() {
        val (store, backing) = newStore()
        assertEquals(0, store.setConsent(true))
        assertTrue(store.hasConsent())
        assertEquals(mapOf(LocalMemoryStore.CONSENT_KEY to LocalMemoryStore.CONSENT_GRANTED), backing.snapshot())
    }

    @Test
    fun aSavedEntryCanBeRecalled() {
        val store = consentedStore()
        now = 5_000L
        val result = store.remember("Favourite Subject", "Physics")
        assertTrue(result is MemoryWriteResult.Stored)
        val stored = (result as MemoryWriteResult.Stored).entry
        assertEquals("favourite-subject", stored.key)
        assertEquals("Physics", stored.value)
        assertEquals(5_000L, stored.savedAtMs)
        assertNull(result.evicted)

        val recalled = store.recall("favourite subject")
        assertEquals(stored, recalled)
        assertEquals(1, store.entryCount())
    }

    @Test
    fun keysAreNormalisedSoCasingAndPunctuationDoNotCreateDuplicates() {
        val store = consentedStore()
        store.remember("Best Friend", "Asha")
        now += 1L
        store.remember("best   friend!", "Asha Rani")
        assertEquals(1, store.entryCount())
        assertEquals("Asha Rani", store.recall("BEST FRIEND")?.value)
    }

    @Test
    fun devanagariKeysAndValuesSurvive() {
        val store = consentedStore()
        store.remember("विषय", "गणित")
        assertEquals("गणित", store.recall("विषय")?.value)
        assertEquals("विषय", store.snapshot().first().key)
    }

    @Test
    fun blankKeysAndBlankValuesAreRejected() {
        val store = consentedStore()
        assertEquals(MemoryWriteResult.Rejected("blank-key"), store.remember("", "something"))
        assertEquals(MemoryWriteResult.Rejected("blank-key"), store.remember("   ", "something"))
        assertEquals(MemoryWriteResult.Rejected("blank-key"), store.remember("!!! ???", "something"))
        assertEquals(MemoryWriteResult.Rejected("blank-value"), store.remember("key", ""))
        assertEquals(MemoryWriteResult.Rejected("blank-value"), store.remember("key", "   "))
        assertEquals(0, store.entryCount())
    }

    @Test
    fun overlongKeysAreRejectedAndExactLimitKeysAreAccepted() {
        val store = consentedStore()
        val exact = "k".repeat(LocalMemoryStore.MAX_KEY_CHARS)
        assertTrue(store.remember(exact, "value") is MemoryWriteResult.Stored)
        val tooLong = "k".repeat(LocalMemoryStore.MAX_KEY_CHARS + 5)
        assertEquals(MemoryWriteResult.Rejected("key-too-long"), store.remember(tooLong, "value"))
        assertEquals(1, store.entryCount())
    }

    @Test
    fun overlongValuesAreTruncatedNotRejected() {
        val store = consentedStore()
        val long = "x".repeat(LocalMemoryStore.MAX_VALUE_CHARS + 60)
        val result = store.remember("long note", long)
        assertTrue(result is MemoryWriteResult.Stored)
        assertEquals(
            LocalMemoryStore.MAX_VALUE_CHARS,
            (result as MemoryWriteResult.Stored).entry.value.length,
        )
        assertEquals(LocalMemoryStore.MAX_VALUE_CHARS, store.recall("long note")?.value?.length)
    }

    @Test
    fun controlCharactersAndTheInternalSeparatorAreStrippedFromValues() {
        val store = consentedStore()
        val result = store.remember("tricky", "a\u001Fb\u0000c   d")
        assertTrue(result is MemoryWriteResult.Stored)
        val value = (result as MemoryWriteResult.Stored).entry.value
        assertFalse(value.contains('\u001F'))
        assertFalse(value.contains('\u0000'))
        assertEquals("a b c d", value)
        // The entry still decodes correctly, because the separator can never appear in a value.
        assertEquals(value, store.recall("tricky")?.value)
    }

    @Test
    fun whitespaceInsideValuesIsCollapsed() {
        val store = consentedStore()
        store.remember("note", "  my   school   is   near   the   park  ")
        assertEquals("my school is near the park", store.recall("note")?.value)
    }

    @Test
    fun theStoreIsBoundedAndEvictsTheOldestEntry() {
        val store = consentedStore()
        repeat(LocalMemoryStore.MAX_ENTRIES) { index ->
            now += 1_000L
            val result = store.remember("key $index", "value $index")
            assertTrue(result is MemoryWriteResult.Stored)
            assertNull("entry $index should not evict anything", (result as MemoryWriteResult.Stored).evicted)
        }
        assertEquals(LocalMemoryStore.MAX_ENTRIES, store.entryCount())

        now += 1_000L
        val overflow = store.remember("one too many", "the newest value")
        assertTrue(overflow is MemoryWriteResult.Stored)
        val evicted = (overflow as MemoryWriteResult.Stored).evicted
        assertTrue("the oldest entry must be evicted", evicted != null)
        // The oldest entry is "key 0" because the clock moved forward with every save.
        assertEquals("key-0", evicted?.key)
        assertEquals(LocalMemoryStore.MAX_ENTRIES, store.entryCount())
        assertNull(store.recall("key 0"))
        assertEquals("the newest value", store.recall("one too many")?.value)
    }

    @Test
    fun updatingAnExistingKeyNeverEvictsAnything() {
        val store = consentedStore()
        repeat(LocalMemoryStore.MAX_ENTRIES) { index ->
            now += 1L
            store.remember("key $index", "value $index")
        }
        assertEquals(LocalMemoryStore.MAX_ENTRIES, store.entryCount())

        now += 1L
        val update = store.remember("key 0", "a newer value")
        assertTrue(update is MemoryWriteResult.Stored)
        assertNull((update as MemoryWriteResult.Stored).evicted)
        assertEquals(LocalMemoryStore.MAX_ENTRIES, store.entryCount())
        assertEquals("a newer value", store.recall("key 0")?.value)
    }

    @Test
    fun theSnapshotIsNewestFirstAndShowsExactlyWhatIsStored() {
        val store = consentedStore()
        now = 10_000L
        store.remember("first", "oldest value")
        now = 20_000L
        store.remember("second", "middle value")
        now = 30_000L
        store.remember("third", "newest value")

        val snapshot = store.snapshot()
        assertEquals(listOf("third", "second", "first"), snapshot.map { it.key })
        assertEquals(listOf(30_000L, 20_000L, 10_000L), snapshot.map { it.savedAtMs })
        assertEquals("newest value", snapshot.first().value)
        assertEquals(3, store.entryCount())
    }

    @Test
    fun aSingleEntryCanBeDeleted() {
        val store = consentedStore()
        store.remember("keep", "this stays")
        store.remember("drop", "this goes")
        assertTrue(store.delete("drop"))
        assertFalse("deleting twice must report the truth", store.delete("drop"))
        assertFalse(store.delete("never existed"))
        assertEquals(1, store.entryCount())
        assertEquals("this stays", store.recall("keep")?.value)
        assertNull(store.recall("drop"))
    }

    @Test
    fun deleteAllRemovesEveryEntryButKeepsConsent() {
        val store = consentedStore()
        store.remember("one", "a")
        store.remember("two", "b")
        store.remember("three", "c")
        assertEquals(3, store.deleteAll())
        assertEquals(0, store.entryCount())
        assertTrue(store.snapshot().isEmpty())
        assertTrue("deleting entries must not withdraw consent", store.hasConsent())
        assertEquals(0, store.deleteAll())
    }

    @Test
    fun withdrawingConsentDeletesEverythingAndReportsHowMuch() {
        val store = consentedStore()
        store.remember("one", "a")
        store.remember("two", "b")
        assertEquals(2, store.setConsent(false))
        assertFalse(store.hasConsent())
        assertEquals(0, store.entryCount())
        assertTrue(store.snapshot().isEmpty())
        // And nothing can be written again until consent is granted once more.
        assertTrue(store.remember("three", "c") is MemoryWriteResult.RefusedNoConsent)
        assertEquals(0, store.entryCount())
    }

    @Test
    fun aCorruptStoredValueIsIgnoredInsteadOfCrashing() {
        val (store, backing) = newStore()
        store.setConsent(true)
        backing.putString(LocalMemoryStore.ENTRY_PREFIX + "broken", "no-separator-here")
        backing.putString(LocalMemoryStore.ENTRY_PREFIX + "notanumber", "value\u001Fnot-a-timestamp")
        backing.putString("some.other.library.key", "irrelevant")

        assertNull(store.recall("broken"))
        assertNull(store.recall("notanumber"))
        // Nothing undecodable is ever shown to the user as "something NEX remembers".
        assertTrue(store.snapshot().isEmpty())
        // A real save still works afterwards, and the snapshot shows only the good entry.
        assertTrue(store.remember("working", "yes") is MemoryWriteResult.Stored)
        assertEquals(listOf("working"), store.snapshot().map { it.key })
        assertEquals("yes", store.recall("working")?.value)
    }

    @Test
    fun onlyNexNamespacedKeysAreEverReadBack() {
        val (store, backing) = newStore()
        store.setConsent(true)
        backing.putString("analytics.device.id", "should-never-appear")
        backing.putString("other.pref", "x")
        store.remember("mine", "y")
        val keys = store.snapshot().map { it.key }
        assertEquals(listOf("mine"), keys)
        assertFalse(keys.any { it.contains("analytics") })
    }

    @Test
    fun recallOfAnUnknownKeyIsNull() {
        val store = consentedStore()
        assertNull(store.recall("nothing here"))
        assertNull(store.recall(""))
        assertNull(store.recall("   "))
    }
}
