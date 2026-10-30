package com.openminis.app.ui.sandbox

import android.content.Context
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsInstallState
import com.openminis.app.sandbox.RootfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SandboxDiagnosticCheck(
    val title: String,
    val detail: String,
    val passed: Boolean,
)

data class RootfsManagementUiState(
    val isInstalled: Boolean = false,
    val isProcessing: Boolean = false,
    val statusMessage: String = "",
    val resultMessage: String? = null,
    val lastOperationSuccess: Boolean = false,
    val rootfsSize: Long = 0L,
    val rootfsPath: String = "",
    val hasBackup: Boolean = false,
    /** Current install phase + 0..1 progress (null when not installing). */
    val installProgress: Float? = null,
    val deviceArch: String = "aarch64",
    val isProotAvailable: Boolean = true,
    val activeMirrorUrl: String = "",
    val dirBreakdown: List<Pair<String, Long>> = emptyList(),
    val isRunningDiagnostics: Boolean = false,
    val diagnosticsResults: List<SandboxDiagnosticCheck> = emptyList(),
)

class RootfsManagementViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(RootfsManagementUiState())
    val uiState: StateFlow<RootfsManagementUiState> = _uiState.asStateFlow()

    private var backupDir: File? = null
    private var progressJob: Job? = null

    private fun observeInstallProgress(manager: RootfsManager) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            manager.installState.collect { state ->
                when (state) {
                    is RootfsInstallState.Idle -> Unit
                    is RootfsInstallState.Preparing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Preparing rootfs…",
                            installProgress = 0f,
                        )
                    is RootfsInstallState.Downloading ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Downloading rootfs… ${(state.progress * 100).toInt()}%",
                            installProgress = state.progress,
                        )
                    is RootfsInstallState.Extracting ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Extracting rootfs… ${(state.progress * 100).toInt()}%",
                            installProgress = state.progress,
                        )
                    is RootfsInstallState.Finalizing ->
                        _uiState.value = _uiState.value.copy(
                            statusMessage = "Finalizing…",
                            installProgress = 1f,
                        )
                    is RootfsInstallState.Installed,
                    is RootfsInstallState.Failed -> {
                        _uiState.value = _uiState.value.copy(installProgress = null)
                        progressJob?.cancel()
                    }
                }
            }
        }
    }

    fun refresh(context: Context) {
        val manager = RootfsManager.getInstance(context)

        _uiState.value = _uiState.value.copy(
            isInstalled = manager.isInstalled,
            rootfsPath = manager.rootfsDir.absolutePath,
            deviceArch = manager.detectDeviceArch(),
            isProotAvailable = manager.isProotAvailable,
            activeMirrorUrl = manager.getActiveAlpineMirror(),
        )

        if (manager.isInstalled) {
            viewModelScope.launch {
                try {
                    val size = manager.getRootfsSize()
                    val breakdown = withContext(Dispatchers.IO) {
                        val targets = listOf(
                            "/root" to File(manager.rootfsDir, "root"),
                            "/var/minis/workspace" to File(manager.rootfsDir, "var/minis/workspace"),
                            "/var/minis/skills" to File(manager.rootfsDir, "var/minis/skills"),
                            "/var/minis/memory" to File(manager.rootfsDir, "var/minis/memory"),
                            "/var/minis/shared" to File(manager.rootfsDir, "var/minis/shared"),
                        )
                        targets.map { (label, dir) ->
                            label to dirSize(dir)
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        rootfsSize = size,
                        dirBreakdown = breakdown,
                    )
                } catch (_: Exception) { }
            }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val curr = stack.removeLast()
            val children = curr.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    stack.add(c)
                } else {
                    total += c.length()
                }
            }
        }
        return total
    }

    fun runDiagnostics(context: Context) {
        if (_uiState.value.isRunningDiagnostics) return
        _uiState.value = _uiState.value.copy(
            isRunningDiagnostics = true,
            diagnosticsResults = emptyList(),
        )
        val manager = RootfsManager.getInstance(context)
        viewModelScope.launch {
            val checks = withContext(Dispatchers.IO) {
                val list = mutableListOf<SandboxDiagnosticCheck>()

                // 1. Rootfs filesystem integrity
                val shExists = File(manager.rootfsDir, "bin/sh").exists() ||
                    File(manager.rootfsDir, "bin/busybox").exists()
                val minisDirExists = File(manager.rootfsDir, "var/minis/workspace").exists()
                val fsOk = manager.isInstalled && (shExists || minisDirExists)
                list.add(
                    SandboxDiagnosticCheck(
                        title = "Alpine Rootfs Integrity",
                        detail = if (fsOk) "Verified (/bin/sh & /var/minis ready)"
                        else if (!manager.isInstalled) "Rootfs not installed yet"
                        else "Missing core directories in ${manager.rootfsDir.name}",
                        passed = fsOk,
                    )
                )

                // 2. Execution Engine (PRoot / Host Shell)
                val prootOk = manager.isProotAvailable
                val loaderFile = File(manager.nativeLibDir, "libproot-loader.so")
                list.add(
                    SandboxDiagnosticCheck(
                        title = "Sandbox Execution Engine",
                        detail = if (prootOk) {
                            "Native PRoot ready (loader=${if (loaderFile.exists()) "ok" else "missing"})"
                        } else {
                            "Host Shell fallback active (/system/bin/sh)"
                        },
                        passed = true,
                    )
                )

                // 3. DNS & Repositories Configuration
                val resolvFile = File(manager.rootfsDir, "etc/resolv.conf")
                val nameservers = if (resolvFile.exists()) {
                    resolvFile.readLines()
                        .map { it.trim() }
                        .filter { it.startsWith("nameserver ") }
                        .map { it.removePrefix("nameserver ").trim() }
                } else emptyList()
                list.add(
                    SandboxDiagnosticCheck(
                        title = "Container DNS & APK Mirror",
                        detail = if (nameservers.isNotEmpty()) {
                            "DNS: ${nameservers.joinToString(", ")} · Mirror: ${manager.getActiveAlpineMirror()}"
                        } else {
                            "No nameserver in /etc/resolv.conf (will refresh on boot)"
                        },
                        passed = nameservers.isNotEmpty() || !manager.isInstalled,
                    )
                )

                // 4. Live Shell Probe
                val t0 = System.currentTimeMillis()
                val probeResult = runCatching {
                    val cmd = if (manager.isInstalled && PRootKernel.isBooted) {
                        PRootKernel.buildProotCommand("echo MINIS_OK && uname -m")
                    } else {
                        listOf("/system/bin/sh", "-c", "echo MINIS_OK && uname -m")
                    }
                    val pb = ProcessBuilder(cmd)
                    pb.redirectErrorStream(true)
                    if (!manager.isProotAvailable) {
                        pb.directory(File(manager.rootfsDir, "root").apply { mkdirs() })
                    } else {
                        val env = pb.environment()
                        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
                        if (PRootKernel.nativeLibDir.isNotEmpty()) env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
                        if (PRootKernel.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
                        if (PRootKernel.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
                    }
                    val proc = pb.start()
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    out
                }.getOrNull()
                val elapsedMs = System.currentTimeMillis() - t0
                val probeOk = probeResult?.contains("MINIS_OK") == true
                val archLine = probeResult?.lines()?.lastOrNull()?.trim().orEmpty()
                list.add(
                    SandboxDiagnosticCheck(
                        title = "Shell Round-Trip Probe",
                        detail = if (probeOk) "Passed in ${elapsedMs}ms (arch: $archLine)"
                        else "Probe output: ${probeResult?.take(80) ?: "failed"}",
                        passed = probeOk,
                    )
                )
                list
            }
            _uiState.value = _uiState.value.copy(
                isRunningDiagnostics = false,
                diagnosticsResults = checks,
            )
        }
    }

    fun cleanSandboxCache(context: Context) {
        val manager = RootfsManager.getInstance(context)
        if (!manager.isInstalled || _uiState.value.isProcessing) return
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Cleaning sandbox temp & cache...",
            resultMessage = null,
        )
        viewModelScope.launch {
            val freedBytes = withContext(Dispatchers.IO) {
                val targets = listOf(
                    File(manager.rootfsDir, "tmp"),
                    File(manager.rootfsDir, "var/cache/apk"),
                    File(manager.rootfsDir, ".minis-exit"),
                )
                var freed = 0L
                for (dir in targets) {
                    if (dir.exists()) {
                        freed += dirSize(dir)
                        dir.listFiles()?.forEach { child ->
                            runCatching { child.deleteRecursively() }
                        }
                    }
                }
                freed
            }
            val formatted = Formatter.formatFileSize(context, freedBytes)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                lastOperationSuccess = true,
                resultMessage = "Cleaned temporary files and APK cache ($formatted reclaimed)",
            )
            refresh(context)
        }
    }

    fun install(context: Context) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Installing rootfs...",
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(context)
        observeInstallProgress(manager)
        viewModelScope.launch {
            try {
                manager.installIfNeeded()
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    resultMessage = "Rootfs installed successfully",
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Installation failed: ${e.message}",
                    installProgress = null,
                )
            }
        }
    }

    fun resetRootfs(context: Context, keepUserData: Boolean) {
        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = if (keepUserData) "Backing up and resetting..." else "Resetting rootfs...",
            resultMessage = null,
            installProgress = 0f,
        )

        val manager = RootfsManager.getInstance(context)
        observeInstallProgress(manager)
        viewModelScope.launch {
            try {
                val backup = manager.reset(keepUserData)

                backupDir = backup
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    hasBackup = backup != null && backup.exists(),
                    resultMessage = if (keepUserData) {
                        "Rootfs reset with backup created"
                    } else {
                        "Rootfs reset complete"
                    },
                    installProgress = null,
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Reset failed: ${e.message}",
                    installProgress = null,
                )
            }
        }
    }

    fun restoreBackup(context: Context) {
        val backup = backupDir
        if (backup == null || !backup.exists()) {
            _uiState.value = _uiState.value.copy(
                resultMessage = "No backup available",
                lastOperationSuccess = false,
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isProcessing = true,
            statusMessage = "Restoring user data...",
            resultMessage = null,
        )

        val manager = RootfsManager.getInstance(context)
        viewModelScope.launch {
            try {
                manager.restoreUserData(backup)

                backupDir = null
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = true,
                    hasBackup = false,
                    resultMessage = "User data restored successfully",
                )
                refresh(context)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    lastOperationSuccess = false,
                    resultMessage = "Restore failed: ${e.message}",
                )
            }
        }
    }
}
