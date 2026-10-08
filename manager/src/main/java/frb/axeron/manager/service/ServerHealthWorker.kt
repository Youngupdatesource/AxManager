package frb.axeron.manager.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import frb.axeron.api.Axeron
import kotlinx.coroutines.delay

class ServerHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ServerHealthWorker"

        // Jika worker membangunkan proses manager dari kondisi mati, binder dari server yang
        // sehat baru tiba beberapa saat kemudian. Tunggu dulu agar server sehat tidak di-restart.
        private const val BINDER_WAIT_TRIES = 10
        private const val BINDER_WAIT_STEP_MS = 500L
    }

    override suspend fun doWork(): Result {
        if (!ServerGuard.autoRestartEnabled) return Result.success()
        if (!ServerGuard.wasRunning) return Result.success()

        repeat(BINDER_WAIT_TRIES) {
            if (Axeron.pingBinder()) return Result.success()
            delay(BINDER_WAIT_STEP_MS)
        }

        Log.w(TAG, "Server tidak merespons, mencoba restart dari WorkManager")
        ServerGuard.restart(applicationContext)
        return Result.success()
    }
}
