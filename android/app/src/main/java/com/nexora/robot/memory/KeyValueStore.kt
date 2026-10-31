package com.nexora.robot.memory

/**
 * Minimal key/value abstraction so local-memory logic is pure Kotlin and unit testable.
 *
 * The Android implementation ([PreferencesKeyValueStore]) wraps `SharedPreferences`;
 * tests use [InMemoryKeyValueStore]. Nothing here ever touches the network.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** Deterministic in-memory store used by unit tests. */
class InMemoryKeyValueStore : KeyValueStore {

    private val values = LinkedHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun keys(): Set<String> = values.keys.toSet()

    /** Test helper: everything currently stored. */
    fun snapshot(): Map<String, String> = values.toMap()
}
