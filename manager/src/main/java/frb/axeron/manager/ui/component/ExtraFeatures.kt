package frb.axeron.manager.ui.component

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import frb.axeron.manager.R
import frb.axeron.manager.ui.webui.WebUIActivity
import frb.axeron.server.PluginInfo
import java.util.Locale
import android.graphics.Bitmap

@get:Composable
val Int.scaleDp: Dp
    get() {
        val configuration = LocalConfiguration.current
        val fontScale = configuration.fontScale
        return (this@scaleDp * fontScale).dp
    }

fun Uri.resolveDisplayName(context: Context): String =
    context.contentResolver.query(
        this,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else "unknown.zip"
    } ?: "unknown.zip"

@Composable
fun UseLifecycle(
    onResume: () -> Unit = {},
    onPause: () -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}


fun formatSize(size: Long): String {
    if (size == 0L) return "null"
    val kb = 1024
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        size >= gb -> String.format(Locale.getDefault(), "%.2f GB", size.toDouble() / gb)
        size >= mb -> String.format(Locale.getDefault(), "%.2f MB", size.toDouble() / mb)
        size >= kb -> String.format(Locale.getDefault(), "%.2f KB", size.toDouble() / kb)
        else -> "$size B"
    }
}

fun createWebUIShortcut(
    context: Context,
    plugin: PluginInfo,
    label: String = plugin.prop.name,
    iconBitmap: Bitmap? = null
) {
    val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
    val shortLabel = label.trim().ifEmpty { plugin.prop.name }
    val icon = if (iconBitmap != null) {
        Icon.createWithBitmap(iconBitmap)
    } else {
        Icon.createWithResource(context, R.mipmap.ic_launcher)
    }

    val shortcut = ShortcutInfo.Builder(context, plugin.prop.id)
        .setShortLabel(shortLabel)
        .setLongLabel(shortLabel)
        .setIcon(icon)
        .setIntent(
            Intent(context, WebUIActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("id", plugin.prop.id)
            }
        )
        .build()

    if (shortcutManager.pinnedShortcuts.any { it.id == plugin.prop.id }) {
        shortcutManager.updateShortcuts(listOf(shortcut))
        Toast.makeText(context, R.string.shortcut_updated, Toast.LENGTH_SHORT).show()
        return
    }

    if (shortcutManager.isRequestPinShortcutSupported) {
        shortcutManager.requestPinShortcut(shortcut, null)
    }
}
