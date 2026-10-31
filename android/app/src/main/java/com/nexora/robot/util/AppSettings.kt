package com.nexora.robot.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.nexora.robot.BuildConfig
import com.nexora.robot.memory.LocalMemoryStore
import com.nexora.robot.net.BackendUrlPolicy

/**
 * Small, non-identifying app preferences.
 *
 * Stored in private `SharedPreferences` on this device only: no analytics id, no advertising
 * id, no device fingerprint, and nothing here is ever uploaded.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * The backend base URL. Defaults to the build-config value and can be corrected in-app,
     * but only ever to a clean https address (see [BackendUrlPolicy]).
     */
    var backendBaseUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, BuildConfig.BACKEND_BASE_URL).orEmpty()
        set(value) {
            val check = BackendUrlPolicy.check(value)
            if (check is BackendUrlPolicy.UrlCheck.Valid) {
                prefs.edit().putString(KEY_BACKEND_URL, check.normalizedUrl).apply()
            }
        }

    var voiceModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_MODE, value).apply()

    var hindiMode: Boolean
        get() = prefs.getBoolean(KEY_HINDI_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_HINDI_MODE, value).apply()

    /** In-app override; `null` means "follow the system animation setting". */
    var reducedMotionOverride: Boolean?
        get() = if (prefs.contains(KEY_REDUCED_MOTION)) prefs.getBoolean(KEY_REDUCED_MOTION, false) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_REDUCED_MOTION).apply()
            else prefs.edit().putBoolean(KEY_REDUCED_MOTION, value).apply()
        }

    /** Explicit local-memory consent. Nothing is written to [LocalMemoryStore] without it. */
    var memoryConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_CONSENT, value).apply()

    var cameraPermissionExplained: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_EXPLAINED, false)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_EXPLAINED, value).apply()

    /** Resolved language for TTS and the backend: "hi" when Hindi mode is on, else "en". */
    fun language(): String = if (hindiMode) "hi" else "en"

    /**
     * Reduced motion is honoured when the user asks for it in NEX **or** when the system
     * "Remove animations" setting is on (animator duration scale of 0).
     */
    fun reducedMotionEnabled(context: Context): Boolean {
        reducedMotionOverride?.let { return it }
        return systemAnimationsDisabled(context)
    }

    private fun systemAnimationsDisabled(context: Context): Boolean = try {
        val scale = Settings.Global.getFloat(
            context.applicationContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    } catch (error: Exception) {
        NexLog.w("could not read animator scale, assuming animations allowed", error)
        false
    }

    companion object {
        const val FILE_NAME = "nex.settings"
        const val KEY_BACKEND_URL = "backend_base_url"
        const val KEY_VOICE_MODE = "voice_mode_enabled"
        const val KEY_HINDI_MODE = "hindi_mode"
        const val KEY_REDUCED_MOTION = "reduced_motion_override"
        const val KEY_MEMORY_CONSENT = "memory_consent"
        const val KEY_CAMERA_EXPLAINED = "camera_permission_explained"
    }
}
