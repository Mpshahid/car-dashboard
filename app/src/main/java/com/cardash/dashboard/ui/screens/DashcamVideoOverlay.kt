package com.cardash.dashboard.ui.screens

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Telemetry to stamp onto the recording; written from the UI, read on the GL thread. */
data class VideoHudSnapshot(
    val speed: String = "--",
    val speedUnit: String = "",
    val gear: String = "-",
    val rpm: String = "--",
    val coolant: String = "--",
    val limit: String? = null,
)

/**
 * Burns a dashcam HUD into every recorded video frame using CameraX [OverlayEffect]
 * (target = VIDEO_CAPTURE only, so the on-screen preview keeps its Compose overlay).
 */
class DashcamVideoOverlay {
    val snapshot = AtomicReference(VideoHudSnapshot())

    private val thread = HandlerThread("DashcamOverlay").apply { start() }
    val effect = OverlayEffect(
        CameraEffect.VIDEO_CAPTURE,
        0,
        Handler(thread.looper),
    ) { t -> Log.e("DashcamOverlay", "Overlay effect error", t) }

    private val stampFormat = SimpleDateFormat("EEE dd MMM yyyy  HH:mm:ss", Locale.UK)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
    private val bigPaint = text(Typeface.create("sans-serif-light", Typeface.NORMAL))
    private val midPaint = text(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val smallPaint = text(Typeface.create("sans-serif", Typeface.NORMAL)).apply { alpha = 210 }
    private val gearBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 27, 27, 32) }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 75, 74) }
    private val signFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val signText = text(Typeface.create("sans-serif-medium", Typeface.BOLD)).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }

    init {
        effect.setOnDrawListener { frame -> draw(frame); true }
    }

    private fun text(face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = face
        setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0))
    }

    private fun draw(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val crop = frame.cropRect
        val w = crop.width().toFloat()
        val h = crop.height().toFloat()
        val rot = ((frame.rotationDegrees % 360) + 360) % 360

        canvas.save()
        canvas.translate(crop.left.toFloat(), crop.top.toFloat())
        // Map an upright (as-played-back) coordinate space onto the raw buffer.
        when (rot) {
            90 -> { canvas.translate(0f, h); canvas.rotate(-90f) }
            180 -> { canvas.translate(w, h); canvas.rotate(180f) }
            270 -> { canvas.translate(w, 0f); canvas.rotate(90f) }
        }
        val uw = if (rot % 180 == 0) w else h
        val uh = if (rot % 180 == 0) h else w
        drawHud(canvas, uw, uh, snapshot.get())
        canvas.restore()
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, s: VideoHudSnapshot) {
        val u = minOf(w, h) / 1080f // 1.0 at 1080p
        val m = 36f * u

        // Top-left timestamp strip.
        smallPaint.textSize = 30f * u
        val stamp = stampFormat.format(Date())
        val stampW = smallPaint.measureText(stamp)
        c.drawRoundRect(RectF(m, m, m + stampW + 36f * u, m + 54f * u), 12f * u, 12f * u, panelPaint)
        c.drawText(stamp, m + 18f * u, m + 38f * u, smallPaint)

        // Top-right tag with a red dot.
        midPaint.textSize = 28f * u
        val tag = "CARDASH"
        val tagW = midPaint.measureText(tag)
        val tagRight = w - m
        c.drawRoundRect(RectF(tagRight - tagW - 66f * u, m, tagRight, m + 54f * u), 12f * u, 12f * u, panelPaint)
        c.drawCircle(tagRight - tagW - 38f * u, m + 27f * u, 9f * u, redPaint)
        c.drawText(tag, tagRight - tagW - 18f * u, m + 37f * u, midPaint)

        // Bottom-left telemetry panel.
        bigPaint.textSize = 120f * u
        midPaint.textSize = 30f * u
        smallPaint.textSize = 28f * u
        val speedW = bigPaint.measureText(s.speed)
        val unitW = midPaint.measureText(s.speedUnit)
        val gearBox = 92f * u
        val stats = "${s.rpm} RPM    ${s.coolant} COOLANT"
        val statsW = smallPaint.measureText(stats)
        val rowW = speedW + 14f * u + unitW + 28f * u + gearBox
        val panelW = maxOf(rowW, statsW) + 48f * u
        val panelH = 220f * u
        val top = h - m - panelH
        c.drawRoundRect(RectF(m, top, m + panelW, h - m), 20f * u, 20f * u, panelPaint)

        val baseline = top + 132f * u
        var x = m + 24f * u
        c.drawText(s.speed, x, baseline, bigPaint)
        x += speedW + 14f * u
        c.drawText(s.speedUnit, x, baseline, midPaint)
        x += unitW + 28f * u
        val gTop = baseline - 84f * u
        c.drawRoundRect(RectF(x, gTop, x + gearBox, gTop + gearBox), 18f * u, 18f * u, gearBoxPaint)
        midPaint.textSize = 52f * u
        midPaint.textAlign = Paint.Align.CENTER
        c.drawText(s.gear, x + gearBox / 2f, gTop + gearBox / 2f + 18f * u, midPaint)
        midPaint.textAlign = Paint.Align.LEFT

        c.drawText(stats, m + 24f * u, h - m - 30f * u, smallPaint)

        // Optional UK-style speed-limit roundel to the right of the panel.
        s.limit?.let { lim ->
            val r = 52f * u
            val cx = m + panelW + 30f * u + r
            val cy = h - m - panelH / 2f
            c.drawCircle(cx, cy, r, redPaint)
            c.drawCircle(cx, cy, r * 0.78f, signFill)
            signText.textSize = 44f * u
            c.drawText(lim, cx, cy + 16f * u, signText)
        }
    }

    fun release() {
        effect.clearOnDrawListener()
        effect.close()
        thread.quitSafely()
    }
}
