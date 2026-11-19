package app.aifiles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: VM by viewModels()
    private var ok by mutableStateOf(false)
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grant() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(LocalContext.current)
                else androidx.compose.material3.dynamicLightColorScheme(LocalContext.current)) {
                Surface(Modifier.fillMaxSize()) { if (ok) Root(vm) else Gate { grant() } }
            }
        }
    }

    override fun onStop() { super.onStop(); vm.vaultUnlocked = false }

    override fun onResume() { super.onResume(); check() }

    private fun check() {
        ok = Environment.isExternalStorageManager() &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        if (ok) vm.refresh()
    }

    private fun grant() {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
            perms.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
        else if (!Environment.isExternalStorageManager())
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        else check()
    }
}

@Composable
fun Gate(onGrant: () -> Unit) = Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
    Icon(Icons.Default.FolderOpen, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text("AI Files", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text("Needs access to all files and media to browse, organise and index your photos. Everything is processed on your device.")
    Spacer(Modifier.height(24.dp))
    Button(onGrant) { Text("Grant access") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: VM) {
    var tab by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Files" to Icons.Default.Folder, "Gallery" to Icons.Default.Photo, "AI" to Icons.Default.AutoAwesome)
                    .forEachIndexed { i, (n, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, n) }, label = { Text(n) }) }
            }
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) { 0 -> FilesTab(vm); 1 -> GalleryTab(vm); else -> AiTab(vm) }
            }
        }
        vm.viewer?.let { (list, start) -> Viewer(vm, list, start) }
        if (vm.showVault) VaultScreen(vm)
        vm.player?.let { VideoPlayer(it) { vm.player = null } }
    }
}
