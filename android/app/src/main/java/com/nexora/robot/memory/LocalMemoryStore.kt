package com.nexora.robot.memory

/**
 * Bounded, consent-gated, on-device memory.
 *
 * Product rules, all of them enforced here and pinned by `LocalMemoryStoreTest`:
 *
 *  * **nothing is written before explicit consent** — [remember] refuses without it;
 *  * withdrawing consent **deletes everything**;
 *  * the store is **bounded** ([MAX_ENTRIES]); when full, the oldest entry is evicted and the
 *    eviction is reported so the UI can say so honestly;
 *  * [snapshot] exists so the user can *see* what is remembered, and [delete]/[deleteAll]
 *    exist so the user can remove it;
 *  * there is no profiling, no background collection and no upload: this store is on-device
 *    only, and no key or value ever leaves the phone from here.
 */
data class MemoryEntry(
    val key: String,
    val value: String,
    val savedAtMs: Long,
)

sealed interface MemoryWriteResult {
    /** Stored, possibly after evicting the oldest entry to stay inside the bound. */
    data class Stored(val entry: MemoryEntry, val evicted: MemoryEntry?) : MemoryWriteResult

    /** Refused because the user has not granted local-memory consent. */
    data object RefusedNoConsent : MemoryWriteResult

    /** Refused because the key or value was not usable. */
    data class Rejected(val reason: String) : MemoryWriteResult
}

class LocalMemoryStore(
    private val store: KeyValueStore,
    private val clock: () -> Long = { 0L },
) {

    companion object {
        const val CONSENT_KEY = "nex.memory.consent"
        const val CONSENT_GRANTED = "granted"
        const val ENTRY_PREFIX = "nex.memory.entry."

        /** Hard bound on how much NEX will remember. */
        const val MAX_ENTRIES = 24
        const val MAX_KEY_CHARS = 40
        const val MAX_VALUE_CHARS = 240

        private const val SEPARATOR = "\u001F"
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}]")
        private val KEY_NORMALIZER = Regex("[^\\p{L}\\p{N}._-]+")
    }

    // ------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------

    fun hasConsent(): Boolean = store.getString(CONSENT_KEY) == CONSENT_GRANTED

    /**
     * Grant or withdraw consent. Withdrawing deletes every entry immediately.
     *
     * @return the number of entries deleted while withdrawing (0 when granting).
     */
    fun setConsent(granted: Boolean): Int {
        if (granted) {
            store.putString(CONSENT_KEY, CONSENT_GRANTED)
            return 0
        }
        val removed = deleteAll()
        store.remove(CONSENT_KEY)
        return removed
    }

    // ------------------------------------------------------------------
    // Read / write
    // ------------------------------------------------------------------

    fun remember(rawKey: String, rawValue: String): MemoryWriteResult {
        if (!hasConsent()) return MemoryWriteResult.RefusedNoConsent

        val key = normalizeKey(rawKey)
        if (key.isBlank()) return MemoryWriteResult.Rejected("blank-key")
        if (key.length > MAX_KEY_CHARS) return MemoryWriteResult.Rejected("key-too-long")

        val value = normalizeValue(rawValue)
        if (value.isBlank()) return MemoryWriteResult.Rejected("blank-value")

        val evicted = if (store.getString(entryKey(key)) == null && entryCount() >= MAX_ENTRIES) {
            snapshot().lastOrNull()?.also { delete(it.key) }
        } else {
            null
        }

        val entry = MemoryEntry(key = key, value = value, savedAtMs = clock())
        store.putString(entryKey(key), encode(entry))
        return MemoryWriteResult.Stored(entry = entry, evicted = evicted)
    }

    fun recall(rawKey: String): MemoryEntry? {
        val key = normalizeKey(rawKey)
        if (key.isBlank()) return null
        return decode(key, store.getString(entryKey(key)))
    }

    /** Everything NEX remembers, newest first. Shown verbatim to the user. */
    fun snapshot(): List<MemoryEntry> = store.keys()
        .filter { it.startsWith(ENTRY_PREFIX) }
        .mapNotNull { storedKey -> decode(storedKey.removePrefix(ENTRY_PREFIX), store.getString(storedKey)) }
        .sortedWith(compareByDescending<MemoryEntry> { it.savedAtMs }.thenBy { it.key })

    fun entryCount(): Int = store.keys().count { it.startsWith(ENTRY_PREFIX) }

    fun delete(rawKey: String): Boolean {
        val key = normalizeKey(rawKey)
        if (key.isBlank()) return false
        val existed = store.getString(entryKey(key)) != null
        store.remove(entryKey(key))
        return existed
    }

    /** Remove everything. Also used when consent is withdrawn. */
    fun deleteAll(): Int {
        val keys = store.keys().filter { it.startsWith(ENTRY_PREFIX) }
        keys.forEach { store.remove(it) }
        return keys.size
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun entryKey(key: String): String = ENTRY_PREFIX + key

    private fun normalizeKey(rawKey: String): String =
        KEY_NORMALIZER.replace(rawKey.trim().lowercase(), "-").trim('-').take(MAX_KEY_CHARS + 8)

    private fun normalizeValue(rawValue: String): String {
        val cleaned = CONTROL_CHARS.replace(rawValue, " ").replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length > MAX_VALUE_CHARS) cleaned.take(MAX_VALUE_CHARS) else cleaned
    }

    private fun encode(entry: MemoryEntry): String = "${entry.value}$SEPARATOR${entry.savedAtMs}"

    private fun decode(key: String, stored: String?): MemoryEntry? {
        if (stored == null) return null
        val separatorIndex = stored.lastIndexOf(SEPARATOR)
        if (separatorIndex <= 0) return null
        val value = stored.substring(0, separatorIndex)
        val savedAtMs = stored.substring(separatorIndex + 1).toLongOrNull() ?: return null
        return MemoryEntry(key = key, value = value, savedAtMs = savedAtMs)
    }
}
