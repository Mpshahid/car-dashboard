package com.cardash.dashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code for [text]: black modules on white with the standard 4-module quiet zone, so phone
 * cameras pick it up even on the app's dark background.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, description: String = "QR code") {
    val matrix = remember(text) {
        runCatching {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    } ?: return
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description }) {
        drawRect(Color.White)
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            // Slight overlap so neighbouring modules join without hairline gaps.
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}
