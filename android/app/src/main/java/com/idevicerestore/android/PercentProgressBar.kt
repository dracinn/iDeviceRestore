package com.idevicerestore.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.ProgressBar
import java.util.Locale

/** Horizontal progress bar that keeps the exact download percentage visible in-app. */
class PercentProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.progressBarStyleHorizontal
) : ProgressBar(context, attrs, defStyleAttr) {
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        color = resolveTextColor()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isIndeterminate || max <= 0) return

        val percent = progress.coerceIn(0, max) * 100.0 / max
        val label = String.format(Locale.US, "%.1f%%", percent)
        val metrics = percentPaint.fontMetrics
        val x = width / 2f
        val y = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, x, y, percentPaint)
    }

    private fun resolveTextColor(): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        return try {
            attrs.getColor(0, 0xFF000000.toInt())
        } finally {
            attrs.recycle()
        }
    }
}
