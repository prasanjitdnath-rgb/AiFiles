package app.aifiles

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun authenticate(a: FragmentActivity, title: String, onOk: () -> Unit) {
    val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onOk()
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title)
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL).build())
}

/** Vault = app-private storage (invisible to Gallery and other apps), gated by biometric / device PIN. Auto-locks when the app leaves the foreground. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(vm: VM) {
    val ctx = LocalContext.current
    val sel = remember { mutableStateListOf<File>() }
    BackHandler { if (sel.isNotEmpty()) sel.clear() else vm.showVault = false }
    LaunchedEffect(Unit) { if (!vm.vaultUnlocked) authenticate(ctx as FragmentActivity, "Unlock private vault") { vm.vaultUnlocked = true } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (sel.isEmpty()) "Private vault" else "${sel.size} selected") },
            navigationIcon = { IconButton({ if (sel.isNotEmpty()) sel.clear() else vm.showVault = false }) { Icon(Icons.Default.ArrowBack, null) } },
            actions = {
                if (sel.isNotEmpty() && vm.vaultUnlocked) {
                    IconButton({ val s = sel.toList(); sel.clear(); vm.fromVault(s) }) { Icon(Icons.Default.Restore, null) }
                    IconButton({ val s = sel.toList(); sel.clear(); vm.deleteVault(s) }) { Icon(Icons.Default.Delete, null) }
                }
            })
    }) { pad ->
        if (!vm.vaultUnlocked) Column(Modifier.padding(pad).fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Button({ authenticate(ctx as FragmentActivity, "Unlock private vault") { vm.vaultUnlocked = true } }) { Text("Unlock") }
        } else {
            val files by produceState(emptyList<File>(), vm.tick) { value = withContext(Dispatchers.IO) { vm.vaultDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() } }
            if (files.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) {
                Text("Vault is empty.\nSelect files or photos and tap the lock icon.", Modifier.padding(32.dp))
            } else LazyColumn(Modifier.padding(pad)) {
                items(files, key = { it.path }) { f ->
                    val s = f in sel
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = { if (sel.isNotEmpty()) { if (s) sel.remove(f) else sel.add(f) } else if (isPlayable(f)) vm.player = f else openFile(ctx, f) },
                            onLongClick = { if (!s) sel.add(f) }),
                        colors = ListItemDefaults.colors(containerColor = if (s) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                        leadingContent = {
                            if (s) Icon(Icons.Default.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            else if (isMedia(f)) AsyncImage(f, null, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            else Icon(Icons.Default.InsertDriveFile, null, Modifier.size(48.dp))
                        },
                        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(Formatter.formatFileSize(ctx, f.length())) })
                }
            }
        }
    }
}
