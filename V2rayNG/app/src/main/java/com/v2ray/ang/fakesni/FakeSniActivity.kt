package com.v2ray.ang.fakesni

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class FakeSniActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        FakeSniScreen(onBack = { finish() })
    }
}

@Composable
private fun FakeSniScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { FakeSniPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var fakeSniHostname by remember { mutableStateOf(prefs.fakeSniHostname) }
    var connectIp by remember { mutableStateOf(prefs.connectIp) }
    var connectPort by remember { mutableStateOf(prefs.connectPort.toString()) }
    var listenPort by remember { mutableStateOf(prefs.listenPort.toString()) }
    var utls by remember { mutableStateOf(prefs.utls) }
    var injector by remember { mutableStateOf(prefs.injector) }
    var fragment by remember { mutableStateOf(prefs.enableFragment) }

    Scaffold(
        topBar = { AppTopBar(title = "FakeSNI", onBackClick = onBack, isLoading = false) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable FakeSNI", style = MaterialTheme.typography.titleMedium)
                    Text("Integrated into v2rayNG; root is required.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        prefs.enabled = it
                        if (it) FakeSniService.start(context) else FakeSniService.stop(context)
                    }
                )
            }

            OutlinedTextField(
                value = fakeSniHostname,
                onValueChange = { fakeSniHostname = it; prefs.fakeSniHostname = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Fake SNI Hostname") },
                singleLine = true
            )
            OutlinedTextField(
                value = connectIp,
                onValueChange = { connectIp = it; prefs.connectIp = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Connect IP") },
                supportingText = { Text("Real upstream IP, for example 104.16.2.189") },
                singleLine = true
            )
            OutlinedTextField(
                value = connectPort,
                onValueChange = {
                    connectPort = it
                    it.toIntOrNull()?.let { value -> prefs.connectPort = value }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Connect Port") },
                singleLine = true
            )
            OutlinedTextField(
                value = listenPort,
                onValueChange = {
                    listenPort = it
                    it.toIntOrNull()?.let { value -> prefs.listenPort = value.coerceIn(1024, 65535) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Listen Port") },
                supportingText = { Text("Local FakeSNI port, normally 40443") },
                singleLine = true
            )
            OutlinedTextField(
                value = utls,
                onValueChange = { utls = it; prefs.utls = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("uTLS fingerprint") },
                supportingText = { Text("firefox, chrome, safari, ios, android, randomized, none") },
                singleLine = true
            )
            OutlinedTextField(
                value = injector,
                onValueChange = { injector = it; prefs.injector = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Injector") },
                supportingText = { Text("passive or active") },
                singleLine = true
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = fragment,
                    onCheckedChange = { fragment = it; prefs.enableFragment = it }
                )
                Text("  Enable TLS fragmentation")
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (enabled) FakeSniService.start(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply / Restart FakeSNI") }
        }
    }
}
