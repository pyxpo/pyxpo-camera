package dev.pyxpo.modules.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import dev.pyxpo.moduleapi.PxNativeView
import dev.pyxpo.moduleapi.PxReply
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One `CameraView`. It owns a lifecycle: RESUMED while the view is active, not paused and its activity is in front,
 * so CameraX opens and closes the camera with it. Props that change the use cases (facing, mode, scanning,
 * video quality, mirror) bind them again.
 */
class CameraView(private val activity: Activity, private val emit: (String, Any?) -> Unit) : PxNativeView, LifecycleOwner {
    private val preview = PreviewView(activity).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    override val view = preview

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private val work: ExecutorService = Executors.newSingleThreadExecutor()

    private var facing = "back"
    private var flash = "off"
    private var torch = false
    private var zoom = 0f
    private var mode = "picture"
    private var mute = false
    private var active = true
    private var paused = false
    private var mirror = false
    private var animateShutter = true
    private var quality: String? = null
    private var barcodeTypes: List<Int>? = null

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var bound = emptyList<UseCase>()
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var foreground = true
    private var disposed = false
    private var lastScan = 0L

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { if (a === activity) { foreground = true; if (camera == null) bind(); state() } }
        override fun onActivityPaused(a: Activity) { if (a === activity) { foreground = false; state() } }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    init {
        registry.currentState = Lifecycle.State.CREATED
        activity.application.registerActivityLifecycleCallbacks(callbacks)
        ProcessCameraProvider.getInstance(activity).let { f ->
            f.addListener({
                if (disposed) return@addListener
                try {
                    provider = f.get()
                    bind()
                } catch (e: Exception) {
                    emit("mount_error", mapOf("message" to (e.message ?: "The camera could not start")))
                }
            }, mainExecutor)
        }
    }

    override fun update(props: JSONObject, removed: Set<String>) {
        var rebind = false
        if (props.has("facing")) { val v = props.getString("facing"); rebind = rebind || v != facing; facing = v }
        if (props.has("mode")) { val v = props.getString("mode"); rebind = rebind || v != mode; mode = v }
        if (props.has("mirror")) { val v = props.getBoolean("mirror"); rebind = rebind || v != mirror; mirror = v }
        if (props.has("mute")) mute = props.getBoolean("mute")
        if (props.has("animate_shutter")) animateShutter = props.getBoolean("animate_shutter")
        if (props.has("video_quality") || "video_quality" in removed) {
            val v = props.optString("video_quality").ifEmpty { null }
            rebind = rebind || v != quality
            quality = v
        }
        val scanning = props.has("on_barcode_scanned") || (barcodeTypes != null && "on_barcode_scanned" !in removed)
        val types = if (!scanning) null else if (props.has("barcode_types")) CameraModule.types(props.getJSONArray("barcode_types")) else barcodeTypes
        if (types != barcodeTypes) { rebind = true; barcodeTypes = types }
        if (props.has("flash")) { flash = props.getString("flash"); imageCapture?.flashMode = flashMode() }
        if (props.has("enable_torch")) { torch = props.getBoolean("enable_torch"); camera?.cameraControl?.enableTorch(torch) }
        if (props.has("zoom")) { zoom = props.getDouble("zoom").toFloat(); camera?.cameraControl?.setLinearZoom(zoom) }
        if (props.has("active")) active = props.getBoolean("active")
        if (rebind && provider != null) bind()
        state()
    }

    override fun dispose() {
        disposed = true
        recording?.stop()
        activity.application.unregisterActivityLifecycleCallbacks(callbacks)
        provider?.unbind(*bound.toTypedArray())
        registry.currentState = Lifecycle.State.DESTROYED
        work.shutdown()
    }

    fun setPaused(p: Boolean) { paused = p; state() }

    private fun state() {
        if (disposed) return
        registry.currentState = if (active && !paused && foreground) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
    }

    private fun flashMode() = when (flash) {
        "on" -> ImageCapture.FLASH_MODE_ON
        "auto" -> ImageCapture.FLASH_MODE_AUTO
        else -> ImageCapture.FLASH_MODE_OFF
    }

    private fun selector() = if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    private fun bind() {
        val p = provider ?: return
        if (disposed) return
        p.unbind(*bound.toTypedArray())
        bound = emptyList()
        camera = null
        imageCapture = null
        videoCapture = null
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            emit("mount_error", mapOf("message" to "Camera permission is not granted"))
            return
        }
        try {
            if (!p.hasCamera(selector())) {
                emit("mount_error", mapOf("message" to "This device has no $facing camera"))
                return
            }
            val cases = ArrayList<UseCase>()
            cases += Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            if (mode == "video") {
                val q = when (quality) {
                    "2160p" -> Quality.UHD
                    "1080p" -> Quality.FHD
                    "720p" -> Quality.HD
                    "480p", "4:3" -> Quality.SD
                    else -> Quality.HIGHEST
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.Builder(recorder)
                    .setMirrorMode(if (mirror) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
                    .build()
                videoCapture = vc
                cases += vc
            } else {
                val ic = ImageCapture.Builder().setFlashMode(flashMode()).build()
                imageCapture = ic
                cases += ic
            }
            barcodeTypes?.let { cases += analysis(it) }
            camera = p.bindToLifecycle(this, selector(), *cases.toTypedArray())
            bound = cases
            camera?.cameraControl?.setLinearZoom(zoom)
            if (torch) camera?.cameraControl?.enableTorch(true)
            emit("camera_ready", null)
        } catch (e: Exception) {
            emit("mount_error", mapOf("message" to (e.message ?: "The camera could not start")))
        }
    }

    // barcodes

    private fun analysis(types: List<Int>): ImageAnalysis {
        val scanner = CameraModule.scanner(types)
        val a = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        a.setAnalyzer(work) { proxy -> scan(proxy, scanner) }
        return a
    }

    private fun scan(proxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val now = System.currentTimeMillis()
        if (now - lastScan < SCAN_INTERVAL_MS) { proxy.close(); return }
        val rotation = proxy.imageInfo.rotationDegrees
        val bmp = try { proxy.toBitmap() } catch (e: Exception) { proxy.close(); return }
        proxy.close()
        val (w, h) = if (rotation % 180 == 0) bmp.width to bmp.height else bmp.height to bmp.width
        try {
            val codes = com.google.android.gms.tasks.Tasks.await(scanner.process(InputImage.fromBitmap(bmp, rotation)))
            val first = codes.firstOrNull() ?: return
            val vw = preview.width.toFloat()
            val vh = preview.height.toFloat()
            if (vw <= 0 || vh <= 0) return
            val scale = maxOf(vw / w, vh / h)
            val res = CameraModule.result(first, scale, (vw - w * scale) / 2, (vh - h * scale) / 2,
                if (facing == "front") vw else null, activity.resources.displayMetrics.density) ?: return
            lastScan = now
            main.post { if (!disposed && barcodeTypes != null) emit("barcode_scanned", res) }
        } catch (_: Exception) {
        }
    }

    // photos

    fun takePicture(o: JSONObject, reply: PxReply) {
        val ic = imageCapture ?: run { reply(null, if (mode == "video") "takePictureAsync needs mode=\"picture\"" else "The camera is not ready"); return }
        if (o.optBoolean("shutterSound", true)) MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
        if (animateShutter) preview.animate().alpha(0f).setDuration(50).withEndAction { preview.animate().alpha(1f).setDuration(150) }
        val front = facing == "front"
        ic.takePicture(work, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bytes = if (image.format == ImageFormat.JPEG) {
                        val buf = image.planes[0].buffer
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    } else {
                        ByteArrayOutputStream().also { image.toBitmap().compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
                    }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()
                    reply(photo(bytes, rotation, front && o.optBoolean("mirror", mirror), o), null)
                } catch (e: Exception) {
                    image.close()
                    reply(null, e.message ?: "Taking the picture failed")
                }
            }

            override fun onError(e: ImageCaptureException) { reply(null, e.message ?: "Taking the picture failed") }
        })
    }

    private fun photo(bytes: ByteArray, rotation: Int, flip: Boolean, o: JSONObject): Map<String, Any?> {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("The camera returned no image")
        val m = Matrix().apply { postRotate(rotation.toFloat()); if (flip) postScale(-1f, 1f) }
        val bmp = if (rotation == 0 && !flip) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val png = o.optString("imageType") == "png"
        val out = ByteArrayOutputStream()
        bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, (o.optDouble("quality", 1.0) * 100).toInt(), out)
        val data = out.toByteArray()
        val file = CameraModule.cacheFile(activity, if (png) "png" else "jpg")
        file.writeBytes(data)
        val res = HashMap<String, Any?>()
        res["uri"] = Uri.fromFile(file).toString()
        res["width"] = bmp.width
        res["height"] = bmp.height
        res["format"] = if (png) "png" else "jpg"
        if (o.optBoolean("base64")) res["base64"] = Base64.encodeToString(data, Base64.NO_WRAP)
        if (o.optBoolean("exif")) res["exif"] = exif(bytes)
        return res
    }

    private fun exif(bytes: ByteArray): Map<String, Any?> {
        val e = ExifInterface(ByteArrayInputStream(bytes))
        val out = HashMap<String, Any?>()
        for (tag in EXIF_TAGS) {
            val v = e.getAttribute(tag) ?: continue
            out[tag] = v.toDoubleOrNull()?.takeIf { tag in EXIF_NUMBERS } ?: v
        }
        return out
    }

    // videos

    @SuppressLint("MissingPermission")
    fun record(o: JSONObject, reply: PxReply) {
        val vc = videoCapture ?: run { reply(null, if (mode != "video") "recordAsync needs mode=\"video\"" else "The camera is not ready"); return }
        if (recording != null) { reply(null, "A recording is already in progress"); return }
        val file = CameraModule.cacheFile(activity, "mp4")
        val opts = FileOutputOptions.Builder(file).apply {
            if (o.has("maxDuration")) setDurationLimitMillis((o.getDouble("maxDuration") * 1000).toLong())
            if (o.has("maxFileSize")) setFileSizeLimit(o.getLong("maxFileSize"))
        }.build()
        var pending = vc.output.prepareRecording(activity, opts)
        if (!mute && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                val ok = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                    event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                if (ok && file.exists()) reply(mapOf("uri" to Uri.fromFile(file).toString()), null)
                else {
                    file.delete()
                    reply(null, if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA)
                        "The recording stopped before the camera sent any video"
                    else event.cause?.message ?: "Recording failed (error ${event.error})")
                }
            }
        }
    }

    fun stopRecording() { recording?.stop() }

    /** JPEG output sizes of the current camera, largest first. */
    fun pictureSizes(): List<String> {
        val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lens = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == lens }
            ?: return emptyList()
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return (map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()).sortedByDescending { it.width.toLong() * it.height }
            .map { "${it.width}x${it.height}" }.distinct()
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 500L
        val EXIF_NUMBERS = setOf(
            ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        )
        val EXIF_TAGS = listOf(
            ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE,
        )
    }
}
