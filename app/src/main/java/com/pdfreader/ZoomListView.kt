package com.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ListView

/**
 * ListView z przybliżaniem: szczypanie dwoma palcami, podwójne stuknięcie,
 * a po przybliżeniu przesuwanie jednym palcem we wszystkich kierunkach.
 */
class ZoomListView(context: Context, attrs: AttributeSet?) : ListView(context, attrs) {

    var zoom = 1f
        private set

    /** Wywoływane po zakończeniu gestu przybliżania (np. żeby wyrenderować ostrzej). */
    var onZoomEnd: (() -> Unit)? = null

    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrollRemainder = 0f
    private var manual = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                onZoomEnd?.invoke()
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomTo(if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
            onZoomEnd?.invoke()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (zoom <= 1f) return false
            smoothScrollBy((-velocityY / zoom * 0.3f).toInt(), 400)
            return true
        }
    })

    fun resetZoom() {
        if (zoom == 1f) return
        zoom = 1f
        tx = 0f
        ty = 0f
        invalidate()
        onZoomEnd?.invoke()
    }

    private fun zoomTo(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(1f, MAX_ZOOM)
        tx = fx - (fx - tx) * z / zoom
        ty = fy - (fy - ty) * z / zoom
        zoom = z
        clampTranslation()
        invalidate()
    }

    private fun panBy(dx: Float, dy: Float) {
        tx += dx
        ty += dy
        // Gdy przesunięcie w pionie wyjdzie poza przybliżony widok, przewijamy dalej listę stron.
        val minTy = height * (1 - zoom)
        val overflow = when {
            ty > 0f -> -ty.also { ty = 0f }
            ty < minTy -> (minTy - ty).also { ty = minTy }
            else -> 0f
        }
        if (overflow != 0f) {
            scrollRemainder += overflow / zoom
            val step = scrollRemainder.toInt()
            if (step != 0) {
                scrollListBy(step)
                scrollRemainder -= step
            }
        }
        clampTranslation()
        invalidate()
    }

    private fun clampTranslation() {
        tx = tx.coerceIn(width * (1 - zoom), 0f)
        ty = ty.coerceIn(height * (1 - zoom), 0f)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestures.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                manual = zoom > 1f
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!manual) {
                // Drugi palec: od teraz gest obsługujemy sami, a zwykłe przewijanie anulujemy.
                manual = true
                val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (!manual) return super.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && ev.pointerCount == 1) {
                    panBy(ev.x - lastX, ev.y - lastY)
                }
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (ev.actionIndex == 0) 1 else 0
                lastX = ev.getX(remaining)
                lastY = ev.getY(remaining)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> manual = false
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (zoom == 1f) return super.dispatchDraw(canvas)
        val save = canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(zoom, zoom)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    companion object {
        private const val MAX_ZOOM = 5f
        private const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
