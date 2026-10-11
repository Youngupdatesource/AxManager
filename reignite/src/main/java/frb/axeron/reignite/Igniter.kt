package frb.axeron.reignite

import android.ddm.DdmHandleAppName
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

object Igniter {

    private var VERSION = "v1.1"

    private val AXERONDIR = System.getenv("AXERONDIR")
    private val PLUGINS_DIR = File("$AXERONDIR/plugins")
    private val PLUGINS_UPDATE_DIR = File("$AXERONDIR/plugins_update")
    private val AXERONXBIN = File("$AXERONDIR/xbin")

    private var DEBUG = false

    // ===============================
    // ENTRY
    // ===============================
    @JvmStatic
    fun main(args: Array<String>) {
        val debug = args.firstOrNull() == "true"
        DEBUG = debug

        DdmHandleAppName.setAppName("axeron_plugin_igniter", 0)
        Log.i("test", "AXERON Plugin Manager ($VERSION)")
        println("AXERON Plugin Manager ($VERSION)")

        cleanUpdateDir()
        mainLoop()

        println("- Completed")
        kotlin.system.exitProcess(0)
    }

    // ===============================
    // MAIN LOOP
    // ===============================
    private fun mainLoop() {
        PLUGINS_DIR.listFiles()?.forEach { plugin ->
            if (!plugin.isDirectory) return@forEach

            val name = plugin.name
            val bin = File(plugin, "system/bin")
            val fsData = File(plugin, "post-fs-data.sh")
            val sProp = File(plugin, "system.prop")
            val service = File(plugin, "service.sh")
            val uninstall = File(plugin, "uninstall.sh")

            val disable = File(plugin, "disable")
            val remove = File(plugin, "remove")
            val update = File(plugin, "update")

            when {
                remove.exists() -> {
                    println("- Disable and uninstalling $name")
                    stopPlugin(name, bin)
                    uninstallPlugin(name, uninstall, bin)
                    return@forEach
                }

                disable.exists() -> {
                    println("- Disable $name")
                    stopPlugin(name, bin)
                    return@forEach
                }

                update.exists() -> {
                    println("- Updating $name")
                    stopPlugin(name, bin)
                    if (update.delete()) {
                        println(" - Update $name Complete")
                    } else {
                        println(" - Failed to update $name")
                    }
                }

                isServiceRunning(name) -> {
                    val pid = SystemProp.get("log.tag.service.$name")
                    println("- $name:$pid is already running, skip.")
                    return@forEach
                }
            }

            println("- Starting $name")
            startPlugin(name, fsData, sProp, service, bin)
        }
    }

    // ===============================
    // START PLUGIN (subshell + &)
    // ===============================
    private fun isStandalone(script: File): Boolean {
        if (!script.exists()) return false
        return script.useLines { lines ->
            lines.any { it.trim() == "ASH_STANDALONE=1" }
        }
    }

    private fun startPlugin(
        name: String,
        fsData: File,
        sProp: File,
        service: File,
        bin: File
    ) {
        if (bin.exists()) {
            linkBin(bin)
        }
        if (fsData.exists()) {
            postFsData(name, fsData)
        }
        if (sProp.exists()) {
            applySetprop(name, sProp)
        }
        if (service.exists()) {
            startService(name, service)
        }
    }

    // ===============================
    // SERVICE (setsid + echo $$ + exec)
    // ===============================

    private fun logPipe(tag: String): String {
//        return if (DEBUG)
//            "2>&1 | busybox log -t $tag"
//        else
        return ">/dev/null 2>&1"
    }

    private fun applySetprop(name: String, sProp: File) {
        val tag = "log.tag.props.$name"

        if (SystemProp.get(tag) != "-1") {
            SystemProp.set("-f", sProp.absolutePath)
            SystemProp.set(tag, "1")
            println(" - applySetprop $name")
        }
    }

    private fun postFsData(name: String, fsData: File) {
        val tag = "log.tag.fs.$name"
        val standalone = isStandalone(fsData)

        if (SystemProp.get(tag) != "1") {
            runScript(fsData.absolutePath, standalone)
            SystemProp.set(tag, "1")
            println(" - postExecuted $name")
        }
    }

    private fun startService(name: String, service: File) {
        val tag = "axeron.plugin.$name"
        val log = logPipe(tag)
        val standalone = isStandalone(service)

        val shell = Tools.word("sh")
        val setsid = Tools.word("setsid")
        val innerShell = if (standalone) "busybox sh -o standalone" else shell
        val execLine = "exec $innerShell \"${service.absolutePath}\""

        val script = $$"""
            $$setsid $$shell -c '
              $$execLine
            ' $$log &
            pid=$!
            resetprop log.tag.service.$$name "$pid"
        """.trimIndent()

        println(" - startService $name")
        launchShell(script)
    }


    // ===============================
    // STOP / UNINSTALL
    // ===============================
    private fun stopPlugin(name: String, bin: File) {
        SystemProp.set("log.tag.fs.$name", "0")
        SystemProp.set("log.tag.props.$name", "0")

        val pid = SystemProp.get("log.tag.service.$name")
        if (pid.isNotBlank() && pid != "-1") {
            println(" - try to stopping service $name:-$pid")
            pid.toIntOrNull()?.let { killGroup(it) }
        }

        runApplet("pkill", "-f", name)
        SystemProp.set("log.tag.service.$name", "-1")

        unlinkBin(bin)
    }

    private fun uninstallPlugin(name: String, uninstall: File, bin: File) {
        if (uninstall.exists()) {
            runScript(uninstall.absolutePath, isStandalone(uninstall))
        }
        unlinkBin(bin)
        File("$AXERONDIR/plugins/$name").deleteRecursively()
    }

    // ===============================
    // LINK BIN
    // ===============================
    private fun linkBin(bin: File) {
        if (!bin.isDirectory) return

        AXERONXBIN.mkdirs()
        bin.listFiles()?.forEach { src ->
            val dst = File(AXERONXBIN, src.name)

            try {
                if (dst.exists()) {
                    if (!dst.delete()) {
                        println(" - Failed to remove: ${dst.absolutePath}")
                    }
                }
                // buat symlink baru
                Os.symlink(src.absolutePath, dst.absolutePath)
                println(" - Linked : ${dst.absolutePath}")
            } catch (_: ErrnoException) {
                println(" - Failed Linking : ${src.absolutePath}")
            }
        }
    }

    private fun unlinkBin(bin: File) {
        if (!bin.exists()) return
        bin.listFiles()?.forEach { src ->
            val dst = File(AXERONXBIN, src.name)
            if (!dst.exists()) return@forEach
            if (Os.readlink(dst.absolutePath) == src.absolutePath) {
                println(" - Unlinked : ${dst.absolutePath}")
                File(AXERONXBIN, src.name).delete()
            }
        }
    }

    // ===============================
    // UTIL
    // ===============================
    private fun cleanUpdateDir() {
        PLUGINS_UPDATE_DIR.listFiles()?.forEach {
            if (it.isDirectory) it.deleteRecursively()
        }
    }

    private fun isServiceRunning(name: String): Boolean {
        val pid = SystemProp.get("log.tag.service.$name")
        return (pid.isNotBlank() && pid != "-1")
                || (pid.isNotBlank() && File("/proc/$pid").exists())
                || runApplet("pgrep", "-f", name) == 0
    }

    private object Tools {
        private val SYSTEM_DIRS = arrayOf("/system/bin", "/system/xbin", "/vendor/bin")
        private val cache = HashMap<String, String?>()

        @Synchronized
        fun system(applet: String): String? {
            if (cache.containsKey(applet)) return cache[applet]
            val found = SYSTEM_DIRS.asSequence()
                .map { File(it, applet) }
                .firstOrNull { it.canExecute() && !isBusybox(it) }
                ?.absolutePath
            cache[applet] = found
            return found
        }

        fun word(applet: String): String = system(applet) ?: "busybox $applet"

        private fun isBusybox(file: File): Boolean = try {
            file.canonicalFile.name.startsWith("busybox")
        } catch (_: Exception) {
            false
        }
    }

    private fun runApplet(applet: String, vararg args: String): Int {
        val system = Tools.system(applet)
        if (system != null) {
            val code = execWait(arrayOf(system, *args))
            if (code != -1) return code
        }
        return execWait(arrayOf("busybox", applet, *args))
    }

    private fun runScript(path: String, standalone: Boolean): Int =
        if (standalone) execWait(arrayOf("busybox", "sh", "-o", "standalone", path))
        else runApplet("sh", path)

    private fun launchShell(script: String) {
        val system = Tools.system("sh")
        if (system != null) {
            try {
                Runtime.getRuntime().exec(arrayOf(system, "-c", script))
                return
            } catch (_: Exception) {
            }
        }
        exec(arrayOf("busybox", "sh", "-c", script))
    }

    private fun killGroup(pid: Int) {
        try {
            Os.kill(-pid, OsConstants.SIGTERM)
        } catch (_: ErrnoException) {
        }
    }

    private fun execWait(cmd: Array<String>): Int =
        try {
            Runtime.getRuntime().exec(cmd).waitFor()
        } catch (_: Exception) {
            -1
        }

    private fun exec(cmd: Array<String>) {
        try {
            Runtime.getRuntime().exec(cmd)
        } catch (_: Exception) {
            println("ERROR: $cmd")
        }
    }

}