package frb.axeron.manager.ui.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.zhanghai.android.appiconloader.AppIconLoader

class ThrottledAppIconFetcher(
    private val packageInfo: PackageInfo,
    private val loader: AppIconLoader,
    private val gate: Semaphore,
    private val resources: Resources
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val applicationInfo = packageInfo.applicationInfo
            ?: throw IllegalStateException("applicationInfo null: ${packageInfo.packageName}")
        val bitmap = gate.withPermit {
            withContext(Dispatchers.IO) { loader.loadIcon(applicationInfo) }
        }
        return DrawableResult(
            drawable = BitmapDrawable(resources, bitmap),
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    class Factory(
        iconSizePx: Int,
        context: Context,
        maxParallel: Int = DEFAULT_PARALLELISM
    ) : Fetcher.Factory<PackageInfo> {

        private val appContext = context.applicationContext
        private val loader = AppIconLoader(iconSizePx, false, appContext)
        private val gate = Semaphore(maxParallel)

        override fun create(
            data: PackageInfo,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = ThrottledAppIconFetcher(data, loader, gate, appContext.resources)

        companion object {
            const val DEFAULT_PARALLELISM = 3
        }
    }
}
