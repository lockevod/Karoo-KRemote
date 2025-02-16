package com.enderthor.kremote.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.RemoteDevice
import com.enderthor.kremote.viewmodel.ConfigurationViewModel

@Composable
fun ConfigurationScreen(
    activeDevice: RemoteDevice?,
    errorMessage: String?,
    configViewModel: ConfigurationViewModel
) {


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        activeDevice?.let { device ->
            Text(
                text = "Configuration ${device.name}",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(16.dp))

            KeyMappingSection(
                title = "Left",
                currentKey = device.keyMappings.remoteleft,
                onKeySelected = { configViewModel.updateKeyMapping(device.id, "left", it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            KeyMappingSection(
                title = "Right",
                currentKey = device.keyMappings.remoteright,
                onKeySelected = { configViewModel.updateKeyMapping(device.id, "right", it) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            KeyMappingSection(
                title = "Up",
                currentKey = device.keyMappings.remoteup,
                onKeySelected = { configViewModel.updateKeyMapping(device.id, "up", it) }
            )
        } ?: run {
            Text(
                text = "No active devices",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        errorMessage?.let { error ->
            AlertDialog(
                onDismissRequest = { configViewModel.clearError() },
                title = { Text("Error") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { configViewModel.clearError() }) {
                        Text("Accept")
                    }
                }
            )
        }
    }
}

@Composable
fun KeyMappingSection(
    title: String,
    currentKey: KarooKey,
    onKeySelected: (KarooKey) -> Unit
) {
    Column {
        //Text(text = title)
        val options = KarooKey.entries.map {
            DropdownOption(it.name, it.label)
        }
        val selectedOption = DropdownOption(currentKey.name, currentKey.label)

        KarooKeyDropdown(
            remotekey = title,
            options = options,
            selectedOption = selectedOption,
            onSelect = { option ->
                onKeySelected(KarooKey.valueOf(option.id))
            }
        )
    }
}