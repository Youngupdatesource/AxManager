package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import frb.axeron.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Penyimpanan banner kartu status Home. File disalin sekali ke filesDir (tidak bergantung pada Uri picker),
 * ukurannya dibatasi, dan divalidasi bisa didekode sebelum dipakai.
 */
object BannerStore {
    enum class Result { OK, TOO_LARGE, FAILED }

    private const val FILE_NAME = "home_banner"
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** Naik setiap banner diganti/dihapus supaya UI dan cache Coil ikut diperbarui. */
    var version by mutableLongStateOf(0L)
        private set

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    suspend fun import(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.FAILED
            var total = 0L
            var tooLarge = false
            input.use { src ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = src.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            tooLarge = true
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                tmp.delete()
                return@withContext Result.TOO_LARGE
            }

            // Validasi murah: hanya baca dimensi, tanpa memuat bitmap.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                tmp.delete()
                return@withContext Result.FAILED
            }

            val target = file(context)
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            version = System.currentTimeMillis()
            Result.OK
        } catch (e: Exception) {
            tmp.delete()
            Result.FAILED
        }
    }

    fun clear(context: Context) {
        file(context).delete()
        version = System.currentTimeMillis()
    }
}

/** File banner kustom bila ada; dihitung ulang setiap banner diganti atau dihapus. */
@Composable
fun rememberStatusBannerFile(): File? {
    val context = LocalContext.current
    val version = BannerStore.version
    return remember(version) { BannerStore.file(context).takeIf { it.exists() } }
}

/**
 * Menggambar banner (gambar/GIF) mengisi parent.
 *
 * - Digambar lewat Modifier.paint(sizeToIntrinsics = false) supaya ukuran gambar tidak ikut
 *   menentukan tinggi kartu (StatusCard memakai IntrinsicSize.Min).
 * - Ukuran request = ukuran area gambar, jadi gambar/GIF didekode di ukuran tampil, bukan ukuran asli.
 * - GIF hanya dianimasikan saat Activity STARTED; selain itu painter dilepas dari komposisi
 *   sehingga animasi berhenti dan tidak menghabiskan baterai di background.
 */
@Composable
fun StatusBannerImage(file: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = BannerStore.version
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    var areaSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.onSizeChanged { areaSize = it }) {
        if (visible && areaSize.width > 0 && areaSize.height > 0) {
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .memoryCacheKey("status_banner:$version:${areaSize.width}x${areaSize.height}")
                    .size(areaSize.width, areaSize.height)
                    .build(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .paint(painter, sizeToIntrinsics = false, contentScale = ContentScale.Crop)
            )
        }
    }
}

/** Item pengaturan banner untuk layar Appearance. */
@Composable
fun BannerSettingItems() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = rememberStatusBannerFile()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = when (BannerStore.import(context.applicationContext, uri)) {
                BannerStore.Result.OK -> null
                BannerStore.Result.TOO_LARGE -> R.string.home_banner_too_large
                BannerStore.Result.FAILED -> R.string.home_banner_failed
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    SettingsItem(
        iconVector = Icons.Filled.Image,
        label = stringResource(R.string.status_banner),
        description = stringResource(R.string.status_banner_desc),
        onClick = { picker.launch("image/*") }
    ) { _, _ ->
        if (file != null) {
            StatusBannerImage(
                file = file,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }

    if (file != null) {
        SettingsItem(
            iconVector = Icons.Filled.Delete,
            label = stringResource(R.string.home_banner_remove),
            onClick = { BannerStore.clear(context) }
        )
    }
}
