package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import frb.axeron.api.Axeron
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

const val SHORTCUT_ICON_SIZE_PX = 192
private const val SHORTCUT_IMAGE_MAX_BYTES = 16 * 1024 * 1024

fun squareBitmap(source: Bitmap, size: Int = SHORTCUT_ICON_SIZE_PX): Bitmap {
    val side = minOf(source.width, source.height)
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    val cropped = Bitmap.createBitmap(source, left, top, side, side)
    if (side == size) return cropped
    val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
    if (scaled !== cropped && cropped !== source) cropped.recycle()
    return scaled
}

private fun InputStream.readLimited(limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val shortSide = minOf(bounds.outWidth, bounds.outHeight)
    while (shortSide / (sample * 2) >= SHORTCUT_ICON_SIZE_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

fun decodeShortcutBitmap(bytes: ByteArray): Bitmap? {
    val decoded = decodeSampled(bytes) ?: return null
    val square = squareBitmap(decoded)
    if (square !== decoded) decoded.recycle()
    return square
}

suspend fun readShortcutBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readLimited(SHORTCUT_IMAGE_MAX_BYTES)
        } ?: return@withContext null
        decodeShortcutBitmap(bytes)
    } catch (e: Exception) {
        null
    }
}

suspend fun loadPluginBannerBitmap(context: Context, plugin: PluginInfo): Bitmap? {
    val banner = plugin.prop.banner
    if (banner.isEmpty()) return null
    return try {
        if (banner.startsWith("http", true)) {
            val request = ImageRequest.Builder(context)
                .data(banner)
                .size(SHORTCUT_ICON_SIZE_PX * 2)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result is SuccessResult) {
                val drawable = result.drawable
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap()
                squareBitmap(bitmap)
            } else {
                null
            }
        } else {
            withContext(Dispatchers.IO) {
                val dir = File(
                    PathHelper.getWorkingPath(
                        Axeron.getAxeronInfo().isRoot(),
                        AxeronApiConstant.folder.PARENT_PLUGIN
                    ),
                    plugin.prop.id
                )
                val stream = Axeron.newFileService()
                    .setFileInputStream(File(dir, banner).absolutePath)
                val bytes = stream?.use { it.readLimited(SHORTCUT_IMAGE_MAX_BYTES) }
                bytes?.let { decodeShortcutBitmap(it) }
            }
        }
    } catch (e: Exception) {
        null
    }
}
