package com.nexora.robot.vision

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageFormat
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.nexora.robot.util.NexLog
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional camera support.
 *
 * Privacy rules implemented here:
 *  * the session only starts when [com.nexora.robot.privacy.PermissionPolicy] allowed it, which
 *    requires an explicit user request in the current turn;
 *  * only the **front** camera is ever bound - a device without one gets an honest failure,
 *    never a silent rear-camera fallback;
 *  * [inUse] drives a **visible** on-screen indicator, so the user always knows the camera is on;
 *  * a single frame is captured **in memory** — `ImageCapture.takePicture(File)` is never used,
 *    nothing is written to storage, and [stop] unbinds the camera immediately;
 *  * the session never survives backgrounding: [com.nexora.robot.voice.VoiceReducer] clears
 *    `cameraInUse` and the activity calls [stop] from `onPause`.
 */
class CameraSessionController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {

    private val _inUse = MutableStateFlow(false)

    /** True while the camera hardware is bound. Drives the visible indicator. */
    val inUse: StateFlow<Boolean> = _inUse.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    /** Bind preview + single-shot capture. Returns false when no camera is available. */
    suspend fun start(): Boolean {
        if (_inUse.value) return true
        return try {
            val provider = awaitCameraProvider()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(JPEG_QUALITY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                // Front camera only, by product rule: requiring the front lens makes a
                // device without one fail honestly (CameraInfoUnavailableException -> stop()
                // -> false) instead of silently filming from the rear camera.
                CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build(),
                preview,
                capture,
            )
            cameraProvider = provider
            imageCapture = capture
            _inUse.value = true
            NexLog.i("camera session started: preview visible, frames kept in memory only")
            true
        } catch (error: Exception) {
            NexLog.e("camera session could not start", error)
            stop()
            false
        }
    }

    /** Unbind everything. The camera indicator must go off with this call. */
    fun stop() {
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { NexLog.w("camera unbind failed", it) }
        cameraProvider = null
        imageCapture = null
        if (_inUse.value) {
            _inUse.value = false
            NexLog.i("camera session stopped")
        }
    }

    /**
     * Capture exactly one frame into memory. Returns null when nothing could be captured,
     * so the caller can say so honestly instead of describing an imagined picture.
     */
    suspend fun captureSingleFrame(): ByteArray? = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null || !_inUse.value) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = image.toJpegBytes()
                    image.close()
                    if (continuation.isActive) continuation.resume(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    NexLog.w("frame capture failed", exception)
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        val provider = future.get()
                        if (continuation.isActive) continuation.resume(provider)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    /** FORMAT_JPEG frames arrive as one plane holding the encoded JPEG bytes. */
    private fun ImageProxy.toJpegBytes(): ByteArray? {
        if (format != ImageFormat.FORMAT_JPEG) return null
        if (planes.isEmpty()) return null
        val buffer: ByteBuffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private companion object {
        /** Small enough for one turn, good enough to read a page or recognise an object. */
        const val JPEG_QUALITY = 80
    }
}
