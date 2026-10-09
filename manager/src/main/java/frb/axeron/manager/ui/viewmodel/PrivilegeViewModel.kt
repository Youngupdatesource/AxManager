package frb.axeron.manager.ui.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import frb.axeron.api.Axeron
import frb.axeron.manager.ui.util.HanziToPinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivilegeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val FLAG_ALLOWED = 1 shl 1
        private const val FLAG_DENIED = 1 shl 2
        private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED
    }

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    var search by mutableStateOf("")

    // Di PrivilegeViewModel.kt
    val privilegeList by derivedStateOf {
        val currentSearch = search
        val allPrivileges = privileges.values

        if (currentSearch.isEmpty()) {
            allPrivileges.filter { it.isNotSystemOrSelf() }.toList()
        } else {
            allPrivileges.asSequence()
                .filter { it.isNotSystemOrSelf() }
                .filter { app ->
                    app.label.contains(currentSearch, true) ||
                            app.packageName.contains(currentSearch, true) ||
                            (app.pinyin.isNotEmpty() && app.pinyin.contains(currentSearch, true))
                }
                .toList()
        }
            .also { isRefreshing = false }
    }

    // Helper Extension
    private fun AppsViewModel.AppInfo.isNotSystemOrSelf(): Boolean {
        val isSystem = (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isSelf = uid == android.os.Process.myUid()
        return !isSystem && !isSelf
    }


    var privileges by mutableStateOf<Map<Int, AppsViewModel.AppInfo>>(HashMap())
        private set

    val privilegedCount by derivedStateOf {
        privileges.values.count { it.isAdded }
    }

    fun granted(uid: Int): Boolean {
        return (Axeron.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
    }

    fun grant(uid: Int) {
        Axeron.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
        privileges = privileges.toMutableMap().apply {
            this[uid]?.let {
                this[uid] = it.copy(isAdded = true)
            }
        }
    }

    fun revoke(uid: Int) {
        Axeron.updateFlagsForUid(uid, MASK_PERMISSION, 0)
        privileges = privileges.toMutableMap().apply {
            this[uid]?.let {
                this[uid] = it.copy(isAdded = false)
            }
        }
    }


    private fun getApplications(): List<PackageInfo> {
        return application.packageManager.getInstalledPackages(0)
    }

    fun loadInstalledApps(refresh: Boolean = true) {
        if (isRefreshing && refresh) return

        viewModelScope.launch {
            isRefreshing = refresh
            val pm = application.packageManager

            val selfUid = android.os.Process.myUid()

            val result = withContext(Dispatchers.IO) {
                // Sebelumnya semua paket (termasuk ratusan app sistem) di-loadLabel dan di-IPC ke daemon
                // satu per satu, lalu baru dibuang oleh filter di UI. Buang dulu di sini.
                val packages = getApplications().filter {
                    val info = it.applicationInfo ?: return@filter false
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && info.uid != selfUid
                }

                // loadLabel + getFlagsForUid (Binder) dibagi ke beberapa worker.
                val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val chunkSize = ((packages.size + workers - 1) / workers).coerceAtLeast(1)
                val loaded = coroutineScope {
                    packages.chunked(chunkSize).map { chunk ->
                        async {
                            chunk.map { packageInfo ->
                                val appInfo = packageInfo.applicationInfo!!
                                Triple(
                                    packageInfo,
                                    appInfo.loadLabel(pm).toString(),
                                    granted(appInfo.uid)
                                )
                            }
                        }
                    }.awaitAll().flatten()
                }

                // Pinyin tetap berurutan (HanziToPinyin singleton, thread-safety tidak dijamin).
                loaded.associate { (packageInfo, label, isGranted) ->
                    packageInfo.applicationInfo!!.uid to AppsViewModel.AppInfo(
                        label = label,
                        packageInfo = packageInfo,
                        isAdded = isGranted,
                        pinyin = appSearchPinyin(label)
                    )
                }
            }

            privileges = result
            isRefreshing = false
        }
    }
}