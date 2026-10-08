package frb.axeron.manager.ui.component

import android.content.pm.PackageInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun AppIcon(
    packageInfo: PackageInfo,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sizePx = remember(context) {
        context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
    }
    val request = remember(packageInfo.packageName, packageInfo.lastUpdateTime, sizePx) {
        ImageRequest.Builder(context)
            .data(packageInfo)
            .size(sizePx)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier
    )
}
