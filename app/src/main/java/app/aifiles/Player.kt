package app.aifiles

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

fun isPlayable(f: File) = f.extension.lowercase().let { it in Cats.exts["Videos"]!! || it in Cats.exts["Audio"]!! }

@Composable
fun VideoPlayer(f: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val exo = remember(f) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); prepare(); playWhenReady = true } }
    DisposableEffect(exo) { onDispose { exo.release() } }
    var speed by remember { mutableFloatStateOf(1f) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ c -> PlayerView(c).apply { player = exo; keepScreenOn = true } }, Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            Text(f.name, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
        }
        LazyRow(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 52.dp, end = 8.dp)) {
            items(listOf(0.5f, 1f, 1.5f, 2f)) { s ->
                FilterChip(speed == s, { speed = s; exo.setPlaybackSpeed(s) }, { Text("${s}x") }, Modifier.padding(horizontal = 2.dp))
            }
        }
    }
}
