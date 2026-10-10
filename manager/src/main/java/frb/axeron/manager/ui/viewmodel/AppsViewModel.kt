package frb.axeron.manager.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.util.LruCache
import android.os.Parcelable
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import frb.axeron.api.Axeron
import frb.axeron.manager.AxeronApplication.Companion.axeronApp
import frb.axeron.manager.ui.util.HanziToPinyin
import frb.axeron.manager.ui.webui.AppIconUtil
import frb.axeron.server.util.AxWebLoader
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File


private const val WEB_ICON_SIZE_PX = 256
private const val WEB_ICON_CACHE_BYTES = 2 * 1024 * 1024

private val webIconPngCache = object : LruCache<String, ByteArray>(WEB_ICON_CACHE_BYTES) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
}

fun appSearchPinyin(label: String): String {
    return if (label.all { it.code < 128 }) "" else HanziToPinyin.getInstance().toPinyinString(label)
}

class AppsViewModel(application: Application) : AndroidViewModel(application) {
    @Parcelize
    data class AppInfo(
        val label: String,
        val packageInfo: PackageInfo,
        val isAdded: Boolean,
        val pinyin: String = "",
        val usesShizuku: Boolean = false,
    ) : Parcelable {
        class Handler : AxWebLoader.PathHandler {
            override fun handle(
                context: Context,
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val packageName = request!!.url.path.toString().substring(1)
                val bytes = webIconPngCache.get(packageName) ?: run {
                    val icon: Bitmap = AppIconUtil.loadAppIconSync(packageName, WEB_ICON_SIZE_PX)
                        ?: return null
                    val stream = ByteArrayOutputStream()
                    icon.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    stream.toByteArray().also { webIconPngCache.put(packageName, it) }
                }
                return WebResourceResponse(
                    "image/png",
                    null,
                    200,
                    "OK",
                    mapOf("Cache-Control" to "max-age=86400"),
                    ByteArrayInputStream(bytes)
                )
            }

        }

        val packageName: String
            get() = packageInfo.packageName
        val uid: Int
            get() = packageInfo.applicationInfo!!.uid
    }

    val file = File(
        PathHelper.getWorkingPath(
            Axeron.getAxeronInfo().isRoot(),
            AxeronApiConstant.folder.PARENT_BINARY
        ), "added_apps.txt"
    )
    var search by mutableStateOf("")

    private fun AppInfo.matches(query: String): Boolean {
        return label.contains(query, ignoreCase = true) ||
                packageName.contains(query, ignoreCase = true) ||
                (pinyin.isNotEmpty() && pinyin.contains(query, ignoreCase = true))
    }

    val addedList by derivedStateOf {
        val query = search
        addedApps.filter { it.matches(query) }
    }

    val installedList by derivedStateOf {
        val query = search
        installedApps.filter { it.matches(query) }
    }

    private val prefs = application.getSharedPreferences("apps_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var addedPackageNames: List<String> by mutableStateOf(emptyList())
        private set

    var addedApps: List<AppInfo> by mutableStateOf(emptyList())
        private set

    var installedApps: List<AppInfo> by mutableStateOf(emptyList())
        private set

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager

            // Ambil packageName yang sudah tersimpan
            addedPackageNames = getSavedPackageNames()
            val packages = Axeron.getPackages(0)

            val apps = packages.filterNot {
                it.packageName == axeronApp.packageName ||
                        it.applicationInfo!!.flags.and(ApplicationInfo.FLAG_SYSTEM) != 0
            }.map {
                val appInfo = it.applicationInfo!!
                val label = appInfo.loadLabel(pm).toString()
                AppInfo(
                    label = label,
                    packageInfo = it,
                    isAdded = it.packageName in addedPackageNames,
                    pinyin = appSearchPinyin(label)
                )
            }

            installedApps = apps
            addedApps = apps.filter {
                it.isAdded
            }
        }
    }

//    fun addApp(app: AppInfo) {
//        if (!addedApps.any { it.packageName == app.packageName }) {
//            val updatedApp = app.copy(isAdded = true)
//            addedApps = addedApps + updatedApp
//            installedApps = installedApps.map {
//                if (it.packageName == app.packageName) it.copy(isAdded = true) else it
//            }
//            saveAddedPackageNames(addedApps.map { it.packageName })
//        }
//    }
//
//    fun removeApp(packageName: String) {
//        addedApps = addedApps.filterNot { it.packageName == packageName }
//        installedApps = installedApps.map {
//            if (it.packageName == packageName) it.copy(isAdded = false) else it
//        }
//        saveAddedPackageNames(addedApps.map { it.packageName })
//    }

    fun addApp(app: AppInfo) {
        if (!addedApps.any { it.packageName == app.packageName }) {
            val updatedApp = app.copy(isAdded = true)
            addedApps = addedApps + updatedApp
            installedApps = installedApps.map {
                if (it.packageName == app.packageName) it.copy(isAdded = true) else it
            }
            saveAddedAppsToFile(addedApps.map { it.packageName })
        }
    }

    fun removeApp(packageName: String) {
        addedApps = addedApps.filterNot { it.packageName == packageName }
        installedApps = installedApps.map {
            if (it.packageName == packageName) it.copy(isAdded = false) else it
        }
        saveAddedAppsToFile(addedApps.map { it.packageName })
    }

    // ==== Penyimpanan hanya packageName ====

    private fun saveAddedAppsToFile(packageNames: List<String>) {
        addedPackageNames = packageNames
        try {
            val fs = Axeron.newFileService() ?: return
            val session = fs.getStreamSession(file.absolutePath, true, false) ?: return
            val fos = session.outputStream
            packageNames.forEach { pkg ->
                val app = installedApps.find { it.packageName == pkg }
                if (app != null) {
                    fos.write("${app.packageName}\n".toByteArray())
                }
            }
            fos.flush()
            fos.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSavedPackageNames(): List<String> {
        return try {
            val axFile = Axeron.newFileService() ?: return emptyList()
            if (!axFile.exists(file.absolutePath)) return emptyList()
            val fis = axFile.setFileInputStream(file.absolutePath)
            fis.use {
                it?.bufferedReader()?.readLines()?.mapNotNull { line ->
                    line.split(",").firstOrNull()
                } ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

//    private fun saveAddedPackageNames(packageNames: List<String>) {
//        val json = gson.toJson(packageNames)
//        prefs.edit { putString("added_apps", json) }
//    }
//
//    private fun getSavedPackageNames(): List<String> {
//        val json = prefs.getString("added_apps", null)
//        return if (!json.isNullOrEmpty()) {
//            val type = object : TypeToken<List<String>>() {}.type
//            gson.fromJson(json, type)
//        } else {
//            emptyList()
//        }
//    }
}


