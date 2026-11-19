package app.aifiles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun canAuthenticate(activity: FragmentActivity): Boolean {
    val manager = BiometricManager.from(activity)
    val result = manager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

fun authenticate(activity: FragmentActivity, title: String, onOk: () -> Unit, onFail: (() -> Unit)? = null) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onOk()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail?.invoke()
            }

            override fun onAuthenticationFailed() {
                // user can retry; prompt stays open in most cases
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use your fingerprint, face, or device PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build(),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(vm: VM) {
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val selected = remember { mutableStateListOf<String>() }
    var authError by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }

    fun tryUnlock() {
        if (activity == null) {
            authError = "Vault is unavailable in this context."
            return
        }
        if (!canAuthenticate(activity)) {
            authError = "Set up a screen lock, fingerprint, or face unlock to use the vault."
            return
        }
        authError = null
        authenticate(
            activity = activity,
            title = "Unlock private vault",
            onOk = {
                vm.vaultUnlocked = true
                authError = null
            },
            onFail = {
                authError = "Authentication canceled or failed."
            },
        )
    }

    LaunchedEffect(Unit) {
        if (!vm.vaultUnlocked && !attempted) {
            attempted = true
            tryUnlock()
        }
    }

    BackHandler {
        when {
            selected.isNotEmpty() -> selected.clear()
            else -> vm.showVault = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Private vault" else "${selected.size} selected") },
                navigationIcon = {
                    IconButton({
                        if (selected.isNotEmpty()) selected.clear() else vm.showVault = false
                    }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (vm.vaultUnlocked && selected.isNotEmpty()) {
                        IconButton({
                            val files = selected.map(::File)
                            selected.clear()
                            vm.fromVault(files)
                        }) { Icon(Icons.Default.Restore, contentDescription = "Restore") }
                        IconButton({
                            val files = selected.map(::File)
                            selected.clear()
                            vm.deleteVault(files)
                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { pad ->
        if (!vm.vaultUnlocked) {
            Column(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Private vault", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Files moved here are stored in app-private storage and hidden from other apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (authError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(authError!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { tryUnlock() }) { Text("Unlock") }
            }
        } else {
            val files by produceState(initialValue = emptyList<File>(), vm.tick, vm.vaultUnlocked) {
                value = withContext(Dispatchers.IO) {
                    vm.vaultDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                }
            }
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Vault is empty.\nSelect files or photos and move them to the vault to keep them private.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize(),
                ) {
                    items(files, key = { it.path }) { file ->
                        val isSelected = file.path in selected
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    when {
                                        selected.isNotEmpty() -> {
                                            if (isSelected) selected.remove(file.path) else selected.add(file.path)
                                        }

                                        isPlayable(file) -> vm.player = file

                                        else -> openFile(ctx, file)
                                    }
                                },
                                onLongClick = { if (!isSelected) selected.add(file.path) },
                            ),
                            colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                            leadingContent = {
                                when {
                                    isSelected -> Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(44.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )

                                    isMedia(file) -> AsyncImage(
                                        model = file,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop,
                                    )

                                    else -> Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(40.dp))
                                }
                            },
                            headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(Formatter.formatShortFileSize(ctx, file.length()))
                            },
                        )
                    }
                }
            }
        }
    }
}