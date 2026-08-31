package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSelector(
    installedGames: List<Pair<String, String>>,
    selectedGamePackage: String?,
    onGameSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedGameName = installedGames.find { it.second == selectedGamePackage }?.first ?: "Select Game"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = selectedGameName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Selected Game") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            installedGames.forEach { game ->
                DropdownMenuItem(
                    text = { Text(game.first) },
                    onClick = {
                        onGameSelected(game.second)
                        expanded = false
                    }
                )
            }
        }
    }
}
