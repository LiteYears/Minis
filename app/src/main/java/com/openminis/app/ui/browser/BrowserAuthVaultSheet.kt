package com.openminis.app.ui.browser

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.browser.BrowserAuthVault
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.ui.chat.StandardChatSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Bottom sheet for managing Persistent Named Auth Profiles ("Authenticated Session Vault")
 * and inspecting Set-of-Marks (SoM) Visual DOM grounding on the active browser tab.
 */
@Composable
fun BrowserAuthVaultSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        BrowserAuthVault.ensureLoaded(context)
    }

    val profiles by BrowserAuthVault.profiles.collectAsState()
    val tabs by tabPool.tabs.collectAsState()
    val selectedTabId by tabPool.selectedTabId.collectAsState()
    val selectedTab = tabs.find { it.id == selectedTabId } ?: tabs.firstOrNull()
    val currentUrl = selectedTab?.manager?.currentURL?.collectAsState()?.value.orEmpty()
    val somActive = selectedTab?.manager?.somOverlayActive?.collectAsState()?.value ?: false
    val somCount = selectedTab?.manager?.somMarkCount?.collectAsState()?.value ?: 0

    var profileNameInput by remember(currentUrl) {
        mutableStateOf(BrowserAuthVault.suggestProfileName(currentUrl))
    }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    val accent = MaterialTheme.colorScheme.primary
    val cardBg = MaterialTheme.colorScheme.surfaceContainerHigh

    StandardChatSheet(
        title = "Authenticated Session Vault",
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── Set-of-Marks (SoM) Visual Grounding Card ──
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(accent.copy(alpha = 0.14f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Visibility,
                                    contentDescription = "Set-of-Marks Visual Grounding",
                                    tint = accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Visual DOM · Set-of-Marks (SoM)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (somActive) {
                                        "Live overlay active: $somCount interactive elements numbered [1]..[$somCount]"
                                    } else {
                                        "Overlays numbered [1], [2], [3] badges on interactive elements for Vision AI"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    val mgr = selectedTab?.manager ?: return@FilledTonalButton
                                    scope.launch {
                                        val count = mgr.toggleSomOverlayFromUI()
                                        statusBanner = if (count > 0) {
                                            "Marked $count interactive elements on page"
                                        } else {
                                            "Cleared Set-of-Marks visual overlay"
                                        }
                                    }
                                },
                                enabled = selectedTab != null && currentUrl.isNotBlank() && !isBusy,
                                modifier = Modifier.testTag("vault_toggle_som_button"),
                            ) {
                                Text(if (somActive) "Hide Marks" else "Preview [1..N]")
                            }
                        }
                    }
                }
            }

            // ── Save Current Session Card ──
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(accent.copy(alpha = 0.14f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Save Authenticated Session",
                                    tint = accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Save Current Logged-In Session",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                        currentUrl
                                    } else {
                                        "Open a website and sign in first to capture cookies & storage"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = profileNameInput,
                                onValueChange = { profileNameInput = it },
                                label = { Text("Profile Name (e.g. github-work, banking)") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("vault_profile_name_input"),
                            )
                            Button(
                                onClick = {
                                    val mgr = selectedTab?.manager ?: return@Button
                                    isBusy = true
                                    scope.launch {
                                        val res = BrowserAuthVault.saveFromManager(
                                            context = context,
                                            manager = mgr,
                                            rawProfileName = profileNameInput,
                                        )
                                        isBusy = false
                                        statusBanner = res.text.lineSequence().firstOrNull() ?: res.text
                                        Toast.makeText(
                                            context,
                                            statusBanner,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                enabled = selectedTab != null &&
                                    currentUrl.isNotBlank() &&
                                    currentUrl != "about:blank" &&
                                    !isBusy,
                                modifier = Modifier.testTag("vault_save_profile_button"),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Save")
                            }
                        }
                    }
                }
            }

            // ── Status Banner ──
            statusBanner?.let { msg ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .border(0.5.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // ── Saved Named Auth Profiles Header ──
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "SAVED AUTH PROFILES (${profiles.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "AI tool: load_auth_profile",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                    )
                }
            }

            if (profiles.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = cardBg.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                text = "No Named Auth Profiles saved yet",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Log into any website in the Browser Sheet (including 2FA) and tap Save above. The AI agent can then reuse your authenticated cookies and storage anytime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(profiles, key = { it.name }) { profile ->
                    AuthProfileRowCard(
                        profile = profile,
                        accent = accent,
                        cardBg = cardBg,
                        enabled = !isBusy,
                        onLoad = {
                            isBusy = true
                            scope.launch {
                                val targetTab = selectedTab ?: tabPool.newTabFromUI()
                                val mgr = targetTab?.manager
                                if (mgr != null) {
                                    val res = BrowserAuthVault.loadIntoManager(
                                        context = context,
                                        manager = mgr,
                                        rawProfileName = profile.name,
                                        navigateToSavedUrl = true,
                                    )
                                    statusBanner = res.text.lineSequence().firstOrNull() ?: res.text
                                    Toast.makeText(
                                        context,
                                        "Loaded profile '${profile.name}'",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                isBusy = false
                            }
                        },
                        onDelete = {
                            BrowserAuthVault.deleteProfile(context, profile.name)
                            statusBanner = "Deleted profile '${profile.name}'"
                        },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AuthProfileRowCard(
    profile: BrowserAuthVault.AuthProfile,
    accent: androidx.compose.ui.graphics.Color,
    cardBg: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateStr = remember(profile.updatedAtMs) {
        SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(profile.updatedAtMs))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onLoad),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(accent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Key,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        if (profile.primaryDomain.isNotBlank()) {
                            Text(
                                text = profile.primaryDomain,
                                fontSize = 11.sp,
                                color = accent,
                                modifier = Modifier
                                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${profile.cookieCount} cookies · ${profile.storageKeyCount} storage keys · $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onLoad,
                    enabled = enabled,
                    modifier = Modifier.testTag("vault_load_${profile.name}"),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Load Profile ${profile.name}",
                        tint = accent,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.testTag("vault_delete_${profile.name}"),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Profile ${profile.name}",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
            if (profile.savedUrl.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Text(
                    text = profile.savedUrl,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
