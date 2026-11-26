package com.cardash.dashboard.ui.components

import com.cardash.dashboard.music.NowPlaying
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.music.MusicController
import com.cardash.dashboard.ui.theme.Accent
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile

/** Compact now-playing card with previous / play-pause / next. */
@Composable
fun MusicCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val access by MusicController.access.collectAsState()
    val n by MusicController.now.collectAsState()
    LaunchedEffect(Unit) { MusicController.refresh() }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    if (maxWidth < 250.dp) { MusicStacked(access, n); return@BoxWithConstraints }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Tile)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art / icon
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262B))
                .clickable { if (access) MusicController.openMusicApp() else MusicController.openAccessSettings(context) },
            contentAlignment = Alignment.Center,
        ) {
            val art = n.art
            if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp))
            else Icon(Icons.Filled.MusicNote, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier.weight(1f).clickable(enabled = !access || !n.hasTrack) {
                if (!access) MusicController.openAccessSettings(context) else MusicController.openMusicApp()
            },
        ) {
            when {
                !access -> {
                    Text("Music", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Tap to allow music control", color = Accent, fontSize = 11.sp, maxLines = 1)
                }
                !n.hasTrack -> {
                    Text("Nothing playing", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Tap to open YouTube Music", color = Muted, fontSize = 11.sp, maxLines = 1)
                }
                else -> {
                    // Long titles scroll instead of being cut off.
                    Text(
                        n.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        listOf(n.artist, n.appLabel).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (access && n.hasTrack) {
            Ctl(Icons.Filled.SkipPrevious, 28) { MusicController.previous() }
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color.White).clickable { MusicController.playPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (n.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
            }
            Ctl(Icons.Filled.SkipNext, 28) { MusicController.next() }
        }
    }
    }
}

/** Narrow version: art + title on top, the three controls in a row underneath. */
@Composable
private fun MusicStacked(access: Boolean, n: NowPlaying) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tile)
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable {
                if (!access) MusicController.openAccessSettings(context) else MusicController.openMusicApp()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF26262B)), contentAlignment = Alignment.Center) {
                val art = n.art
                if (art != null) Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(34.dp))
                else Icon(Icons.Filled.MusicNote, null, tint = Muted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val title = when { !access -> "Music"; !n.hasTrack -> "Nothing playing"; else -> n.title }
                val sub = when {
                    !access -> "Tap to allow"
                    !n.hasTrack -> "Tap to open"
                    else -> listOf(n.artist, n.appLabel).filter { it.isNotBlank() }.joinToString(" · ")
                }
                Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.basicMarquee())
                Text(sub, color = if (!access) Accent else Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (access && n.hasTrack) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Ctl(Icons.Filled.SkipPrevious, 34) { MusicController.previous() }
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Color.White).clickable { MusicController.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (n.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                }
                Ctl(Icons.Filled.SkipNext, 34) { MusicController.next() }
            }
        }
    }
}

@Composable
private fun Ctl(icon: ImageVector, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
