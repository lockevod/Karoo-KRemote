package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp



data class DropdownOption(val id: Any, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KarooKeyDropdown(options: List<DropdownOption>, remotekey: String, selectedOption: DropdownOption, onSettingsChange: (DropdownOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
   // var selectedOption by remember { mutableStateOf(remoteSettings.remoteleft.label) }
   // val options = listOf(remoteSettings.remoteleft, remoteSettings.remoteright, remoteSettings.remoteup)

    Column {
        //Text(text = "Selecciona una KarooKey")
        Spacer(modifier = Modifier.height(16.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedOption.name,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Option for $remotekey") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { result->
                    DropdownMenuItem(
                        onClick = {
                            expanded = false
                            onSettingsChange(result)
                        },
                        text = { Text(result.name) }
                    )
                }
            }
        }
    }
}
