package com.openminis.rclone.gomobile

interface LogSink {
    fun line(line: String)
}

class RcloneRPCResult(
    var status: Long = 200L,
    var output: String = "{}"
)

object Gomobile {
    @JvmStatic
    fun rcloneInitialize() {}

    @JvmStatic
    fun rcloneFinalize() {}

    @JvmStatic
    fun rcloneSetSocks(socks: String): String =
        """{"ok":true,"result":{"changed":false}}"""

    @JvmStatic
    fun rcloneRPC(method: String, input: String): RcloneRPCResult {
        val responseJson = when (method) {
            "core/version" -> """{"version":"v1.68.0","decomposed":[1,68,0],"isGit":false,"isBeta":false,"os":"android","arch":"arm64","goVersion":"go1.23.0"}"""
            "config/listremotes" -> """{"remotes":[]}"""
            "config/dump" -> """{}"""
            "operations/list" -> """{"list":[]}"""
            "operations/about" -> """{"total":0,"used":0,"trashed":0,"other":0,"free":0}"""
            "operations/stat" -> """{"item":null}"""
            "job/list" -> """{"jobids":[]}"""
            else -> """{}"""
        }
        return RcloneRPCResult(200L, responseJson)
    }

    @JvmStatic
    fun tsSetLogger(sink: LogSink?) {}

    @JvmStatic
    fun tsSetDefaultRouteInterface(ifName: String) {}

    @JvmStatic
    fun tsSetInterfaces(interfacesJSON: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun tsNetworkChanged() {}

    @JvmStatic
    fun tsStart(authKey: String, hostname: String, stateDir: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun tsSetAcceptRoutes(on: Boolean): String =
        """{"ok":true}"""

    @JvmStatic
    fun tsSetExitNode(ip: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun tsStop(): String =
        """{"ok":true}"""

    @JvmStatic
    fun tsLogout(): String =
        """{"ok":true}"""

    @JvmStatic
    fun tsStatus(): String =
        """{"ok":true,"status":"stopped"}"""

    @JvmStatic
    fun tsRoutes(): String =
        """{"ok":true,"routes":[]}"""

    @JvmStatic
    fun tsDialTest(address: String, timeoutMs: Long): String =
        """{"ok":false,"error":"unavailable"}"""

    @JvmStatic
    fun netConfigure(config: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun netStop(): String =
        """{"ok":true}"""

    @JvmStatic
    fun netHookRoutes(tag: String, local: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun netHookRoutes2(tag: String, local: String, resolvers: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun netXparentStart(mapDir: String, tag: String): String =
        """{"ok":true}"""

    @JvmStatic
    fun netShellProbe(resolver: String, names: String, timeoutMs: Long): String =
        """{"ok":false,"error":"unavailable"}"""

    @JvmStatic
    fun netXparentStop(): String =
        """{"ok":true}"""

    @JvmStatic
    fun netXparentStatus(): String =
        """{"ok":true,"active":false}"""

    @JvmStatic
    fun netStatus(): String =
        """{"ok":true,"status":"idle"}"""

    @JvmStatic
    fun netHealth(): String =
        """{"ok":true,"healthy":true}"""

    @JvmStatic
    fun netSelfTest(timeoutMs: Long): String =
        """{"ok":false,"error":"unavailable"}"""

    @JvmStatic
    fun netDiagnose(timeoutMs: Long): String =
        """{"ok":true,"diagnostics":{}}"""

    @JvmStatic
    fun netDecide(tag: String, host: String): String =
        """{"ok":true,"mode":"direct"}"""

    @JvmStatic
    fun netProbe(tag: String, address: String, timeoutMs: Long): String =
        """{"ok":false,"error":"unavailable"}"""

    @JvmStatic
    fun netTestUpstream(upstream: String, target: String, timeoutMs: Long): String =
        """{"ok":false,"error":"unavailable"}"""
}
