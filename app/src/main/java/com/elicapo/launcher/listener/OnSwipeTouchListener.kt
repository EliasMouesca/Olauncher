package com.elicapo.launcher.listener

import android.content.Context
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import kotlin.math.abs

/*
Swipe, double tap and long press touch listener for a view
Source: https://www.tutorialspoint.com/how-to-handle-swipe-gestures-in-kotlin
*/

internal open class OnSwipeTouchListener(c: Context?) : OnTouchListener {
    //    private var doubleTapOn = false
    private val gestureDetector: GestureDetector
    private val earlySwipeThreshold = 100f
    private var swipeUpTriggered = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean {
        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeUpTriggered = false
                gestureStartX = motionEvent.x
                gestureStartY = motionEvent.y
            }
            MotionEvent.ACTION_MOVE -> triggerEarlySwipeUp(motionEvent)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = gestureDetector.onTouchEvent(motionEvent)
                swipeUpTriggered = false
                return handled
            }
        }
        return gestureDetector.onTouchEvent(motionEvent)
    }

    private fun triggerEarlySwipeUp(event: MotionEvent) {
        if (swipeUpTriggered) return

        val diffX = event.x - gestureStartX
        val diffY = event.y - gestureStartY
        if (diffY < -earlySwipeThreshold && abs(diffY) > abs(diffX)) {
            swipeUpTriggered = true
            onSwipeUp()
        }
    }

    private inner class GestureListener : SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD: Int = 100
        private val SWIPE_VELOCITY_THRESHOLD: Int = 100

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (swipeUpTriggered) return false
//            if (doubleTapOn) {
//                doubleTapOn = false
//                onTripleClick()
//            }
            onClick()
            return super.onSingleTapUp(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
//            doubleTapOn = true
//            Timer().schedule(Constants.TRIPLE_TAP_DELAY_MS) {
//                if (doubleTapOn) {
//                    doubleTapOn = false
//                    onDoubleClick()
//                }
//            }
            onDoubleClick()
            return super.onDoubleTap(e)
        }

        override fun onLongPress(e: MotionEvent) {
            onLongClick()
            super.onLongPress(e)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            try {
                if (swipeUpTriggered) return false
                val diffY = event2.y - (event1?.y ?: 0F)
                val diffX = event2.x - (event1?.x ?: 0F)
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) onSwipeRight() else onSwipeLeft()
                    }
                } else {
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY < 0) onSwipeUp() else onSwipeDown()
                    }
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
            return false
        }
    }

    open fun onSwipeRight() {}
    open fun onSwipeLeft() {}
    open fun onSwipeUp() {}
    open fun onSwipeDown() {}
    open fun onLongClick() {}
    open fun onDoubleClick() {}
    open fun onTripleClick() {}
    open fun onClick() {}

    init {
        gestureDetector = GestureDetector(c, GestureListener())
    }
}
