package com.openminis.app.ui.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserUseManager
import com.openminis.app.browser.BrowserUserscriptManager
import kotlinx.coroutines.launch

/**
 * Interactive Tampermonkey / Userscript management sheet inside the Browser.
 *
 * Lets the user:
 * 1. See which userscripts are currently active on the loaded page URL.
 * 2. One-tap install built-in Tampermonkey presets (`anti-annoyance-shield`,
 *    `unlock-copy-selection`, `auto-expand-articles`, `spa-api-interceptor`).
 * 3. Paste or author custom `// ==UserScript==` scripts with `@match` and `@run-at`.
 * 4. Enable/disable, manually run now, or remove any installed userscript.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserUserscriptSheet(
    manager: BrowserUseManager?,
    currentURL: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) {
        BrowserUserscriptManager.ensureLoaded(context)
    }

    val scripts by BrowserUserscriptManager.scriptsFlow.collectAsState()
    var showCreateEditor by remember { mutableStateOf(false) }
    var newScriptName by remember { mutableStateOf("") }
    var newScriptMatch by remember {
        val host = runCatching { java.net.URI(currentURL).host }.getOrNull()
        mutableStateOf(if (!host.isNullOrBlank()) "*://$host/*" else "<all_urls>")
    }
    var newScriptCode by remember {
        mutableStateOf(
            """
            // ==UserScript==
            // @name         Custom Page Tweaker
            // @match        <all_urls>
            // @run-at       document-end
            // ==/UserScript==

            (function() {
                'use strict';
                GM_log('Userscript running on ' + location.href);
            })();
            """.trimIndent()
        )
    }
    var isBusy by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "Tampermonkey Userscripts",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tampermonkey Userscripts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Auto-inject persistent JS & GM_* scripts on matching websites, or let the AI agent install them via browser_use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = { showCreateEditor = !showCreateEditor },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Script",
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (showCreateEditor) "Close" else "New")
                }
            }

            Spacer(Modifier.height(14.dp))

            // New script editor
            if (showCreateEditor) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Install Custom Userscript",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = newScriptName,
                                onValueChange = { newScriptName = it },
                                label = { Text("Name (optional if in header)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = newScriptMatch,
                                onValueChange = { newScriptMatch = it },
                                label = { Text("@match glob") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newScriptCode,
                            onValueChange = { newScriptCode = it },
                            label = { Text("Userscript Code (supports // ==UserScript== & GM_* APIs)") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            ),
                            minLines = 5,
                            maxLines = 9,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextButton(onClick = { showCreateEditor = false }) {
                                Text("Cancel")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = !isBusy && newScriptCode.isNotBlank(),
                                onClick = {
                                    isBusy = true
                                    scope.launch {
                                        val res = BrowserUserscriptManager.installFromTool(
                                            context = context,
                                            manager = manager,
                                            scriptName = newScriptName.takeIf { it.isNotBlank() },
                                            code = newScriptCode,
                                            matchPatterns = newScriptMatch.split(",")
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() },
                                            runAt = null,
                                            enabled = true,
                                        )
                                        isBusy = false
                                        showCreateEditor = false
                                        Toast.makeText(
                                            context,
                                            if (res.success) "Userscript installed & injected" else res.text,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            ) {
                                Text("Install & Run")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // Built-in Presets bar
            Text(
                text = "One-Tap Built-in Presets",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BrowserUserscriptManager.builtinPresets.forEach { preset ->
                    val alreadyInstalled = scripts.any { it.id == preset.id }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (alreadyInstalled) {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.clickable(enabled = !isBusy) {
                            isBusy = true
                            scope.launch {
                                val res = BrowserUserscriptManager.installFromTool(
                                    context = context,
                                    manager = manager,
                                    scriptName = preset.id,
                                    code = null,
                                    matchPatterns = null,
                                    runAt = null,
                                    enabled = true,
                                )
                                isBusy = false
                                Toast.makeText(
                                    context,
                                    if (res.success) "Activated ${preset.name}" else res.text,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (alreadyInstalled) "${preset.name} ✓" else "+ ${preset.name}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Text(
                text = "Installed Userscripts (${scripts.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))

            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No userscripts installed yet. Tap a preset above or let the AI agent call install_userscript.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(scripts, key = { it.id }) { script ->
                        val matchesPage = currentURL.isNotBlank() &&
                            currentURL != "about:blank" &&
                            BrowserUserscriptManager.matchesUrl(script, currentURL)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (matchesPage && script.enabled) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = script.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "v${script.version}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (matchesPage && script.enabled) {
                                            Spacer(Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary,
                                            ) {
                                                Text(
                                                    text = "ACTIVE",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontSize = 9.sp,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (script.description.isNotBlank()) {
                                        Text(
                                            text = script.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = "@match ${script.matchPatterns.joinToString(", ")} · @run-at ${script.runAt.wireValue}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                if (manager != null) {
                                    IconButton(
                                        enabled = !isBusy,
                                        onClick = {
                                            scope.launch {
                                                val status = manager.runSingleUserscriptNow(script)
                                                Toast.makeText(
                                                    context,
                                                    "Ran '${script.name}': $status",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Run script now on current page",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                Switch(
                                    checked = script.enabled,
                                    onCheckedChange = { checked ->
                                        scope.launch {
                                            BrowserUserscriptManager.toggleUserscriptResult(
                                                context = context,
                                                manager = manager,
                                                nameOrId = script.id,
                                                enabled = checked,
                                            )
                                        }
                                    },
                                )

                                IconButton(
                                    enabled = !isBusy,
                                    onClick = {
                                        scope.launch {
                                            BrowserUserscriptManager.removeUserscriptResult(
                                                context = context,
                                                manager = manager,
                                                nameOrId = script.id,
                                            )
                                            Toast.makeText(
                                                context,
                                                "Removed '${script.name}'",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete userscript",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
