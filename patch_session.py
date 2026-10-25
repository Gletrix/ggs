import re

with open("app/src/main/java/com/example/adb/AdbSessionManager.kt", "r") as f:
    content = f.read()

target1 = """    suspend fun enableConsoleMode(
        host: String,
        port: Int,
        targetPackageName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val commands = listOf(
            "wm size 720x1600",
            "wm density 288",
            "settings put system peak_refresh_rate 90.0",
            "settings put system min_refresh_rate 90.0",
            "settings put global cached_apps_freezer enabled",
            "settings put global zen_mode 1"
        )
        for (cmd in commands) {
            val res = executeShellCommand(cmd, host, port)
            if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
        }
        Result.success(Unit)
    }"""

replacement1 = """    suspend fun enableConsoleMode(
        host: String,
        port: Int,
        targetPackageName: String,
        onLog: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val commands = listOf(
            "wm size 720x1600",
            "wm density 288",
            "settings put system peak_refresh_rate 90.0",
            "settings put system min_refresh_rate 90.0",
            "settings put global cached_apps_freezer enabled",
            "settings put global zen_mode 1"
        )
        commands.forEachIndexed { index, cmd ->
            val step = index + 1
            val total = commands.size
            val res = executeShellCommand(cmd, host, port)
            if (res.isSuccess) {
                val output = res.getOrNull()?.trim() ?: ""
                val outStr = if (output.isNotEmpty()) " -> OK ($output)" else " -> OK"
                onLog("[$step/$total] $cmd$outStr")
            } else {
                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                onLog("[$step/$total] $cmd -> FAILED: $err")
                return@withContext Result.failure(res.exceptionOrNull()!!)
            }
        }
        Result.success(Unit)
    }"""

target2 = """    suspend fun disableConsoleMode(
        host: String,
        port: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val commands = listOf(
            "wm size 1080x2400",
            "wm density 432",
            "settings put system min_refresh_rate 60.0",
            "settings put global zen_mode 0"
        )
        for (cmd in commands) {
            val res = executeShellCommand(cmd, host, port)
            if (res.isFailure) return@withContext Result.failure(res.exceptionOrNull()!!)
        }
        Result.success(Unit)
    }"""

replacement2 = """    suspend fun disableConsoleMode(
        host: String,
        port: Int,
        onLog: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val commands = listOf(
            "wm size 1080x2400",
            "wm density 432",
            "settings put system min_refresh_rate 60.0",
            "settings put global zen_mode 0"
        )
        commands.forEachIndexed { index, cmd ->
            val step = index + 1
            val total = commands.size
            val res = executeShellCommand(cmd, host, port)
            if (res.isSuccess) {
                val output = res.getOrNull()?.trim() ?: ""
                val outStr = if (output.isNotEmpty()) " -> OK ($output)" else " -> OK"
                onLog("[$step/$total] $cmd$outStr")
            } else {
                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                onLog("[$step/$total] $cmd -> FAILED: $err")
                return@withContext Result.failure(res.exceptionOrNull()!!)
            }
        }
        Result.success(Unit)
    }"""

content = content.replace(target1, replacement1)
content = content.replace(target2, replacement2)

with open("app/src/main/java/com/example/adb/AdbSessionManager.kt", "w") as f:
    f.write(content)
