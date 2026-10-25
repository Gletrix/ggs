import re

with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    content = f.read()

# 1. State Persistence setup
prefs_init = """    val scrollState = rememberScrollState()

    val keyStorageManager = remember { AdbKeyStorageManager(context) }"""

prefs_init_repl = """    val scrollState = rememberScrollState()
    val prefs = context.getSharedPreferences("console_mode_prefs", android.content.Context.MODE_PRIVATE)

    val keyStorageManager = remember { AdbKeyStorageManager(context) }"""
content = content.replace(prefs_init, prefs_init_repl)

states_init = """    var isConsoleModeOn by remember { mutableStateOf(false) }
    var selectedGamePackage by remember { mutableStateOf<String?>(null) }"""

states_init_repl = """    var isConsoleModeOn by remember { mutableStateOf(prefs.getBoolean("IS_CONSOLE_MODE_ACTIVE", false)) }
    var selectedGamePackage by remember { mutableStateOf<String?>(prefs.getString("SELECTED_GAME_PACKAGE", null)) }"""
content = content.replace(states_init, states_init_repl)

launch_effect = """            withContext(Dispatchers.Main) {
                installedGames = games
                if (games.isNotEmpty()) {
                    selectedGamePackage = games[0].second
                }
            }"""

launch_effect_repl = """            withContext(Dispatchers.Main) {
                installedGames = games
                val savedPkg = prefs.getString("SELECTED_GAME_PACKAGE", null)
                if (savedPkg != null && games.any { it.second == savedPkg }) {
                    selectedGamePackage = savedPkg
                } else if (games.isNotEmpty() && selectedGamePackage == null) {
                    selectedGamePackage = games[0].second
                    prefs.edit().putString("SELECTED_GAME_PACKAGE", games[0].second).apply()
                }
            }"""
content = content.replace(launch_effect, launch_effect_repl)

# 2. Add log callback and prefs save in toggleConsoleMode
enable_call = """                if (enable) {
                    val result = sessionManager.enableConsoleMode(connectHost, connectPort, selectedPackage!!)
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isConsoleModeOn = true
                            currentStep = "Console Mode Enabled"
                            executionSuccess = true

                        }"""

enable_call_repl = """                if (enable) {
                    val result = sessionManager.enableConsoleMode(connectHost, connectPort, selectedPackage!!) { logMsg ->
                        coroutineScope.launch(Dispatchers.Main) { addLog(logMsg) }
                    }
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isConsoleModeOn = true
                            prefs.edit().putBoolean("IS_CONSOLE_MODE_ACTIVE", true).apply()
                            currentStep = "Console Mode Enabled"
                            executionSuccess = true

                        }"""
content = content.replace(enable_call, enable_call_repl)

disable_call = """                } else {
                    val result = sessionManager.disableConsoleMode(connectHost, connectPort)
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isConsoleModeOn = false
                            currentStep = "Console Mode Disabled"
                            executionSuccess = true

                        }"""

disable_call_repl = """                } else {
                    val result = sessionManager.disableConsoleMode(connectHost, connectPort) { logMsg ->
                        coroutineScope.launch(Dispatchers.Main) { addLog(logMsg) }
                    }
                    if (result.isSuccess) {
                        withContext(Dispatchers.Main) {
                            isConsoleModeOn = false
                            prefs.edit().putBoolean("IS_CONSOLE_MODE_ACTIVE", false).apply()
                            currentStep = "Console Mode Disabled"
                            executionSuccess = true

                        }"""
content = content.replace(disable_call, disable_call_repl)


# 3. GameSelector pref save
game_sel = """                GameSelector(
                    installedGames = installedGames,
                    selectedGamePackage = selectedGamePackage,
                    onGameSelected = { selectedGamePackage = it }
                )"""

game_sel_repl = """                GameSelector(
                    installedGames = installedGames,
                    selectedGamePackage = selectedGamePackage,
                    onGameSelected = { 
                        selectedGamePackage = it 
                        prefs.edit().putString("SELECTED_GAME_PACKAGE", it).apply()
                    }
                )"""
content = content.replace(game_sel, game_sel_repl)

# 4. Status Badge and Log UI changes
console_controls = """                Text("CONSOLE MODE CONTROLS", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))"""

console_controls_repl = """                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CONSOLE MODE CONTROLS", style = MaterialTheme.typography.labelLarge)
                    androidx.compose.material3.Badge(
                        containerColor = if (isConsoleModeOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        contentColor = if (isConsoleModeOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary
                    ) {
                        Text(
                            text = if (isConsoleModeOn) "CONSOLE MODE: ACTIVE" else "STOCK MODE: ACTIVE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))"""
content = content.replace(console_controls, console_controls_repl)


exec_logs = """                    Text(
                        text = "Execution Logs",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )"""

exec_logs_repl = """                    Text(
                        text = "ADB Command Log",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )"""
content = content.replace(exec_logs, exec_logs_repl)

log_box = """                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp)
                ) {"""

log_box_repl = """                val logScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(10.dp)
                        .verticalScroll(logScrollState)
                ) {"""
content = content.replace(log_box, log_box_repl)


with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(content)
