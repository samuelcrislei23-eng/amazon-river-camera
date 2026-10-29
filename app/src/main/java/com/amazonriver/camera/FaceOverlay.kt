package com.amazonriver.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FaceOverlay @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var boxes: List<RectF> = emptyList()
        set(v) { field = v; invalidate() }
    var grid = false
        set(v) { field = v; invalidate() }
    var tap: PointF? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.argb(110, 255, 255, 255)
    }

    override fun onDraw(cv: Canvas) {
        if (grid) {
            for (i in 1..2) {
                cv.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), line)
                cv.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, line)
            }
        }
        for (b in boxes) cv.drawRect(b, box)
        tap?.let { cv.drawCircle(it.x, it.y, 28f * d, box) }
    }
}
