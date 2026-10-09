package frb.axeron.server

import android.system.Os
import frb.axeron.server.util.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pengganti wakelock untuk kestabilan daemon, semuanya bisa dilakukan sebagai shell UID:
 *
 * 1. Diagnostik startup: mencatat cgroup, oom_score_adj, dan status adbd/USB. Dengan ini penyebab
 *    kematian daemon bisa dibuktikan dari log, bukan ditebak.
 * 2. Mengecualikan app manager dari pembatasan baterai (Doze whitelist + app ops background),
 *    supaya lapisan pemulih (WorkManager/auto-restart di manager) tetap berjalan saat idle.
 *    Ini tidak menahan CPU dan tidak membuat device tetap terjaga.
 */
object DaemonGuard {
    private val LOGGER = Logger("DaemonGuard")

    private const val CMD_TIMEOUT_SEC = 5L

    fun start(managerPackage: String) {
        Thread({
            runCatching { logDiagnostics() }.onFailure { LOGGER.e("diagnostics failed", it) }
            runCatching { exemptManager(managerPackage) }.onFailure { LOGGER.e("exempt failed", it) }
        }, "axeron-guard").apply { isDaemon = true }.start()
    }

    private fun logDiagnostics() {
        LOGGER.i("uid=${Os.getuid()} pid=${Os.getpid()} ppid=${Os.getppid()}")
        LOGGER.i("cgroup: ${readFile("/proc/self/cgroup")}")
        LOGGER.i("oom_score_adj=${readFile("/proc/self/oom_score_adj")}")

        // Server yang dimulai lewat adbd ikut ter-kill saat adbd di-stop init (cgroup service adbd).
        for (key in listOf(
            "init.svc.adbd", "sys.usb.config", "sys.usb.state", "persist.sys.usb.config",
            "service.adb.tcp.port", "persist.adb.tls_server.enable"
        )) {
            LOGGER.i("prop $key=${run("getprop", key)}")
        }

        // Bukti apakah shell UID boleh memindahkan diri dari cgroup adbd (hanya dibaca, tidak diubah).
        val root = File("/sys/fs/cgroup/cgroup.procs")
        LOGGER.i("cgroup root writable=${root.canWrite()} exists=${root.exists()}")
    }

    private fun exemptManager(pkg: String) {
        run("cmd", "deviceidle", "whitelist", "+$pkg").let {
            LOGGER.i("deviceidle whitelist +$pkg -> $it")
        }
        for (op in listOf("RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")) {
            LOGGER.i("appops $op allow -> ${run("cmd", "appops", "set", pkg, op, "allow")}")
        }
    }

    private fun readFile(path: String): String = try {
        File(path).readText().trim().replace('\n', ' ')
    } catch (e: Exception) {
        "unreadable(${e.javaClass.simpleName})"
    }

    private fun run(vararg cmd: String): String = try {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (!process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "timeout"
        } else {
            output.replace('\n', ' ').take(200)
        }
    } catch (e: Exception) {
        "error(${e.javaClass.simpleName})"
    }
}
