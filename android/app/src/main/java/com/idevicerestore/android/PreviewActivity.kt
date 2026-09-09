package com.idevicerestore.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class PreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        setContentView(AssetPreviewView(this, BuildConfig.PREVIEW_START_SCREEN) { action ->
            when (action) {
                "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                "diagnostics" -> startActivity(Intent(this, BootDiagnosticsActivity::class.java))
                "firmware", "restore", "devices", "more" -> startActivity(
                    Intent(this, MainActivity::class.java).putExtra("preview_action", action)
                )
            }
        })
    }
}

private class AssetPreviewView(
    context: Context,
    startScreen: String,
    private val action: (String) -> Unit
) : View(context) {
    init { setBackgroundColor(Color.WHITE) }

    private enum class Screen(val file: String) {
        HOME("mockups/01_home.webp"),
        FIRMWARE("mockups/02_firmware_catalog.webp"),
        RESTORE("mockups/03_restore.webp"),
        DIAGNOSTICS("mockups/04_diagnostics.webp")
    }

    private val bitmaps = Screen.values().associateWith { screen ->
        context.assets.open(screen.file).use(BitmapFactory::decodeStream)
    }
    private var screen = when (startScreen.lowercase()) {
        "firmware" -> Screen.FIRMWARE
        "restore" -> Screen.RESTORE
        "diagnostics" -> Screen.DIAGNOSTICS
        else -> Screen.HOME
    }
    private var imageRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = bitmaps.getValue(screen)
        val scale = minOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageRect = RectF(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, null, imageRect, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || !imageRect.contains(event.x, event.y)) return true
        val x = (event.x - imageRect.left) * 372f / imageRect.width()
        val y = (event.y - imageRect.top) * 876f / imageRect.height()
        handleTouch(x, y)
        return true
    }

    private fun handleTouch(x: Float, y: Float) {
        if (y >= 790f) {
            when {
                x < 74f -> show(Screen.HOME)
                x < 149f -> action("devices")
                x < 224f -> show(Screen.FIRMWARE)
                x < 299f -> show(Screen.RESTORE)
                else -> action("more")
            }
            return
        }
        when (screen) {
            Screen.HOME -> when {
                x > 310f && y < 120f -> action("settings")
                y in 450f..610f && x < 190f -> show(Screen.FIRMWARE)
                y in 450f..610f && x >= 190f -> show(Screen.RESTORE)
                y in 610f..755f && x < 190f -> show(Screen.FIRMWARE)
                y in 610f..755f && x >= 190f -> show(Screen.DIAGNOSTICS)
                y in 360f..455f -> action("devices")
            }
            Screen.FIRMWARE -> when {
                y < 125f && x < 80f -> show(Screen.HOME)
                y in 235f..735f && x > 265f -> action("firmware")
                y > 735f -> action("firmware")
            }
            Screen.RESTORE -> when {
                y < 125f && x < 80f -> show(Screen.HOME)
                y > 700f && x > 180f -> action("restore")
                y > 700f && x <= 180f -> show(Screen.FIRMWARE)
            }
            Screen.DIAGNOSTICS -> when {
                y < 125f && x < 80f -> show(Screen.HOME)
                y > 710f -> action("diagnostics")
                y in 115f..180f && x > 180f -> action("diagnostics")
            }
        }
    }

    private fun show(next: Screen) {
        if (screen != next) {
            screen = next
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        bitmaps.values.forEach(Bitmap::recycle)
        super.onDetachedFromWindow()
    }
}
