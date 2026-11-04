package dev.ibb.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Microsoft
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ibb.launcher.IbbApplication
import dev.ibb.launcher.core.IbbLog
import dev.ibb.launcher.data.auth.MicrosoftAuth
import dev.ibb.launcher.data.model.Account
import dev.ibb.launcher.data.model.AccountType
import dev.ibb.launcher.data.model.MsaDeviceCode
import dev.ibb.launcher.ui.components.BrandHeader
import dev.ibb.launcher.ui.components.BrandWordmark
import dev.ibb.launcher.ui.components.GlassCard
import dev.ibb.launcher.ui.components.GlassChip
import dev.ibb.launcher.ui.components.GlassDivider
import dev.ibb.launcher.ui.components.GlassIconButton
import dev.ibb.launcher.ui.components.IbbButton
import dev.ibb.launcher.ui.components.IbbButtonStyle
import dev.ibb.launcher.ui.components.SectionHeader
import dev.ibb.launcher.ui.screens.components.PlayerHead
import dev.ibb.launcher.ui.theme.Ash
import dev.ibb.launcher.ui.theme.Cyan
import dev.ibb.launcher.ui.theme.Danger
import dev.ibb.launcher.ui.theme.Mint
import dev.ibb.launcher.ui.theme.Porcelain
import dev.ibb.launcher.ui.theme.Violet
import dev.ibb.launcher.ui.theme.VioletBright
import kotlinx.coroutines.launch

/**
 * Account manager.
 *
 * Microsoft sign-in uses the **device-code** flow: we show the short code, open the browser, and
 * poll in the background. This avoids an embedded WebView (which Microsoft blocks for OAuth in
 * many configurations) and supports 2FA, passkeys and password-less accounts.
 *
 * Offline profiles are first-class: many players use them for LAN play, and they need no network
 * at all. The UUID is derived exactly like a vanilla server does, so worlds keep their data.
 */
@Composable
fun AccountsScreen(app: IbbApplication) {
    val scope = rememberCoroutineScope()
    val accounts by app.accountStore.accounts.collectAsState()
    val activeId by app.accountStore.activeId.collectAsState()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    var showAddDialog by remember { mutableStateOf(false) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf<MsaDeviceCode?>(null) }
    var authState by remember { mutableStateOf<AuthUiState>(AuthUiState.Idle) }
    var removeTarget by remember { mutableStateOf<Account?>(null) }

    // Keep the device-code dialog in sync with the auth coroutine.
    LaunchedEffect(authState) {
        val state = authState
        if (state is AuthUiState.Waiting) {
            deviceCode = state.code
        } else if (state is AuthUiState.Success || state is AuthUiState.Error) {
            deviceCode = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandWordmark(showTagline = true, modifier = Modifier.weight(1f))
            }
        }
        item { BrandHeader(subtitle = "Accounts") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IbbButton(
                    text = "Microsoft",
                    icon = Icons.Rounded.Microsoft,
                    style = IbbButtonStyle.PRIMARY,
                    modifier = Modifier.weight(1f),
                    enabled = authState !is AuthUiState.Waiting,
                    onClick = {
                        scope.launch {
                            authState = AuthUiState.Starting
                            val result = app.microsoftAuth.signIn(
                                onCode = { code ->
                                    authState = AuthUiState.Waiting(code)
                                    runCatching { uriHandler.openUri(code.verificationUri) }
                                },
                            )
                            authState = when (result) {
                                is MicrosoftAuth.Result.Success -> {
                                    app.accountStore.upsert(result.account)
                                    AuthUiState.Success(result.account.username)
                                }
                                is MicrosoftAuth.Result.Failure -> AuthUiState.Error(result.reason)
                                MicrosoftAuth.Result.Cancelled -> AuthUiState.Idle
                                MicrosoftAuth.Result.NoMinecraftOwnership ->
                                    AuthUiState.Error("This account does not own Minecraft: Java Edition")
                                MicrosoftAuth.Result.NoProfileName ->
                                    AuthUiState.Error("Set up a Minecraft profile at minecraft.net first")
                                is MicrosoftAuth.Result.AwaitingUser -> AuthUiState.Error("Unexpected auth state")
                            }
                        }
                    },
                )
                IbbButton(
                    text = "Offline",
                    icon = Icons.Rounded.Add,
                    style = IbbButtonStyle.SECONDARY,
                    modifier = Modifier.weight(1f),
                    onClick = { showOfflineDialog = true },
                )
            }
        }

        when (val state = authState) {
            is AuthUiState.Waiting -> item {
                GlassCard(modifier = Modifier.fillMaxWidth(), accent = Cyan, accentStrength = 0.10f) {
                    Column {
                        Text("Finish signing in", style = MaterialTheme.typography.titleSmall, color = Cyan)
                        Text(
                            "Enter this code on microsoft.com/link",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.code.userCode,
                                style = MaterialTheme.typography.displaySmall,
                                color = Porcelain,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp),
                            )
                            Spacer(Modifier.width(10.dp))
                            GlassIconButton(
                                icon = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy code",
                                onClick = { clipboard.setText(AnnotatedString(state.code.userCode)) },
                                size = 34.dp,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        IbbButton(
                            text = "Open microsoft.com/link",
                            icon = Icons.Rounded.OpenInNew,
                            style = IbbButtonStyle.GHOST,
                            onClick = { runCatching { uriHandler.openUri(state.code.verificationUri) } },
                        )
                        Spacer(Modifier.height(8.dp))
                        IbbButton("Cancel", { authState = AuthUiState.Idle }, style = IbbButtonStyle.GHOST)
                    }
                }
            }
            is AuthUiState.Error -> item {
                GlassCard(modifier = Modifier.fillMaxWidth(), accent = Danger) {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8FA3))
                }
            }
            is AuthUiState.Starting -> item {
                GlassCard(modifier = Modifier.fillMaxWidth(), accent = Violet) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = Violet,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Contacting Microsoft…", style = MaterialTheme.typography.bodySmall, color = Ash)
                    }
                }
            }
            is AuthUiState.Success -> item {
                GlassCard(modifier = Modifier.fillMaxWidth(), accent = Mint) {
                    Text("Signed in as ${state.username}", style = MaterialTheme.typography.bodySmall, color = Mint)
                }
            }
            AuthUiState.Idle -> Unit
        }

        item { SectionHeader("Profiles", accent = VioletBright) }

        if (accounts.isEmpty()) {
            item {
                GlassCard(modifier = Modifier.fillMaxWidth(), accentStrength = 0.03f) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("No profiles yet", style = MaterialTheme.typography.titleSmall, color = Porcelain)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Add a Microsoft account for online play, or create an offline profile for LAN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ash,
                        )
                    }
                }
            }
        }

        items(accounts, key = { it.id }) { account ->
            AccountRow(
                account = account,
                active = account.id == activeId,
                onSelect = { app.accountStore.select(account.id) },
                onRemove = { removeTarget = account },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            GlassDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "Tokens are encrypted with a hardware-backed key on this device and never leave it.",
                style = MaterialTheme.typography.labelSmall,
                color = Ash,
            )
        }
    }

    if (showOfflineDialog) {
        OfflineAccountDialog(
            onDismiss = { showOfflineDialog = false },
            onCreate = { name ->
                val account = Account(
                    type = AccountType.OFFLINE,
                    username = name,
                    uuid = Account.offlineUuid(name),
                    lastUsedAt = System.currentTimeMillis(),
                )
                app.accountStore.upsert(account)
                IbbLog.i("Accounts", "Created offline profile $name")
                showOfflineDialog = false
            },
        )
    }

    removeTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${account.username}?") },
            text = { Text("The profile is deleted from this device. Your worlds are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    app.accountStore.remove(account.id)
                    removeTarget = null
                }) { Text("Remove", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
            containerColor = Color(0xFF16141F),
            titleContentColor = Porcelain,
            textContentColor = Ash,
        )
    }
}

private sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Starting : AuthUiState()
    data class Waiting(val code: MsaDeviceCode) : AuthUiState()
    data class Success(val username: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

@Composable
private fun AccountRow(account: Account, active: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = if (active) Cyan else Violet,
        accentStrength = if (active) 0.10f else 0.04f,
        contentPadding = PaddingValues(12.dp),
        onClick = onSelect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerHead(username = account.username, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.username, style = MaterialTheme.typography.titleSmall, color = Porcelain, fontWeight = FontWeight.SemiBold)
                Text(
                    if (account.isOnline) "Microsoft · ${account.uuid.take(8)}…" else "Offline · ${account.uuid.take(8)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ash,
                )
            }
            if (account.isExpired) GlassChip("EXPIRED", accent = Danger)
            else if (active) GlassChip("ACTIVE", accent = Cyan)
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.DeleteOutline, "Remove", onRemove, size = 34.dp, tint = Ash)
        }
    }
}

@Composable
private fun OfflineAccountDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val valid = name.length in 3..16 && name.all { it.isLetterOrDigit() || it == '_' }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16141F),
        title = { Text("Offline profile", color = Porcelain) },
        text = {
            Column {
                Text(
                    "Used for LAN worlds and servers with online-mode off. Works without internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 16) name = it },
                    singleLine = true,
                    label = { Text("Username") },
                    isError = name.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            if (name.isNotEmpty() && !valid) "3–16 letters, numbers or _" else "Same rules as a Minecraft username",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Porcelain,
                        unfocusedTextColor = Porcelain,
                        focusedBorderColor = Violet,
                        cursorColor = Cyan,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onCreate(name) }, enabled = valid) {
                Text("Create", color = if (valid) Cyan else Ash)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ash) } },
    )
}
