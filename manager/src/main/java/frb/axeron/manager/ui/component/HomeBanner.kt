package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import frb.axeron.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Penyimpanan banner Home. File disalin sekali ke filesDir (tidak bergantung pada Uri picker),
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

@Composable
fun HomeBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val version = BannerStore.version
    val bannerFile = remember(version) { BannerStore.file(context).takeIf { it.exists() } }

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

    // GIF hanya dianimasikan saat layar benar-benar tampil: begitu Activity tidak STARTED,
    // image dilepas dari komposisi sehingga animasi berhenti dan tidak menghabiskan baterai.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(if (bannerFile != null) 150.dp else 72.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        if (bannerFile != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (visible) {
                    // Ukuran request = ukuran composable, jadi gambar/GIF didekode di ukuran tampil,
                    // bukan ukuran aslinya.
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(bannerFile)
                            .memoryCacheKey("home_banner:$version")
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(onClick = { picker.launch("image/*") }) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = stringResource(R.string.home_banner_change)
                        )
                    }
                    FilledTonalIconButton(onClick = { BannerStore.clear(context) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.home_banner_remove)
                        )
                    }
                }
            }
        } else {
            Card(
                onClick = { picker.launch("image/*") },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.home_banner_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
