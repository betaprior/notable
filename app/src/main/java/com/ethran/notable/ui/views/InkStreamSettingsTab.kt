package com.ethran.notable.ui.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ethran.notable.ink.InkStreamClient
import com.ethran.notable.ink.InkStreamSettings

@Composable
fun InkStreamSettingsTab() {
    val context = LocalContext.current
    val client = remember { InkStreamClient.from(context) }
    val settings by client.settings.collectAsState()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }
    var hubPort by remember { mutableStateOf("5550") }
    var secret by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        client.loadSettings()
    }
    LaunchedEffect(settings) {
        host = settings.host
        port = settings.port.toString()
        hubPort = settings.hubPort.toString()
        secret = settings.secret
    }

    fun persist(enabled: Boolean) {
        val portNum = port.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) {
            statusMessage = "Invalid port"
            return
        }
        val hubPortNum = hubPort.toIntOrNull()
        if (hubPortNum == null || hubPortNum !in 1..65535) {
            statusMessage = "Invalid hub port"
            return
        }
        // copy() so hub-managed fields (sessionToken) survive manual edits
        client.saveSettings(
            settings.copy(
                enabled = enabled, host = host.trim(),
                port = portNum, hubPort = hubPortNum, secret = secret.trim()
            )
        )
        statusMessage = if (enabled)
            "Streaming to ${host.trim()}:$portNum" else "Streaming disabled"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "Live Ink Streaming",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Mirror strokes in real time to a desktop running " +
                "`xournal --ink-listen <port>` on the same network.",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        EInkSection(
            title = if (settings.enabled) "Enabled" else "Disabled",
            icon = Icons.Default.Cast
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stream while drawing",
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { persist(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            EInkTextField(
                label = "Receiver host (IP or hostname)",
                value = host,
                onValueChange = { host = it },
                placeholder = "192.168.1.42"
            )

            Spacer(modifier = Modifier.height(8.dp))

            EInkTextField(
                label = "Receiver UDP port (set automatically by Stream to laptop)",
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                placeholder = "5555"
            )

            Spacer(modifier = Modifier.height(8.dp))

            EInkTextField(
                label = "Hub TCP port (inkhubd on the laptop)",
                value = hubPort,
                onValueChange = { hubPort = it.filter { c -> c.isDigit() } },
                placeholder = "5550"
            )

            Spacer(modifier = Modifier.height(8.dp))

            EInkTextField(
                label = "Hub secret (matches the hub config; optional)",
                value = secret,
                onValueChange = { secret = it },
                placeholder = ""
            )

            Spacer(modifier = Modifier.height(12.dp))

            InkActionButton(
                text = "Save",
                onClick = { persist(settings.enabled) },
                modifier = Modifier.fillMaxWidth(),
                isBold = true
            )
        }

        statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = msg,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun InkActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isBold: Boolean = false,
) {
    androidx.compose.material.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = eInkButtonColors(isSecondary = false)
    ) {
        Text(text = text, fontWeight = if (isBold) FontWeight.Bold else null)
    }
}
