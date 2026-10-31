package com.nexora.robot.memory

import android.content.Context
import android.content.SharedPreferences

/**
 * The only place NEX's local memory is written: `SharedPreferences`, private to this app.
 *
 * Rules this class exists to enforce:
 *  * no network, no analytics, no cloud backup — the manifest sets `allowBackup="false"` and
 *    excludes shared preferences from backup and device transfer;
 *  * [keys] only ever exposes NEX's own namespaced keys, so a stray value from another library
 *    can never be read back as "something NEX remembers";
 *  * every write goes through `apply()` (async, off the main thread's critical path) and the
 *    bounds in [LocalMemoryStore] decide what is accepted.
 *
 * This class touches Android APIs, so it is deliberately thin: all decision logic lives in the
 * pure-Kotlin [LocalMemoryStore], which is unit tested.
 */
class PreferencesKeyValueStore(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys.filterTo(mutableSetOf()) { isNexKey(it) }

    private fun isNexKey(key: String): Boolean =
        key == LocalMemoryStore.CONSENT_KEY || key.startsWith(LocalMemoryStore.ENTRY_PREFIX)

    companion object {
        const val FILE_NAME = "nex_local_memory"
    }
}
