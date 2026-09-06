package com.elicapo.launcher.ui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * AppWidgetHostView that keeps normal provider touch handling intact while reserving a long press
 * for launcher editing. The long press is detected by the host parent, not by an OnTouchListener,
 * because most widgets own the actual touch target inside the host view.
 */
class EditableAppWidgetHostView(context: Context) : AppWidgetHostView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var editing = false
    private var onLongPress: (() -> Unit)? = null
    private var onInteractionTouch: ((MotionEvent) -> Boolean)? = null

    fun setWidgetLongPressListener(listener: () -> Unit) {
        onLongPress = listener
    }

    fun setInteractionTouchListener(listener: (MotionEvent) -> Boolean) {
        onInteractionTouch = listener
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (onInteractionTouch?.invoke(event) == true) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPressDetection()
                longPressTriggered = false
                editing = false
                downRawX = event.rawX
                downRawY = event.rawY
                longPressRunnable = Runnable {
                    longPressTriggered = true
                    editing = true
                    // A widget may disallow parent interception for its own gestures. Re-enable
                    // it so the next UP/CANCEL can be intercepted and cannot trigger a provider tap.
                    requestDisallowInterceptTouchEvent(false)
                    onLongPress?.invoke()
                }.also {
                    handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!longPressTriggered) {
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                    if (abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop
                    ) cancelLongPressDetection()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPressDetection()
            }
        }
        return editing
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                editing = false
                longPressTriggered = false
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        cancelLongPressDetection()
        super.onDetachedFromWindow()
    }

    private fun cancelLongPressDetection() {
        longPressRunnable?.let(handler::removeCallbacks)
        longPressRunnable = null
    }
}

class LauncherAppWidgetHost(context: Context, hostId: Int) :
    AppWidgetHost(context.applicationContext, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo,
    ): AppWidgetHostView = EditableAppWidgetHostView(context.applicationContext)
}
