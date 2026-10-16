package dev.pyxpo.modules.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.pyxpo.moduleapi.PxModule
import dev.pyxpo.moduleapi.PxModuleContext
import dev.pyxpo.moduleapi.PxNativeView
import dev.pyxpo.moduleapi.PxReply
import org.json.JSONArray
import java.io.File

/**
 * expo-camera on Android: a CameraX preview view (`PxCameraView`) with photo capture, video recording and
 * ML Kit barcode scanning (bundled model, no Google Play services).
 */
class CameraModule : PxModule() {
    override val name = "Camera"
    override val viewTypes = setOf("PxCameraView")

    override fun makeView(ctx: PxModuleContext, type: String, emit: (name: String, value: Any?) -> Unit): PxNativeView =
        CameraView(ctx.activity, emit)

    override fun call(ctx: PxModuleContext, method: String, args: JSONArray, reply: PxReply) {
        val a = ctx.activity
        try {
            when (method) {
                "getPermission" -> reply(status(a, perm(args.getString(0))), null)
                "requestPermission" -> request(ctx, perm(args.getString(0)), reply)
                "isAvailable" -> reply(a.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), null)
                "scanFromURL" -> scanFromURL(a, args.getString(0), types(args.getJSONArray(1)), reply)
                "takePicture" -> view(ctx, args)?.takePicture(args.getJSONObject(1), reply) ?: reply(null, UNMOUNTED)
                "record" -> view(ctx, args)?.record(args.getJSONObject(1), reply) ?: reply(null, UNMOUNTED)
                "stopRecording" -> { view(ctx, args)?.stopRecording(); reply(null, null) }
                "pausePreview" -> { view(ctx, args)?.setPaused(true); reply(null, null) }
                "resumePreview" -> { view(ctx, args)?.setPaused(false); reply(null, null) }
                "pictureSizes" -> view(ctx, args)?.let { reply(it.pictureSizes(), null) } ?: reply(null, UNMOUNTED)
                else -> reply(null, "Camera has no method $method")
            }
        } catch (e: Exception) {
            reply(null, e.message ?: e.toString())
        }
    }

    private fun view(ctx: PxModuleContext, args: JSONArray) = ctx.view(args.getInt(0)) as? CameraView

    // permissions

    private fun perm(kind: String) = if (kind == "microphone") Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA

    private fun prefs(a: Activity) = a.getSharedPreferences("pyxpo.permissions", Context.MODE_PRIVATE)

    /** Expo status: never asked is `undetermined`; denied can be asked again until Android stops showing the dialog. */
    private fun status(a: Activity, p: String): Map<String, Any> = when {
        a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED -> mapOf("status" to "granted", "canAskAgain" to true)
        !prefs(a).getBoolean(p, false) -> mapOf("status" to "undetermined", "canAskAgain" to true)
        else -> mapOf("status" to "denied", "canAskAgain" to a.shouldShowRequestPermissionRationale(p))
    }

    private fun request(ctx: PxModuleContext, p: String, reply: PxReply) {
        val a = ctx.activity
        val now = status(a, p)
        if (now["status"] == "granted" || now["canAskAgain"] != true) { reply(now, null); return }
        ctx.requestPermissions(arrayOf(p)) { _ ->
            prefs(a).edit().putBoolean(p, true).apply()
            reply(status(a, p), null)
        }
    }

    // scanning images

    private fun scanFromURL(a: Activity, url: String, formats: List<Int>, reply: PxReply) {
        val uri = Uri.parse(url)
        val bmp = when (uri.scheme) {
            null, "file" -> BitmapFactory.decodeFile(uri.path ?: url)
            "content" -> a.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            else -> null
        } ?: run { reply(null, "Cannot read an image at $url"); return }
        val scanner = scanner(formats)
        scanner.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { codes -> reply(codes.mapNotNull { result(it, 1f, 0f, 0f, null) }, null) }
            .addOnFailureListener { e -> reply(null, e.message ?: "Barcode scanning failed") }
            .addOnCompleteListener { scanner.close() }
    }

    companion object {
        const val UNMOUNTED = "The CameraView is not mounted"

        private val FORMATS = mapOf(
            "aztec" to Barcode.FORMAT_AZTEC, "ean13" to Barcode.FORMAT_EAN_13, "ean8" to Barcode.FORMAT_EAN_8,
            "qr" to Barcode.FORMAT_QR_CODE, "pdf417" to Barcode.FORMAT_PDF417, "upc_e" to Barcode.FORMAT_UPC_E,
            "datamatrix" to Barcode.FORMAT_DATA_MATRIX, "code39" to Barcode.FORMAT_CODE_39,
            "code93" to Barcode.FORMAT_CODE_93, "itf14" to Barcode.FORMAT_ITF, "codabar" to Barcode.FORMAT_CODABAR,
            "code128" to Barcode.FORMAT_CODE_128, "upc_a" to Barcode.FORMAT_UPC_A,
        )
        private val NAMES = FORMATS.entries.associate { (k, v) -> v to k }

        fun types(a: JSONArray?): List<Int> =
            if (a == null) FORMATS.values.toList() else (0 until a.length()).mapNotNull { FORMATS[a.getString(it)] }

        fun scanner(formats: List<Int>): BarcodeScanner {
            val first = formats.firstOrNull() ?: Barcode.FORMAT_QR_CODE
            val opts = BarcodeScannerOptions.Builder().setBarcodeFormats(first, *formats.drop(1).toIntArray()).build()
            return BarcodeScanning.getClient(opts)
        }

        /**
         * Expo's `BarcodeScanningResult`. Image points map to the view as `p * scale + offset`, then to dp
         * (`density`); `mirrorWidth` flips x for a mirrored preview.
         */
        fun result(b: Barcode, scale: Float, dx: Float, dy: Float, mirrorWidth: Float?, density: Float = 1f): Map<String, Any?>? {
            val type = NAMES[b.format] ?: return null
            fun x(v: Int): Float { val p = v * scale + dx; return (if (mirrorWidth != null) mirrorWidth - p else p) / density }
            fun y(v: Int) = (v * scale + dy) / density
            val r = b.boundingBox ?: Rect()
            val left = minOf(x(r.left), x(r.right))
            val corners = (b.cornerPoints ?: emptyArray<Point>()).map { mapOf("x" to x(it.x).toDouble(), "y" to y(it.y).toDouble()) }
            return mapOf(
                "type" to type, "data" to (b.rawValue ?: ""), "raw" to b.rawValue,
                "bounds" to mapOf(
                    "origin" to mapOf("x" to left.toDouble(), "y" to y(r.top).toDouble()),
                    "size" to mapOf("width" to (r.width() * scale / density).toDouble(), "height" to (r.height() * scale / density).toDouble()),
                ),
                "cornerPoints" to corners,
            )
        }

        fun cacheFile(c: Context, ext: String): File {
            val dir = File(c.cacheDir, "Camera").apply { mkdirs() }
            return File(dir, "${java.util.UUID.randomUUID()}.$ext")
        }
    }
}
