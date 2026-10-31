package com.nexora.robot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexora.robot.R
import com.nexora.robot.animation.AnimationCatalog
import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.databinding.ActivityMainBinding
import com.nexora.robot.memory.LocalMemoryStore
import com.nexora.robot.memory.PreferencesKeyValueStore
import com.nexora.robot.net.HealthResult
import com.nexora.robot.net.NexApiClient
import com.nexora.robot.net.NexErrorCodes
import com.nexora.robot.net.SourceLink
import com.nexora.robot.util.AppSettings
import com.nexora.robot.util.NexLog
import com.nexora.robot.vision.CameraSessionController
import com.nexora.robot.vision.RemoteVisionAnalyzer
import com.nexora.robot.vision.VisionCoordinator
import com.nexora.robot.vision.VisionOutcome
import com.nexora.robot.vision.VisionUiState
import com.nexora.robot.voice.Speaker
import com.nexora.robot.voice.SpeechController
import com.nexora.robot.voice.TranscriptLine
import com.nexora.robot.voice.UiSignal
import com.nexora.robot.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The only screen NEX has, and the only place Android widgets are touched.
 *
 * Division of responsibility:
 *  * [SpeechController] owns the conversation state machine, the microphone, TTS, the backend
 *    turn and every spoken line. It never sees a `View`;
 *  * this activity owns permission launchers, the camera preview, the animation stage and text.
 *    It never decides conversation state — it renders [VoiceState] and forwards user actions;
 *  * [VisionCoordinator] owns one explicit camera turn; the preview is visible exactly while
 *    the camera hardware is bound, which is the honest in-use indicator.
 *
 * NEX never starts the microphone on its own: launching the app does not resume listening even
 * if voice mode was on before, and the wake phrase works only while this screen is in front.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    private lateinit var memory: LocalMemoryStore
    private lateinit var api: NexApiClient
    private lateinit var controller: SpeechController
    private lateinit var vision: VisionCoordinator

    private var previewJob: Job? = null
    private var noticeJob: Job? = null
    private var timerJob: Job? = null
    private var pendingVoiceStart = false
    private var pendingVisionPrompt: String? = null
    private var updatingSwitches = false

    // ------------------------------------------------------------------
    // Runtime permissions: requested only after the user has been told why
    // ------------------------------------------------------------------

    private val microphoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            controller.onMicrophonePermissionResult(granted)
            renderPermissionStatus()
            if (granted) {
                if (pendingVoiceStart) controller.startVoiceMode()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showOpenSettingsDialog(getString(R.string.permission_microphone_denied))
            }
            pendingVoiceStart = false
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            controller.onCameraPermissionResult(granted)
            if (granted) {
                pendingVisionPrompt?.let { runVisionTurn(it) }
            } else {
                controller.speakExternal(
                    getString(R.string.permission_camera_denied),
                    settings.language(),
                    AnimationCue.SAFE_WARNING,
                )
                if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    showOpenSettingsDialog(getString(R.string.permission_camera_denied))
                }
            }
            pendingVisionPrompt = null
        }

    /** Coarse location only, and only for a direct weather or place question. */
    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            controller.speakExternal(
                getString(if (granted) R.string.location_granted else R.string.location_denied),
                settings.language(),
                AnimationCue.PRIVACY_SHIELD,
            )
        }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        memory = LocalMemoryStore(
            store = PreferencesKeyValueStore(this),
            clock = { System.currentTimeMillis() },
        )
        api = buildApiClient()
        controller = SpeechController(
            context = this,
            settings = settings,
            api = api,
            memory = memory,
            scope = lifecycleScope,
            clock = { SystemClock.elapsedRealtime() },
            coarseLocationHint = { coarseLocationHint() },
        )
        vision = VisionCoordinator(
            camera = CameraSessionController(this, this, binding.cameraPreview),
            analyzer = RemoteVisionAnalyzer(api),
            cameraPermissionGranted = { hasPermission(Manifest.permission.CAMERA) },
            onUiState = { NexLog.i("vision ui state=$it") },
        )

        applyReducedMotion()
        bindControls()
        bindSwitches()
        observeController()

        renderPermissionStatus()
        renderMemoryPanel()
        binding.transcriptText.setText(R.string.transcript_empty)
        binding.cameraStateText.setText(R.string.camera_off)
        refreshBackendStatus()

        if (settings.voiceModeEnabled) {
            // Honest reminder, never an automatic restart of the microphone.
            showNotice(getString(R.string.voice_resume_hint))
        }
        NexLog.i("activity created voice_persisted=${settings.voiceModeEnabled}")
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
        renderPermissionStatus()
    }

    override fun onStop() {
        super.onStop()
        // Background: voice stops and the camera is switched off, before any state can leak.
        controller.onBackground()
        vision.release()
        binding.cameraPreview.isVisible = false
    }

    override fun onDestroy() {
        previewJob?.cancel()
        noticeJob?.cancel()
        timerJob?.cancel()
        controller.release()
        vision.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------

    /**
     * Builds the HTTPS client. A malformed stored URL must never crash the app: NEX falls back
     * to the build default and says on screen that the server is not configured.
     */
    private fun buildApiClient(): NexApiClient {
        val configured = settings.backendBaseUrl
        return try {
            NexApiClient(configured)
        } catch (error: IllegalArgumentException) {
            NexLog.w("stored backend url rejected; using the build default", error)
            NexApiClient(BACKEND_FALLBACK_URL)
        }
    }

    private fun bindControls() {
        binding.voiceToggleButton.setOnClickListener { onVoiceToggleClicked() }
        binding.visionButton.setOnClickListener { startVisionTurn(getString(R.string.vision_button)) }
        binding.galleryButton.setOnClickListener { showAnimationGallery() }
        binding.memoryToggleButton.setOnClickListener { toggleMemoryPanel() }
        binding.clearMemoryButton.setOnClickListener { deleteAllMemory() }
        binding.checkServerButton.setOnClickListener { refreshBackendStatus() }
        binding.animationView.tooltipText = getString(R.string.animation_long_press_hint)
        binding.voiceToggleButton.tooltipText = getString(R.string.voice_hint)
        binding.animationView.setOnLongClickListener {
            previewAnimation(nextPreviewId())
            true
        }
    }

    private fun bindSwitches() {
        updatingSwitches = true
        binding.hindiSwitch.isChecked = settings.hindiMode
        binding.reduceMotionSwitch.isChecked = settings.reducedMotionEnabled(this)
        binding.memoryConsentSwitch.isChecked = memory.hasConsent()
        updatingSwitches = false

        binding.hindiSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            settings.hindiMode = checked
            val language = settings.language()
            controller.showTransientCue(AnimationCue.LANGUAGE_SWITCH, LANGUAGE_SWITCH_CUE_MS)
            controller.speakExternal(
                if (checked) "अब हिंदी में बात होगी।" else "I will speak English now.",
                language,
            )
        }

        binding.reduceMotionSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            settings.reducedMotionOverride = checked
            applyReducedMotion()
        }

        binding.memoryConsentSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            onMemoryConsentChanged(checked)
        }
    }

    // ------------------------------------------------------------------
    // Voice
    // ------------------------------------------------------------------

    private fun onVoiceToggleClicked() {
        if (controller.state.value.voiceModeEnabled) {
            settings.voiceModeEnabled = false
            controller.stopVoiceMode()
            return
        }
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            settings.voiceModeEnabled = true
            controller.startVoiceMode()
            return
        }
        // Explicit consent first: the microphone is only ever opened after this dialog.
        pendingVoiceStart = true
        explainThenRequest(getString(R.string.permission_microphone_rationale)) {
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ------------------------------------------------------------------
    // Vision: one explicit question, one frame, never stored
    // ------------------------------------------------------------------

    private fun startVisionTurn(prompt: String) {
        if (hasPermission(Manifest.permission.CAMERA)) {
            runVisionTurn(prompt)
            return
        }
        pendingVisionPrompt = prompt
        val firstTime = !settings.cameraPermissionExplained
        settings.cameraPermissionExplained = true
        if (firstTime) {
            explainThenRequest(getString(R.string.permission_camera_rationale)) {
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun runVisionTurn(prompt: String) {
        binding.animationView.play(AnimationCue.VISION_ANALYZING)
        renderAnimationName()
        lifecycleScope.launch {
            val outcome = vision.answerQuestion(prompt, settings.language())
            when (outcome) {
                is VisionOutcome.Described -> {
                    controller.speakExternal(outcome.text, settings.language(), AnimationCue.ANSWER_READY)
                    renderVisionSources(outcome)
                }
                is VisionOutcome.Refused -> {
                    controller.speakExternal(outcome.message, settings.language(), AnimationCue.CONFUSED)
                }
            }
        }
    }

    private fun renderVisionOutcomeState(state: VisionUiState) {
        // The live viewfinder is the strongest honest indicator: it is on screen exactly while
        // the camera hardware is bound, and gone the moment the single frame is captured.
        val hardwareOn = state == VisionUiState.CAMERA_ON
        binding.cameraPreview.isVisible = hardwareOn
        binding.animationView.isVisible = !hardwareOn

        val indicatorVisible = hardwareOn ||
            state == VisionUiState.STARTING_CAMERA ||
            state == VisionUiState.ANALYZING
        binding.cameraStateText.isVisible = indicatorVisible
        binding.cameraStateText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (hardwareOn) R.drawable.ic_camera_dot else 0,
            0,
            0,
            0,
        )
        binding.cameraStateText.setText(
            when (state) {
                VisionUiState.STARTING_CAMERA -> R.string.camera_starting
                VisionUiState.ANALYZING -> R.string.camera_analyzing
                VisionUiState.FAILED, VisionUiState.PERMISSION_NEEDED -> R.string.camera_off
                else -> R.string.camera_in_use
            },
        )
        if (!hardwareOn) applyCue(controller.animationCue.value)
    }

    private fun renderVisionSources(outcome: VisionOutcome.Described) {
        val builder = StringBuilder()
        if (outcome.sources.isNotEmpty()) {
            builder.append(getString(R.string.sources_header, outcome.sources.size))
            outcome.sources.forEach {
                builder.append('\n').append(getString(R.string.source_line, it.title, it.url))
            }
            builder.append('\n')
        }
        // Always state the storage truth, even when there were no sources.
        if (!outcome.imageStored) builder.append(getString(R.string.vision_not_stored))
        binding.sourcesText.text = builder.toString()
        binding.sourcesText.isVisible = builder.isNotEmpty()
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun observeController() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { controller.state.collect { renderState(it) } }
                launch { controller.animationCue.collect { applyCue(it) } }
                launch { controller.transcript.collect { renderTranscript(it) } }
                launch { controller.sources.collect { renderSources(it) } }
                launch { controller.signals.collect { handleSignal(it) } }
                launch { vision.state.collect { renderVisionOutcomeState(it) } }
            }
        }
    }

    private fun renderState(voice: VoiceState) {
        binding.statusText.text = voice.statusMessage
        binding.voiceToggleButton.setText(
            if (voice.voiceModeEnabled) R.string.voice_stop else R.string.voice_start,
        )
        val hintVisible = voice.isRecovering || voice.isBlocked || voice.isError
        binding.recoveryText.isVisible = hintVisible
        if (hintVisible) {
            binding.recoveryText.setText(
                when {
                    voice.isRecovering -> R.string.recovery_hint
                    voice.isBlocked -> R.string.blocked_hint
                    else -> R.string.error_hint
                },
            )
        }
        renderPermissionStatus()
    }

    private fun applyCue(cue: AnimationCue) {
        // A gallery preview or a long-press preview owns the stage until it expires.
        if (previewJob?.isActive == true) return
        binding.animationView.play(cue)
        renderAnimationName()
    }

    private fun renderAnimationName() {
        val spec = binding.animationView.currentSpec
        binding.animationNameText.text =
            getString(R.string.animation_name_format, spec.id, spec.name)
    }

    private fun renderPermissionStatus() {
        val microphoneAllowed = hasPermission(Manifest.permission.RECORD_AUDIO)
        binding.permissionStatusText.setText(
            if (microphoneAllowed) R.string.permission_status_granted
            else R.string.permission_status_denied,
        )
        binding.permissionStatusText.setTextColor(
            ContextCompat.getColor(this, if (microphoneAllowed) R.color.nex_ok else R.color.nex_text_dim),
        )
    }

    private fun renderTranscript(lines: List<TranscriptLine>) {
        if (lines.isEmpty()) {
            binding.transcriptText.setText(R.string.transcript_empty)
            return
        }
        val builder = SpannableStringBuilder()
        lines.forEach { line ->
            val (text, color) = when (line.speaker) {
                Speaker.USER -> getString(R.string.transcript_you, line.text) to R.color.nex_primary
                Speaker.NEX -> {
                    val template = if (line.grounded) R.string.transcript_grounded else R.string.transcript_nex
                    getString(template, line.text) to R.color.nex_text
                }
                Speaker.SYSTEM -> getString(R.string.transcript_system, line.text) to R.color.nex_privacy
            }
            val start = builder.length
            builder.append(text).append('\n')
            builder.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, color)),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.transcriptText.text = builder.toString().trimEnd()
        binding.rootScroll.post { binding.rootScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderSources(sources: List<SourceLink>) {
        if (sources.isEmpty()) {
            binding.sourcesText.isVisible = false
            return
        }
        val builder = StringBuilder(getString(R.string.sources_header, sources.size))
        sources.forEach {
            builder.append('\n').append(getString(R.string.source_line, it.title, it.url))
        }
        binding.sourcesText.text = builder.toString()
        binding.sourcesText.isVisible = true
    }

    private fun refreshBackendStatus() {
        binding.backendStatusText.setText(R.string.backend_checking)
        lifecycleScope.launch {
            val health = api.health()
            binding.backendStatusText.text = backendStatusText(health)
            if (health is HealthResult.Unreachable && health.code != NexErrorCodes.NOT_CONFIGURED) {
                // Say what still works, instead of leaving the user guessing.
                showNotice(getString(R.string.offline_hint))
            }
        }
    }

    private fun backendStatusText(health: HealthResult): String = when (health) {
        is HealthResult.Ok -> if (health.ready) {
            getString(R.string.backend_ready, health.model.ifBlank { health.provider })
        } else {
            getString(R.string.backend_not_ready, health.provider.ifBlank { "unknown" })
        }
        is HealthResult.Unreachable -> if (health.code == NexErrorCodes.NOT_CONFIGURED) {
            getString(R.string.backend_not_configured)
        } else {
            getString(R.string.backend_unreachable)
        }
    }

    // ------------------------------------------------------------------
    // Signals from the voice core
    // ------------------------------------------------------------------

    private fun handleSignal(signal: UiSignal) {
        when (signal) {
            is UiSignal.VisionRequested -> startVisionTurn(signal.prompt)
            is UiSignal.PermissionNeeded -> requestRuntimePermission(signal.permission)
            is UiSignal.ReducedMotionChanged -> {
                updatingSwitches = true
                binding.reduceMotionSwitch.isChecked = signal.enabled
                updatingSwitches = false
                applyReducedMotion()
            }
            is UiSignal.FocusTimerStarted -> onFocusTimerStarted()
            is UiSignal.FocusTimerComplete -> onFocusTimerComplete()
            is UiSignal.TransientCue -> {
                // The animation itself comes from the cue flow; the name is surfaced from the
                // signal so the label never lags behind what is on screen.
                val spec = AnimationCatalog.byId(signal.cue.animationId)
                binding.animationNameText.text =
                    getString(R.string.animation_name_format, spec.id, spec.name)
            }
            is UiSignal.Notice -> showNotice(signal.message)
        }
    }

    private fun requestRuntimePermission(permission: String) {
        if (hasPermission(permission)) return
        when (permission) {
            Manifest.permission.RECORD_AUDIO -> {
                pendingVoiceStart = true
                explainThenRequest(getString(R.string.permission_microphone_rationale)) {
                    microphoneLauncher.launch(permission)
                }
            }
            Manifest.permission.CAMERA -> {
                explainThenRequest(getString(R.string.permission_camera_rationale)) {
                    cameraLauncher.launch(permission)
                }
            }
            Manifest.permission.ACCESS_COARSE_LOCATION ->
                explainThenRequest(getString(R.string.permission_location_rationale)) {
                    locationLauncher.launch(permission)
                }
            // NEX never requests anything else, so anything else is a bug and is refused.
            else -> NexLog.w("refused a permission request NEX does not use: $permission")
        }
    }

    private fun showNotice(message: String) {
        binding.noticeText.text = message
        binding.noticeText.isVisible = true
        noticeJob?.cancel()
        noticeJob = lifecycleScope.launch {
            delay(NOTICE_VISIBLE_MS)
            binding.noticeText.isVisible = false
        }
    }

    private fun applyReducedMotion() {
        val enabled = settings.reducedMotionEnabled(this)
        binding.animationView.setReducedMotion(enabled)
        updatingSwitches = true
        binding.reduceMotionSwitch.isChecked = enabled
        updatingSwitches = false
    }

    // ------------------------------------------------------------------
    // Focus timer (fully local)
    // ------------------------------------------------------------------

    private fun onFocusTimerStarted() {
        binding.timerText.isVisible = true
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val remainingMs = controller.focusRemainingMs()
                if (remainingMs <= 0L) break
                val totalSeconds = (remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
                binding.timerText.text = getString(
                    R.string.focus_running,
                    totalSeconds / SECONDS_PER_MINUTE,
                    totalSeconds % SECONDS_PER_MINUTE,
                )
                delay(MILLIS_PER_SECOND)
            }
        }
    }

    private fun onFocusTimerComplete() {
        timerJob?.cancel()
        timerJob = null
        binding.timerText.setText(R.string.focus_complete)
        binding.timerText.isVisible = true
        lifecycleScope.launch {
            delay(NOTICE_VISIBLE_MS)
            binding.timerText.isVisible = false
        }
    }

    // ------------------------------------------------------------------
    // Memory panel: show everything, allow deletion
    // ------------------------------------------------------------------

    private fun toggleMemoryPanel() {
        val show = !binding.memoryPanel.isVisible
        binding.memoryPanel.isVisible = show
        if (show) renderMemoryPanel()
    }

    private fun renderMemoryPanel() {
        updatingSwitches = true
        binding.memoryConsentSwitch.isChecked = memory.hasConsent()
        updatingSwitches = false

        val entries = memory.snapshot()
        val builder = StringBuilder()
            .append(getString(R.string.memory_header, entries.size, LocalMemoryStore.MAX_ENTRIES))
            .append('\n')
        if (entries.isEmpty()) {
            builder.append(getString(R.string.memory_empty))
        } else {
            entries.forEach { builder.append(getString(R.string.memory_entry, "${it.key}: ${it.value}")).append('\n') }
        }
        binding.memoryText.text = builder.toString().trimEnd()
    }

    private fun onMemoryConsentChanged(checked: Boolean) {
        val removed = if (checked) {
            memory.setConsent(true)
            0
        } else {
            memory.setConsent(false)
        }
        settings.memoryConsentGranted = checked
        renderMemoryPanel()
        if (removed > 0) {
            controller.speakExternal(
                getString(R.string.memory_deleted, removed),
                settings.language(),
                AnimationCue.PRIVACY_SHIELD,
            )
        }
    }

    private fun deleteAllMemory() {
        val removed = memory.deleteAll()
        renderMemoryPanel()
        controller.speakExternal(
            getString(R.string.memory_deleted, removed),
            settings.language(),
            AnimationCue.PRIVACY_SHIELD,
        )
    }

    // ------------------------------------------------------------------
    // Animation gallery: every one of the 56 is reachable from the UI
    // ------------------------------------------------------------------

    private fun showAnimationGallery() {
        val specs = AnimationCatalog.all
        val items = specs.map { getString(R.string.gallery_entry, it.id, it.name) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.gallery_title, specs.size))
            .setItems(items) { _, index -> previewAnimation(specs[index].id) }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun previewAnimation(id: Int) {
        previewJob?.cancel()
        binding.animationView.playId(id)
        renderAnimationName()
        previewJob = lifecycleScope.launch {
            delay(PREVIEW_HOLD_MS)
            binding.animationView.play(controller.animationCue.value)
            renderAnimationName()
        }
    }

    private fun nextPreviewId(): Int {
        val current = binding.animationView.currentSpec.id
        return if (current >= AnimationCatalog.SIZE) 1 else current + 1
    }

    // ------------------------------------------------------------------
    // Dialogs and permissions
    // ------------------------------------------------------------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Always explain before asking. The system dialog alone is not informed consent. */
    private fun explainThenRequest(message: String, request: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton(R.string.action_allow) { _, _ -> request() }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun showOpenSettingsDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.open_settings_message, message))
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            NexLog.w("app settings screen unavailable", error)
            showNotice(getString(R.string.open_settings_hint))
        }
    }

    /**
     * Approximate locality for a direct weather or place question.
     *
     * Returns `null` unless coarse location is granted and a recent fix exists — NEX never
     * guesses a city and never sends a precise fix. Only the coarse provider list is consulted;
     * no location listener is ever registered, so nothing runs in the background.
     */
    @SuppressLint("MissingPermission")
    private fun coarseLocationHint(): String? {
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = COARSE_PROVIDERS.firstOrNull { manager.allProviders.contains(it) } ?: return null
        val last = try {
            manager.getLastKnownLocation(provider)
        } catch (error: SecurityException) {
            NexLog.w("coarse location unavailable", error)
            null
        } ?: return null
        if (System.currentTimeMillis() - last.time > MAX_LOCATION_AGE_MS) {
            NexLog.i("coarse location fix too old to use")
            return null
        }
        return String.format(Locale.US, "%.2f,%.2f", last.latitude, last.longitude)
    }

    companion object {
        /** HTTPS fallback so a corrupted preference can never crash the app at launch. */
        private const val BACKEND_FALLBACK_URL = "https://nex-companion-api.onrender.com"
        private const val NOTICE_VISIBLE_MS = 8_000L
        private const val PREVIEW_HOLD_MS = 4_000L
        private const val LANGUAGE_SWITCH_CUE_MS = 2_200L
        private const val MAX_LOCATION_AGE_MS = 10L * 60L * 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private val COARSE_PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
    }
}
