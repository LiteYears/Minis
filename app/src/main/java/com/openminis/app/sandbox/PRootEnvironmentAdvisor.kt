package com.openminis.app.sandbox

/**
 * Detects Android + Alpine PRoot environment limitations when shell commands fail
 * or time out, and provides actionable technical diagnostics with supported local
 * alternatives or remote Linux lab offload guidance.
 *
 * Why this exists:
 * Inside Android's unprivileged PRoot sandbox (`proot` running under the app's
 * Android UID with `-0` fake-root):
 * 1. `id -u` reports `0` (fake root), which tricks tools like `nmap`, `tcpdump`,
 *    `ping`, and `iptables` into assuming they hold Linux capabilities (`CAP_NET_RAW`,
 *    `CAP_NET_ADMIN`, `CAP_SYS_ADMIN`, `CAP_SYS_PTRACE`).
 * 2. When those tools invoke privileged kernel syscalls (`socket(AF_PACKET, ...)`,
 *    `socket(AF_INET, SOCK_RAW, ...)`, `setsockopt`, `mount`, `unshare`, or nested
 *    `ptrace`), the Android kernel rejects the syscall with `EPERM` / `EACCES`
 *    (or `ping` hangs waiting on an ICMP socket).
 * 3. Surfacing the exact kernel/PRoot cause and the supported user-space or
 *    remote-lab alternative lets the agent immediately pivot to a working command
 *    without wasting turns retrying unsupported syscalls.
 */
object PRootEnvironmentAdvisor {

    enum class ConstraintKind {
        RAW_ICMP_BLOCKED,
        RAW_SOCKET_SCAN_UNSUPPORTED,
        PACKET_CAPTURE_UNSUPPORTED,
        NESTED_PTRACE_CONFLICT,
        KERNEL_NET_OR_SYS_ADMIN_UNSUPPORTED,
    }

    data class Diagnostic(
        val kind: ConstraintKind,
        val summary: String,
        val alternative: String,
    ) {
        fun formatBlock(): String =
            "[PRoot Environment Diagnostic — ${kind.name}]\n" +
                "Limitation: $summary\n" +
                "Supported Alternative: $alternative"
    }

    private val PING_CMD_REGEX = Regex("""(^|[;&|()\s])(ping6?|fping)(\s|$)""")
    private val NMAP_CMD_REGEX = Regex("""(^|[;&|()\s])nmap(\s|$)""")
    private val RAW_SCANNER_CMD_REGEX = Regex("""(^|[;&|()\s])(masscan|zmap|arp-scan|hping3|unicornscan)(\s|$)""")
    private val PACKET_CAPTURE_CMD_REGEX = Regex("""(^|[;&|()\s])(tcpdump|tshark|dumpcap|ettercap|bettercap)(\s|$)""")
    private val PTRACE_CMD_REGEX = Regex("""(^|[;&|()\s])(strace|ltrace|gdb)(\s|$)""")
    private val NET_SYS_ADMIN_CMD_REGEX = Regex("""(^|[;&|()\s])(iptables|ip6tables|nft|mount|umount|insmod|modprobe|chroot|unshare|sysctl)(\s|$)""")
    private val IP_MUTATION_CMD_REGEX = Regex("""(^|[;&|()\s])ip\s+(link|route|addr|netns|rule)\s+(add|del|set|change|replace|flush)""")

    /**
     * Inspect a completed shell execution and return a [Diagnostic] if the command
     * failed or timed out due to an Android/PRoot kernel or capability boundary.
     */
    fun diagnose(
        command: String,
        exitCode: Int,
        output: String,
        timedOut: Boolean = exitCode == 124,
    ): Diagnostic? {
        if (exitCode == 0 && !timedOut) return null
        val cmdTrimmed = command.trim()
        if (cmdTrimmed.isEmpty()) return null
        val outLower = output.lowercase()

        // 1. Raw ICMP (ping / fping) — either timed out or failed with socket permission error
        if (PING_CMD_REGEX.containsMatchIn(cmdTrimmed) &&
            (timedOut ||
                outLower.contains("operation not permitted") ||
                outLower.contains("permission denied") ||
                outLower.contains("socket") ||
                outLower.contains("100% packet loss") ||
                exitCode != 0)
        ) {
            return Diagnostic(
                kind = ConstraintKind.RAW_ICMP_BLOCKED,
                summary = "Android's unprivileged PRoot sandbox lacks CAP_NET_RAW for raw ICMP sockets (`ping`/`fping` cannot send or receive raw ICMP echo frames).",
                alternative = "Use user-space TCP/HTTP probes locally (`curl -ILs --connect-timeout 5 https://<host>`, `nc -zv <host> <port>`, `nmap -sT -Pn -p 80,443 <host>`, or Python `socket.create_connection((host, port), timeout=5)`), or run ICMP diagnostics on a remote Linux lab host via `ssh`.",
            )
        }

        // 2. Nmap / raw-socket port scanners (fake root uid=0 causes nmap to default to -sS raw SYN scan + ICMP host discovery)
        val isNmap = NMAP_CMD_REGEX.containsMatchIn(cmdTrimmed)
        val isRawScanner = RAW_SCANNER_CMD_REGEX.containsMatchIn(cmdTrimmed)
        val hasRawScanError = outLower.contains("dnet: failed to open device") ||
            outLower.contains("socket troubles") ||
            outLower.contains("requires root privileges") ||
            outLower.contains("you need to be root") ||
            outLower.contains("couldn't open a raw socket") ||
            outLower.contains("cannot open raw socket") ||
            outLower.contains("af_packet") ||
            outLower.contains("cap_net_raw") ||
            outLower.contains("operation not permitted") ||
            outLower.contains("permission denied")

        if ((isNmap && (hasRawScanError || !cmdTrimmed.contains("-sT") || !cmdTrimmed.contains("-Pn"))) ||
            (isRawScanner && (hasRawScanError || exitCode != 0))
        ) {
            return Diagnostic(
                kind = ConstraintKind.RAW_SOCKET_SCAN_UNSUPPORTED,
                summary = "PRoot reports fake UID 0 (`-0`), causing scanners like `nmap` or `masscan` to attempt raw SYN (`-sS`), raw UDP (`-sU`), OS fingerprinting (`-O`), or raw ARP/ICMP probes that fail without kernel `CAP_NET_RAW` / `AF_PACKET`.",
                alternative = "Run unprivileged TCP connect scans with host-discovery disabled (`nmap -sT -Pn -sV --unprivileged <target>`), use `nc -zv` or an async Python `asyncio`/`socket` scanner locally, or execute raw packet scans (`-sS`/`-sU`/`-O`/`masscan`) on a remote Linux lab host via `ssh`.",
            )
        }

        // 3. Raw packet capture / L2-L3 injection (tcpdump, tshark, scapy raw, etc.)
        val isPacketCapture = PACKET_CAPTURE_CMD_REGEX.containsMatchIn(cmdTrimmed)
        val hasPacketCaptureError = outLower.contains("af_packet") ||
            outLower.contains("siocgifhwaddr") ||
            outLower.contains("you don't have permission to capture") ||
            outLower.contains("socket: operation not permitted") ||
            outLower.contains("pcap_activate") ||
            outLower.contains("no suitable device found")

        if (isPacketCapture || hasPacketCaptureError) {
            return Diagnostic(
                kind = ConstraintKind.PACKET_CAPTURE_UNSUPPORTED,
                summary = "Raw packet capture (`AF_PACKET` / `libpcap`) requires `CAP_NET_RAW` in the host Android kernel and is unavailable inside unprivileged PRoot.",
                alternative = "Inspect application-layer traffic with `curl -v --trace-ascii -`, `openssl s_client -connect <host>:<port>`, `mitmproxy`, or `/proc/net/tcp` & `/proc/net/udp` locally; use `android-shizuku-cli` if privileged on-device shell access is authorized; or run `tcpdump`/`tshark` on a remote Linux lab host via `ssh`.",
            )
        }

        // 4. Nested ptrace conflict (strace, ltrace, gdb live process tracing)
        val isPtraceTool = PTRACE_CMD_REGEX.containsMatchIn(cmdTrimmed)
        val hasPtraceError = outLower.contains("ptrace") &&
            (outLower.contains("operation not permitted") ||
                outLower.contains("function not implemented") ||
                outLower.contains("no such process") ||
                outLower.contains("cannot attach") ||
                outLower.contains("could not attach") ||
                outLower.contains("traceme"))

        if ((isPtraceTool && hasPtraceError) || hasPtraceError) {
            return Diagnostic(
                kind = ConstraintKind.NESTED_PTRACE_CONFLICT,
                summary = "PRoot itself relies on `ptrace` to intercept guest syscalls on Android, which prevents guest tools (`strace`, `ltrace`, `gdb` live run/attach) from attaching a second `ptrace` tracer inside PRoot.",
                alternative = "Perform static & disassembly analysis locally (`radare2 -A`, `rizin`, `objdump -d`, `readelf -a`, `nm -D`, `strings`), use `LD_PRELOAD` shared-library interposition or Python `unicorn`/`capstone`/`angr` emulation for dynamic behavior, or run `strace`/`gdb` via `android-shizuku-cli` (when granted) or on a remote Linux lab host via `ssh`.",
            )
        }

        // 5. Kernel netlink / firewall / mount / sysctl / namespace operations
        val isNetSysAdminCmd = NET_SYS_ADMIN_CMD_REGEX.containsMatchIn(cmdTrimmed) ||
            IP_MUTATION_CMD_REGEX.containsMatchIn(cmdTrimmed)
        val hasNetSysAdminError = outLower.contains("rtnetlink answers: operation not permitted") ||
            outLower.contains("rtnetlink answers: permission denied") ||
            outLower.contains("nf_tables") ||
            outLower.contains("can't initialize iptables table") ||
            outLower.contains("mount: permission denied") ||
            outLower.contains("must be superuser to use mount") ||
            outLower.contains("unshare failed: operation not permitted")

        if ((isNetSysAdminCmd && (hasNetSysAdminError || outLower.contains("operation not permitted") || outLower.contains("permission denied"))) ||
            hasNetSysAdminError
        ) {
            return Diagnostic(
                kind = ConstraintKind.KERNEL_NET_OR_SYS_ADMIN_UNSUPPORTED,
                summary = "Kernel network stack mutations (`iptables`, `nft`, `ip route/link`), filesystem `mount`, and kernel namespaces (`unshare`/`chroot`) require host `CAP_NET_ADMIN` / `CAP_SYS_ADMIN` and are blocked by Android's kernel in unprivileged PRoot.",
                alternative = "Read network state via `/proc/net/tcp`, `/proc/net/udp`, `/proc/net/route`, or `minis-config get network`; mount user directories via Settings → Mount External Folders (`/var/minis/mounts/<name>`); or execute kernel-level network/container operations on a remote Linux lab host via `ssh`.",
            )
        }

        return null
    }

    /**
     * Appends a formatted `[PRoot Environment Diagnostic]` note to [output] when
     * [diagnose] identifies an Android/PRoot capability boundary.
     */
    fun appendDiagnosticIfApplicable(
        command: String,
        exitCode: Int,
        output: String,
        timedOut: Boolean = exitCode == 124,
    ): String {
        val diag = diagnose(command, exitCode, output, timedOut) ?: return output
        val block = diag.formatBlock()
        return if (output.isBlank()) block else "$output\n\n$block"
    }
}
