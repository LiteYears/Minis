package com.openminis.app.sandbox

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * JNI bridge to bionic libc's forkpty() + termios + waitpid, with a resilient
 * pure-JVM ProcessBuilder fallback when the native library is unavailable on
 * non-ARM64 host architectures.
 */
object PtyBridge {
    private const val TAG = "PtyBridge"
    @Volatile
    private var isLoaded = false

    private const val FALLBACK_ID_BASE = 100_000
    private val nextFallbackFd = AtomicInteger(FALLBACK_ID_BASE)
    private val fallbackProcesses = ConcurrentHashMap<Int, Process>()

    init {
        try {
            System.loadLibrary("pty_bridge")
            isLoaded = true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "pty_bridge not loaded, using ProcessBuilder fallback: ${t.message}")
            isLoaded = false
        }
    }

    @JvmStatic
    fun forkExec(
        cmd: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        cols: Int,
        rows: Int,
        outPid: IntArray
    ): Int {
        if (isLoaded) {
            try {
                val fd = nativeForkExec(cmd, argv, envp, cwd, cols, rows, outPid)
                if (fd >= 0) return fd
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "nativeForkExec failed, falling back to ProcessBuilder: ${t.message}")
            }
        }
        return try {
            val commandList = if (argv.isNotEmpty()) argv.toList() else listOf(cmd)
            val pb = ProcessBuilder(commandList)
            pb.redirectErrorStream(true)
            if (!cwd.isNullOrEmpty()) {
                val dir = File(cwd)
                if (dir.exists() && dir.isDirectory) {
                    pb.directory(dir)
                }
            }
            val env = pb.environment()
            for (entry in envp) {
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    env[entry.substring(0, eq)] = entry.substring(eq + 1)
                }
            }
            env["COLUMNS"] = cols.toString()
            env["LINES"] = rows.toString()
            val proc = pb.start()
            val fd = nextFallbackFd.getAndIncrement()
            fallbackProcesses[fd] = proc
            outPid[0] = fd
            fd
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "ProcessBuilder fallback forkExec failed", t)
            -1
        }
    }

    /** Read from PTY master. Returns bytes read, 0 on EOF, or -errno. */
    @JvmStatic
    fun readBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int {
        if (fd >= FALLBACK_ID_BASE) {
            val fallbackProc = fallbackProcesses[fd] ?: return 0
            return try {
                val n = fallbackProc.inputStream.read(buf, off, len)
                if (n < 0) 0 else n
            } catch (_: Throwable) {
                0
            }
        }
        if (!isLoaded) return -1
        return try {
            nativeReadBytes(fd, buf, off, len)
        } catch (_: Throwable) {
            -1
        }
    }

    /** Write to PTY master. Returns bytes written, or -errno. Blocks until all len bytes are written. */
    @JvmStatic
    fun writeBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int {
        if (fd >= FALLBACK_ID_BASE) {
            val fallbackProc = fallbackProcesses[fd] ?: return -1
            return try {
                // Non-PTY ProcessBuilder stdin expects LF ('\n') instead of CR ('\r')
                val translated = ByteArray(len)
                for (i in 0 until len) {
                    val b = buf[off + i]
                    translated[i] = if (b == '\r'.code.toByte()) '\n'.code.toByte() else b
                }
                val out = fallbackProc.outputStream
                out.write(translated, 0, len)
                out.flush()
                len
            } catch (_: Throwable) {
                -1
            }
        }
        if (!isLoaded) return -1
        return try {
            nativeWriteBytes(fd, buf, off, len)
        } catch (_: Throwable) {
            -1
        }
    }

    /** ioctl(TIOCSWINSZ). Returns 0 or -errno. */
    @JvmStatic
    fun setWindowSize(fd: Int, cols: Int, rows: Int): Int {
        if (fd >= FALLBACK_ID_BASE) return 0
        if (!isLoaded) return 0
        return try {
            nativeSetWindowSize(fd, cols, rows)
        } catch (_: Throwable) {
            0
        }
    }

    /** Close the PTY master fd. Returns 0 or -errno. */
    @JvmStatic
    fun closeFd(fd: Int): Int {
        if (fd >= FALLBACK_ID_BASE) {
            val fallbackProc = fallbackProcesses[fd] ?: return 0
            runCatching { fallbackProc.inputStream.close() }
            runCatching { fallbackProc.outputStream.close() }
            if (!fallbackProc.isAlive) {
                fallbackProcesses.remove(fd)
            }
            return 0
        }
        if (!isLoaded) return 0
        return try {
            nativeCloseFd(fd)
        } catch (_: Throwable) {
            -1
        }
    }

    /** kill(pid, sig). Returns 0 or -errno. */
    @JvmStatic
    fun sendSignal(pid: Int, sig: Int): Int {
        if (pid >= FALLBACK_ID_BASE) {
            val fallbackProc = fallbackProcesses[pid] ?: return 0
            runCatching {
                if (sig == 9) fallbackProc.destroyForcibly() else fallbackProc.destroy()
            }
            if (!fallbackProc.isAlive) {
                fallbackProcesses.remove(pid)
            }
            return 0
        }
        if (!isLoaded) return 0
        return try {
            nativeSendSignal(pid, sig)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * waitpid(pid, &status, 0). Blocks.
     * Returns WEXITSTATUS on normal exit, -(128+sig) on signal, -errno on failure.
     */
    @JvmStatic
    fun waitFor(pid: Int): Int {
        if (pid >= FALLBACK_ID_BASE) {
            val fallbackProc = fallbackProcesses[pid] ?: return 0
            return try {
                fallbackProc.waitFor()
            } catch (_: Throwable) {
                0
            } finally {
                fallbackProcesses.remove(pid)
            }
        }
        if (!isLoaded) return -1
        return try {
            nativeWaitFor(pid)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * [T-shell-stall-yes-head] Unblock SIGPIPE on the calling thread. Returns
     * a bit set (1 = it was blocked, 2 = its disposition is SIG_IGN) or -errno.
     * Use through [SpawnSignalMask], never directly.
     */
    @JvmStatic
    fun unblockSigpipe(): Int {
        if (!isLoaded) return 0
        return try {
            nativeUnblockSigpipe()
        } catch (t: Throwable) {
            0
        }
    }

    /** [T-shell-stall-yes-head] Re-block SIGPIPE on the calling thread. 0 or -errno. */
    @JvmStatic
    fun blockSigpipe(): Int {
        if (!isLoaded) return 0
        return try {
            nativeBlockSigpipe()
        } catch (t: Throwable) {
            0
        }
    }

    @JvmStatic private external fun nativeForkExec(cmd: String, argv: Array<String>, envp: Array<String>, cwd: String?, cols: Int, rows: Int, outPid: IntArray): Int
    @JvmStatic private external fun nativeReadBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    @JvmStatic private external fun nativeWriteBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    @JvmStatic private external fun nativeSetWindowSize(fd: Int, cols: Int, rows: Int): Int
    @JvmStatic private external fun nativeCloseFd(fd: Int): Int
    @JvmStatic private external fun nativeSendSignal(pid: Int, sig: Int): Int
    @JvmStatic private external fun nativeWaitFor(pid: Int): Int
    @JvmStatic private external fun nativeUnblockSigpipe(): Int
    @JvmStatic private external fun nativeBlockSigpipe(): Int
}
