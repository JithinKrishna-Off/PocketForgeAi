package com.pocketforge.ai.data.memory

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/**
 * Shizuku user service (runs as shell/root in its own process, instantiated by the
 * Shizuku server through our app classloader). It exposes three shell operations:
 *  - dumpsysMeminfo(): "dumpsys meminfo" stdout
 *  - amKill(pkg):       "am kill <pkg>"  (cached-process kill only)
 *  - amForceStop(pkg):  "am force-stop <pkg>"
 *
 * The binder interface is hand-rolled instead of AIDL-generated (parent project keeps
 * AIDL sources out of the java tree). Both ends of the protocol live in this file, so
 * the transaction codes below are shared by the service and by [ClientProxy].
 */
class MemoryUserService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_WHO_AM_I -> {
                reply?.writeString(whoAmI())
                return true
            }
            TRANSACTION_DUMPSYS_MEMINFO -> {
                reply?.writeString(dumpsysMeminfo())
                return true
            }
            TRANSACTION_AM_KILL -> {
                val pkg = data.readString()!!
                reply?.writeInt(amKill(pkg))
                return true
            }
            TRANSACTION_AM_FORCE_STOP -> {
                val pkg = data.readString()!!
                reply?.writeInt(amForceStop(pkg))
                return true
            }
            TRANSACTION_DESTROY -> {
                destroy()
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    fun whoAmI(): String {
        val exec = exec("id")
        return "uid=${Process.myUid()} pid=${Process.myPid()} id=${exec.second.ifBlank { "unknown" }}"
    }

    fun dumpsysMeminfo(): String = exec("dumpsys", "meminfo").second

    fun amKill(pkg: String): Int = if (!isSafeShellToken(pkg)) -1 else exec("am", "kill", pkg).first

    fun amForceStop(pkg: String): Int = if (!isSafeShellToken(pkg)) -1 else exec("am", "force-stop", pkg).first

    /** Called by the Shizuku server with transaction code DESTROY when the service is unbound. */
    fun destroy() {
        System.exit(0)
    }

    private fun exec(vararg cmd: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        Pair(exit, output.take(MAX_OUTPUT_CHARS))
    }.getOrElse { Pair(-1, "exec failed: ${it.message}") }

    /** Client-side helper; wraps the raw binder received in ServiceConnection callback. */
    class ClientProxy(private val binder: IBinder) {

        fun isAlive(): Boolean = runCatching { binder.pingBinder() }.getOrDefault(false)

        fun whoAmI(): String = stringCall(TRANSACTION_WHO_AM_I)

        fun dumpsysMeminfo(): String = stringCall(TRANSACTION_DUMPSYS_MEMINFO)

        fun amKill(pkg: String): Int = intCall(TRANSACTION_AM_KILL, pkg)

        fun amForceStop(pkg: String): Int = intCall(TRANSACTION_AM_FORCE_STOP, pkg)

        private fun stringCall(code: Int): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                binder.transact(code, data, reply, 0)
                return reply.readString() ?: ""
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        private fun intCall(code: Int, pkg: String): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeString(pkg)
                binder.transact(code, data, reply, 0)
                return reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 768 * 1024

        // Own protocol codes (both ends defined above).
        const val TRANSACTION_WHO_AM_I = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_DUMPSYS_MEMINFO = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_AM_KILL = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_AM_FORCE_STOP = IBinder.FIRST_CALL_TRANSACTION + 3

        // Reserved "destroy" transaction the Shizuku server uses on unbind.
        const val TRANSACTION_DESTROY = 16777115

        const val SERVICE_TAG = "pocketforge_memory"
        const val SERVICE_VERSION = 1
        fun serviceClassName(): String = MemoryUserService::class.java.name
    }
}

/** True when [token] is a plain process/package name that is safe to pass to a shell command. */
internal fun isSafeShellToken(token: String): Boolean =
    token.isNotEmpty() && token.all { it.isLetterOrDigit() || it in "._:$[]-" } && !token.startsWith("-")
