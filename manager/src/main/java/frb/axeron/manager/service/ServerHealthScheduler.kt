package frb.axeron.manager.service

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ServerHealthScheduler {
    private const val TAG = "ServerHealthScheduler"
    const val WORK_NAME = "axeron_server_health_check"
    private const val INTERVAL_MINUTES = 30L

    // runCatching: saat direct boot (LOCKED_BOOT_COMPLETED) database WorkManager belum bisa dibuka.
    fun schedule(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<ServerHealthWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "schedule failed", it) }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }.onFailure { Log.w(TAG, "cancel failed", it) }
    }
}
