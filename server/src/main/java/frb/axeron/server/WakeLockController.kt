package frb.axeron.server

import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IPowerManager
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import frb.axeron.server.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WakeLockController(
    private val powerManager: () -> IPowerManager,
    private val tag: String = DEFAULT_TAG,
    private val packageName: String = DEFAULT_PACKAGE,
    private val releaseGraceMs: Long = DEFAULT_RELEASE_GRACE_MS
) {

    companion object {
        private val LOGGER = Logger("WakeLockController")

        const val DEFAULT_TAG = "axeron::wakelock"
        const val DEFAULT_PACKAGE = "axeron_server"
        const val DEFAULT_LEASE_TIMEOUT_MS = 10 * 60 * 1000L
        const val DEFAULT_RELEASE_GRACE_MS = 2_000L

        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_MAX_MS = 30_000L
        private const val SHUTDOWN_WAIT_MS = 1_000L
    }

    class Lease internal constructor(
        val id: Long,
        val owner: String,
        val timeoutMs: Long,
        private val controller: WakeLockController
    ) : AutoCloseable {

        internal val createdAt: Long = SystemClock.elapsedRealtime()
        internal var expiry: Runnable? = null

        private val closed = AtomicBoolean(false)

        val isClosed: Boolean
            get() = closed.get()

        internal fun markClosed(): Boolean = closed.compareAndSet(false, true)

        override fun close() {
            if (markClosed()) {
                controller.post { controller.drop(this, "released") }
            }
        }
    }

    private val thread = HandlerThread("axeron-wakelock").apply { start() }
    private val handler = Handler(thread.looper)
    private val ids = AtomicLong(0)
    private val token = Binder()

    private val watchers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "axeron-wakelock-watch").apply { isDaemon = true }
    }

    private val leases = LinkedHashMap<Long, Lease>()
    private var held = false
    private var stopped = false
    private var backoffMs = RETRY_BASE_MS

    private val releaseTask = Runnable { onReleaseGrace() }
    private val retryTask = Runnable { reconcile() }

    fun acquire(owner: String, timeoutMs: Long = DEFAULT_LEASE_TIMEOUT_MS): Lease {
        val lease = Lease(ids.incrementAndGet(), owner, timeoutMs.coerceAtLeast(1L), this)
        if (!post { onOpen(lease) }) {
            lease.markClosed()
        }
        return lease
    }

    fun bind(lease: Lease, process: Process) {
        try {
            watchers.execute {
                try {
                    process.waitFor()
                } catch (ignored: InterruptedException) {
                } finally {
                    lease.close()
                }
            }
        } catch (ignored: RejectedExecutionException) {
            lease.close()
        }
    }

    fun shutdown() {
        val done = CountDownLatch(1)
        val posted = post {
            try {
                stopped = true
                handler.removeCallbacksAndMessages(null)
                leases.values.forEach { it.markClosed() }
                leases.clear()
                if (held) {
                    nativeRelease()
                }
            } finally {
                done.countDown()
            }
        }
        if (posted) {
            done.await(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        thread.quitSafely()
        watchers.shutdownNow()
    }

    internal fun post(block: () -> Unit): Boolean = handler.post { block() }

    private fun onOpen(lease: Lease) {
        if (stopped || lease.isClosed) return
        leases[lease.id] = lease
        val expiry = Runnable { expire(lease) }
        lease.expiry = expiry
        handler.postDelayed(expiry, lease.timeoutMs)
        LOGGER.i("lease open #${lease.id} owner=${lease.owner} timeout=${lease.timeoutMs}ms active=${leases.size}")
        reconcile()
    }

    private fun expire(lease: Lease) {
        if (!lease.markClosed()) return
        LOGGER.w("lease expired #${lease.id} owner=${lease.owner} age=${SystemClock.elapsedRealtime() - lease.createdAt}ms")
        drop(lease, "expired")
    }

    internal fun drop(lease: Lease, reason: String) {
        lease.expiry?.let { handler.removeCallbacks(it) }
        lease.expiry = null
        if (leases.remove(lease.id) == null) return
        LOGGER.i("lease $reason #${lease.id} owner=${lease.owner} active=${leases.size}")
        reconcile()
    }

    private fun reconcile() {
        if (stopped) return
        handler.removeCallbacks(retryTask)
        handler.removeCallbacks(releaseTask)
        if (leases.isNotEmpty()) {
            if (!held && !nativeAcquire()) {
                handler.postDelayed(retryTask, backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(RETRY_MAX_MS)
            }
        } else if (held) {
            handler.postDelayed(releaseTask, releaseGraceMs)
        }
    }

    private fun onReleaseGrace() {
        if (stopped || leases.isNotEmpty() || !held) return
        if (!nativeRelease()) {
            handler.postDelayed(releaseTask, backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RETRY_MAX_MS)
        }
    }

    private fun nativeAcquire(): Boolean {
        return try {
            val pm = powerManager()
            val flags = PowerManager.PARTIAL_WAKE_LOCK
            val uid = Os.getuid()
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    pm.acquireWakeLockWithUid(token, flags, tag, packageName, uid, 0, null)

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    pm.acquireWakeLockWithUid(token, flags, tag, packageName, uid, 0)

                else ->
                    pm.acquireWakeLockWithUid(token, flags, tag, packageName, uid)
            }
            held = true
            backoffMs = RETRY_BASE_MS
            LOGGER.i("wakelock acquired")
            true
        } catch (e: Exception) {
            LOGGER.e("wakelock acquire failed, retry in ${backoffMs}ms", e)
            false
        }
    }

    private fun nativeRelease(): Boolean {
        return try {
            powerManager().releaseWakeLock(token, 0)
            held = false
            backoffMs = RETRY_BASE_MS
            LOGGER.i("wakelock released")
            true
        } catch (e: Exception) {
            LOGGER.e("wakelock release failed, retry in ${backoffMs}ms", e)
            false
        }
    }
}
