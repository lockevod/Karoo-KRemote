package com.enderthor.kremote.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
fun TabLayout(
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf( "Help")

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
            0 -> Help()
        }
    }
}

@Composable
fun Help() {
    Column(
        modifier = Modifier.fillMaxSize().padding(1.dp).background(color = androidx.compose.ui.graphics.Color(0xFFE0E0E0)),
    ) {
        Text(text = "App autostarts every time, you only need to push de GRemote button and wait until the Remote flashing green several times.\n" +
                "\n" +
                "Apps is configured by default:\n" +
                "\n" +
                "Left Button =>> Back Button (several actions, depends on the current Karoo screen.. back, zoom level etc).\n" +
                "Right Button ==> Next screen or item.\n" +
                "Upper Button ==> Right bottom button.\n")
    }
}



@Preview(name = "karoo", device = "spec:width=480px,height=800px,dpi=300")
@Composable
private fun PreviewTabLayout() {
    TabLayout(
    )
}