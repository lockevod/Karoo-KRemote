package com.enderthor.kremote.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enderthor.kremote.data.KarooKey
import com.enderthor.kremote.data.RemoteSettings
import com.enderthor.kremote.extension.saveSettings
import com.enderthor.kremote.extension.streamSettings

import kotlinx.coroutines.launch


@Composable
fun TabLayout(
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf( "Conf","Help")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(text = title, fontSize = 11.sp) },
                )
            }
        }

        when (selectedTabIndex) {
            0 -> Config()
            1 -> Help()
        }
    }
}

@Composable
fun Help() {
    Column(
        modifier = Modifier.fillMaxSize().padding(1.dp).background(color = Color(0xFFE0E0E0)),
    ) {
        Text(text = "Apps is configured by default:\n\n" +
                "- Left Button => Left screen or item.\n" +
                "- Right Button => Next screen or item.\n" +
                "- Upper Button => Back Button (several actions, depends on the current Karoo screen.. back, zoom level etc).\n\n" +
                "You can change the default configuration in the app and select different actions for each button.\n There is an option to use the remote in all screens or only when you're riding."
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Config() {

    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var bottomup by remember { mutableStateOf(KarooKey.BOTTOMLEFT) }
    var bottomleft by remember { mutableStateOf(KarooKey.TOPLEFT) }
    var bottomright by remember { mutableStateOf(KarooKey.TOPRIGHT) }
    var onlyWhileRiding by remember { mutableStateOf(false) }

    var savedDialogVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {

        ctx.streamSettings().collect { settings ->
            bottomup = settings.remoteup
            bottomright = settings.remoteright
            bottomleft = settings.remoteleft
            onlyWhileRiding = settings.onlyWhileRiding
        }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text("KRemote") })
        Column(modifier = Modifier
            .padding(5.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            apply {
                val dropdownOptions = KarooKey.entries.toList()
                    .map { unit -> DropdownOption(unit.action.toString(), unit.label) }
                val dropdownInitialSelection by remember(bottomup) {
                    mutableStateOf(dropdownOptions.find { option -> option.id == bottomup.action.toString() }!!)
                }
                KarooKeyDropdown(
                    remotekey = "Bottom Up", options = dropdownOptions, selectedOption = dropdownInitialSelection
                ) { selectedOption ->
                    bottomup =
                        KarooKey.entries.find { unit -> unit.action.toString() == selectedOption.id }!!
                }
            }


            apply {

                val dropdownOptions = KarooKey.entries.toList()
                    .map { unit -> DropdownOption(unit.action.toString(), unit.label) }
                val dropdownInitialSelection by remember(bottomleft) {
                    mutableStateOf(dropdownOptions.find { option -> option.id == bottomleft.action.toString() }!!)
                }
                KarooKeyDropdown(
                    remotekey = "Bottom Left",
                    options = dropdownOptions,
                    selectedOption = dropdownInitialSelection
                ) { selectedOption ->
                    bottomleft =
                        KarooKey.entries.find { unit -> unit.action.toString() == selectedOption.id }!!
                }
            }

            apply {
                val dropdownOptions = KarooKey.entries.toList()
                    .map { unit -> DropdownOption(unit.action.toString(), unit.label) }
                val dropdownInitialSelection by remember(bottomright) {
                    mutableStateOf(dropdownOptions.find { option -> option.id == bottomright.action.toString() }!!)
                }
                KarooKeyDropdown(
                    remotekey = "Bottom Right",
                    options = dropdownOptions,
                    selectedOption = dropdownInitialSelection
                ) { selectedOption ->
                    bottomright =
                        KarooKey.entries.find { unit -> unit.action.toString() == selectedOption.id }!!
                }
            }
            //onlyWhileRiding = true

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = onlyWhileRiding, onCheckedChange = {
                    onlyWhileRiding = it
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Only active while riding?")
            }


            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                val newSettings = RemoteSettings(
                    remoteleft = bottomleft, remoteright = bottomright, remoteup = bottomup,
                    onlyWhileRiding = onlyWhileRiding
                )

                coroutineScope.launch {
                    savedDialogVisible = true
                    saveSettings(ctx, newSettings)
                }
            }) {
                Icon(Icons.Default.Done, contentDescription = "Save")
                Spacer(modifier = Modifier.width(5.dp))
                Text("Save")
                Spacer(modifier = Modifier.width(5.dp))
            }
        }

    }
    if (savedDialogVisible){
        AlertDialog(onDismissRequest = { savedDialogVisible = false },
            confirmButton = { Button(onClick = {
                savedDialogVisible = false
            }) { Text("OK") } },
            text = { Text("Settings saved successfully.") }
        )
    }

}


@Preview(name = "karoo", device = "spec:width=480px,height=800px,dpi=300")
@Composable
private fun PreviewTabLayout() {
    TabLayout(
    )
}