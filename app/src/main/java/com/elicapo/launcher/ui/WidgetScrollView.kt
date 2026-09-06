package com.elicapo.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * Keeps Olauncher's full-screen gestures available on empty home space. A touch that starts on a
 * widget is delegated to the widget provider, as required for controls and scrollable widgets.
 */
class WidgetScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !startsOnWidget(event.x, event.y))
            return false
        return super.dispatchTouchEvent(event)
    }

    private fun startsOnWidget(x: Float, y: Float): Boolean {
        val canvas = getChildAt(0) as? WidgetCanvasView ?: return false
        val contentY = y + scrollY
        for (index in 0 until canvas.childCount) {
            val child = canvas.getChildAt(index)
            if (x >= canvas.left + child.left &&
                x < canvas.left + child.right &&
                contentY >= canvas.top + child.top &&
                contentY < canvas.top + child.bottom
            ) return true
        }
        return false
    }
}
