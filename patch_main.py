import re

with open("app/src/main/java/com/example/MainActivity.kt", "r") as f:
    content = f.read()

# 1. Remove AdbOverlayService.startConsoleModeOverlay and startActivity from toggleConsoleMode
target_block = """                            AdbOverlayService.startConsoleModeOverlay(context)
                            context.packageManager.getLaunchIntentForPackage(selectedPackage)?.let {
                                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(it)
                            }"""
content = content.replace(target_block, "")

# 2. Remove AdbOverlayService.stop(context) from toggleConsoleMode (2 places)
content = content.replace("                            AdbOverlayService.stop(context)", "")
content = content.replace("                        AdbOverlayService.stop(context)", "")


# 3. Remove onExitConsoleModeCallback
target_block2 = """        AdbOverlayService.onExitConsoleModeCallback = {
            toggleConsoleMode(false, null)
        }"""
content = content.replace(target_block2, "")
content = content.replace("            AdbOverlayService.onExitConsoleModeCallback = null\n", "")

# 4. Add LAUNCH GAME button
game_selector_block = """                GameSelector(
                    installedGames = installedGames,
                    selectedGamePackage = selectedGamePackage,
                    onGameSelected = { selectedGamePackage = it }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row("""

new_game_selector_block = """                GameSelector(
                    installedGames = installedGames,
                    selectedGamePackage = selectedGamePackage,
                    onGameSelected = { selectedGamePackage = it }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        selectedGamePackage?.let { pkg ->
                            context.packageManager.getLaunchIntentForPackage(pkg)?.let { intent ->
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } ?: run {
                                Toast.makeText(context, "Cannot launch app", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedGamePackage != null
                ) {
                    Text("LAUNCH GAME")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Row("""

content = content.replace(game_selector_block, new_game_selector_block)

with open("app/src/main/java/com/example/MainActivity.kt", "w") as f:
    f.write(content)
