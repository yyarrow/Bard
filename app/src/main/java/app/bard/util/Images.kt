package app.bard.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

fun Bitmap.downscale(maxDim: Int): Bitmap {
    val m = max(width, height)
    if (m <= maxDim) return this
    val scale = maxDim.toFloat() / m
    return Bitmap.createScaledBitmap(
        this, (width * scale).roundToInt(), (height * scale).roundToInt(), true
    )
}

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

fun Bitmap.toJpegBytes(quality: Int = 85): ByteArray =
    ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

/**
 * 从相册 Uri 解码。优先 ImageDecoder：photo picker 的 picker URI、HEIC
 * 都能读，且自动按 EXIF 转正；旧系统或失败时退回 BitmapFactory。
 */
fun decodeUri(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 软件位图：后续要 getPixel 采样底色、compress 上传
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = max(info.size.width, info.size.height)
                if (m > maxDim) {
                    val scale = maxDim.toFloat() / m
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }.onFailure {
            android.util.Log.w("Bard", "ImageDecoder failed for $uri, falling back", it)
        }
    }
    return decodeUriLegacy(context, uri, maxDim)
}

private fun decodeUriLegacy(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null

    val rotation = resolver.openInputStream(uri)?.use { stream ->
        when (ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } ?: 0

    return bitmap.rotate(rotation).downscale(maxDim)
}

/** 保存到相册 Pictures/拾诗，返回是否成功。 */
fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
    val name = "shishi_${System.currentTimeMillis()}.jpg"
    return if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/拾诗")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        val ok = resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        } ?: false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        ok
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
        File(dir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        true
    }
}

/** 通过系统分享面板分享图片。 */
fun shareImage(context: Context, bitmap: Bitmap) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "shishi_share.jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    val uri = FileProvider.getUriForFile(context, "app.bard.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享这首诗"))
}
