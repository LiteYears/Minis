package com.openminis.app.sandbox

/**
 * JNI bridge to bionic libc's forkpty() + termios + waitpid.
 *
 * Enables a real TTY for the spawned proot/sh process so readline, PS1 prompt,
 * arrow keys, Ctrl+C (SIGINT) and interactive programs (vi, top, gh auth login)
 * all work — mirroring iOS ISHKernel behaviour. Without this, Android would
 * only have line-buffered stdin/stdout via ProcessBuilder, which is fundamentally
 * different from a TTY and breaks every interactive program.
 */
object PtyBridge {
    private const val TAG = "PtyBridge"
    @Volatile
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("pty_bridge")
            isLoaded = true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "pty_bridge not loaded: ${t.message}")
            isLoaded = false
        }
    }

    /**
     * Fork a child process attached to a new PTY and execve() into [cmd].
     *
     * @param cmd   absolute path to the executable (e.g. /data/data/.../libproot.so)
     * @param argv  argv[0] is conventionally the command name; include it
     * @param envp  environment strings in "KEY=VALUE" form
     * @param cwd   working directory for the child (or null)
     * @param cols  initial terminal columns
     * @param rows  initial terminal rows
     * @param outPid single-element int array receiving the child pid
     * @return PTY master fd on success, or -errno on failure
     */
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
        if (!isLoaded) return -1
        return try {
            nativeForkExec(cmd, argv, envp, cwd, cols, rows, outPid)
        } catch (t: Throwable) {
            -1
        }
    }

    /** Read from PTY master. Returns bytes read, 0 on EOF, or -errno. */
    @JvmStatic
    fun readBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int {
        if (!isLoaded) return -1
        return try {
            nativeReadBytes(fd, buf, off, len)
        } catch (t: Throwable) {
            -1
        }
    }

    /** Write to PTY master. Returns bytes written, or -errno. Blocks until all len bytes are written. */
    @JvmStatic
    fun writeBytes(fd: Int, buf: ByteArray, off: Int, len: Int): Int {
        if (!isLoaded) return -1
        return try {
            nativeWriteBytes(fd, buf, off, len)
        } catch (t: Throwable) {
            -1
        }
    }

    /** ioctl(TIOCSWINSZ). Returns 0 or -errno. */
    @JvmStatic
    fun setWindowSize(fd: Int, cols: Int, rows: Int): Int {
        if (!isLoaded) return -1
        return try {
            nativeSetWindowSize(fd, cols, rows)
        } catch (t: Throwable) {
            -1
        }
    }

    /** Close the PTY master fd. Returns 0 or -errno. */
    @JvmStatic
    fun closeFd(fd: Int): Int {
        if (!isLoaded) return -1
        return try {
            nativeCloseFd(fd)
        } catch (t: Throwable) {
            -1
        }
    }

    /** kill(pid, sig). Returns 0 or -errno. */
    @JvmStatic
    fun sendSignal(pid: Int, sig: Int): Int {
        if (!isLoaded) return -1
        return try {
            nativeSendSignal(pid, sig)
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * waitpid(pid, &status, 0). Blocks.
     * Returns WEXITSTATUS on normal exit, -(128+sig) on signal, -errno on failure.
     */
    @JvmStatic
    fun waitFor(pid: Int): Int {
        if (!isLoaded) return -1
        return try {
            nativeWaitFor(pid)
        } catch (t: Throwable) {
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
