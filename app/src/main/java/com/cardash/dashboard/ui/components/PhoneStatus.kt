package com.cardash.dashboard.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.ui.theme.Danger
import com.cardash.dashboard.ui.theme.Good
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import com.cardash.dashboard.ui.theme.Warn
import kotlinx.coroutines.delay

data class PhoneStatus(
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val tempC: Float? = null,
    val downBps: Long = 0,
    val upBps: Long = 0,
    val network: String = "Offline",
)

/** Samples battery, battery temperature, and live network throughput once a second. */
@Composable
fun rememberPhoneStatus(): PhoneStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(PhoneStatus()) }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val t = SystemClock.elapsedRealtime()
            val dt = ((t - lastT).coerceAtLeast(1)) / 1000.0
            val down = if (rx >= lastRx && lastRx >= 0) ((rx - lastRx) * 8 / dt).toLong() else 0L
            val up = if (tx >= lastTx && lastTx >= 0) ((tx - lastTx) * 8 / dt).toLong() else 0L
            lastRx = rx; lastTx = tx; lastT = t
            status = readBattery(context).copy(downBps = down, upBps = up, network = networkType(context))
        }
    }
    return status
}

private fun readBattery(context: Context): PhoneStatus {
    val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return PhoneStatus()
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    return PhoneStatus(
        batteryPct = if (level >= 0) level * 100 / scale else -1,
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        tempC = if (temp != Int.MIN_VALUE) temp / 10f else null,
    )
}

private fun networkType(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "Offline"
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
            val kbps = caps.linkDownstreamBandwidthKbps
            when {
                kbps >= 100_000 -> "5G"
                kbps >= 10_000 -> "4G"
                else -> "Mobile"
            }
        }
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BT tether"
        else -> "Online"
    }
}

private fun rate(bps: Long): String = when {
    bps >= 1_000_000 -> "%.1fM".format(bps / 1_000_000.0)
    bps >= 1_000 -> "%.0fk".format(bps / 1_000.0)
    else -> "0k"
}

/** Compact row: battery · phone temperature · network + live download speed. Wraps when narrow. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PhoneStatusRow(modifier: Modifier = Modifier) {
    val s = rememberPhoneStatus()
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val pct = s.batteryPct
        val battIcon = when {
            s.charging -> Icons.Filled.BatteryChargingFull
            pct >= 85 -> Icons.Filled.BatteryFull
            pct >= 55 -> Icons.Filled.Battery6Bar
            pct >= 30 -> Icons.Filled.Battery4Bar
            else -> Icons.Filled.Battery2Bar
        }
        val battTint = when {
            s.charging -> Good
            pct in 0..14 -> Danger
            pct in 15..29 -> Warn
            else -> Color.White
        }
        Chip(battIcon, battTint, if (pct >= 0) "$pct%" else "--")

        val t = s.tempC
        val tempTint = when {
            t == null -> Muted
            t >= 43f -> Danger
            t >= 38f -> Warn
            else -> Color.White
        }
        Chip(Icons.Filled.Thermostat, tempTint, t?.let { "%.0f°".format(it) } ?: "--")

        val netIcon = when (s.network) {
            "Wi-Fi" -> Icons.Filled.Wifi
            "Offline" -> Icons.Filled.WifiOff
            else -> Icons.Filled.SignalCellularAlt
        }
        val netLabel = when (s.network) {
            "Offline" -> "Offline"
            "Wi-Fi" -> "↓" + rate(s.downBps)
            else -> s.network + " ↓" + rate(s.downBps)
        }
        Chip(netIcon, if (s.network == "Offline") Danger else Color.White, netLabel)
    }
}

@Composable
private fun Chip(icon: ImageVector, tint: Color, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Tile)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
