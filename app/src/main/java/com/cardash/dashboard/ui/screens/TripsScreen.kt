package com.cardash.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardash.dashboard.obd.VehicleState
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.theme.Muted
import com.cardash.dashboard.ui.theme.Tile
import kotlin.math.roundToInt

@Composable
fun TripsScreen(
    state: VehicleState,
    prefs: UiPrefs,
    modifier: Modifier = Modifier,
) {
    val trip = state.trip
    val avgL100 = if (trip.distanceKm > 0.1) trip.fuelUsedL / trip.distanceKm * 100.0 else 0.0

    val cards = listOf(
        "Distance" to "%.1f %s".format(Fmt.distValue(trip.distanceKm, prefs.imperial), Fmt.distUnit(prefs.imperial)),
        "Duration" to Fmt.duration(trip.durationSec),
        "Average speed" to "${Fmt.speedValue(trip.avgSpeedKmh, prefs.imperial)} ${Fmt.speedUnit(prefs.imperial)}",
        "Top speed" to "${Fmt.speedValue(trip.maxSpeedKmh, prefs.imperial)} ${Fmt.speedUnit(prefs.imperial)}",
        "Fuel used" to "%.2f L".format(trip.fuelUsedL),
        "Economy" to "${Fmt.economy(avgL100, prefs.imperial)} ${Fmt.economyUnit(prefs.imperial)}",
    )

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Current trip",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "Resets when you reconnect. Full trip history and CSV export are coming next.",
            color = Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        cards.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (label, value) ->
                    StatCard(label, value, Modifier.weight(1f))
                }
                if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Tile)
            .padding(18.dp),
    ) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(
            value,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
