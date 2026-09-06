package com.elicapo.launcher.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.elicapo.launcher.helper.isEinkDisplay
import com.elicapo.launcher.helper.isSystemAnimationsDisabled
import kotlin.math.abs
import kotlin.math.roundToLong

class DrawerHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private enum class State {
        CLOSED,
        DRAGGING,
        SETTLING,
        OPEN,
    }

    private var drawerView: View? = null
    private var state = State.CLOSED
    private var settleAnimator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var dragging = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    var onInteractiveDrawerStart: (() -> Boolean)? = null
    var onDrawerProgress: ((Float) -> Unit)? = null
    var onDrawerOpened: (() -> Unit)? = null
    var onDrawerClosed: (() -> Unit)? = null

    fun attachDrawer(view: View) {
        drawerView = view
        view.visibility = View.INVISIBLE
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.translationY = height.toFloat()
        onDrawerProgress?.invoke(0f)
        view.bringToFront()
    }

    fun isDrawerVisible(): Boolean = state != State.CLOSED

    fun showDrawer(animated: Boolean = true) {
        val drawer = drawerView ?: return
        cancelSettle()
        drawer.visibility = View.VISIBLE
        drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        drawer.bringToFront()

        if (height == 0) {
            post { showDrawer(animated) }
            return
        }

        settleTo(0f, animated)
    }

    fun prepareDrawerForDrag(): Boolean {
        val drawer = drawerView ?: return false
        cancelSettle()
        drawer.visibility = View.VISIBLE
        drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        drawer.bringToFront()
        setDrawerTranslation(height.toFloat())
        state = State.DRAGGING
        dragging = true
        return true
    }

    fun closeDrawer(animated: Boolean = true, onClosed: (() -> Unit)? = null) {
        val drawer = drawerView
        if (drawer == null || height == 0) {
            state = State.CLOSED
            dragging = false
            onClosed?.invoke()
            return
        }

        cancelSettle()
        dragging = false
        settleTo(height.toFloat(), animated, onClosed)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
            }
        }
        velocityTracker?.addMovement(event)

        val handled = super.dispatchTouchEvent(event)

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            velocityTracker?.recycle()
            velocityTracker = null
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = event.x
                gestureStartY = event.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (state != State.CLOSED || dragging) return false

                val diffX = event.x - gestureStartX
                val diffY = event.y - gestureStartY
                if (diffY < -touchSlop && abs(diffY) > abs(diffX)) {
                    val canStart = onInteractiveDrawerStart?.invoke() == true
                    if (canStart && prepareDrawerForDrag()) {
                        updateDrawerPosition(-diffY)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return state != State.CLOSED

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateDrawerPosition(gestureStartY - event.y)
            MotionEvent.ACTION_UP -> {
                val tracker = velocityTracker
                tracker?.computeCurrentVelocity(1000)
                val velocityY = tracker?.yVelocity ?: 0f
                val progress = drawerProgress()
                val shouldOpen = progress >= OPEN_THRESHOLD || velocityY < -minimumFlingVelocity
                dragging = false
                settleTo(if (shouldOpen) 0f else height.toFloat(), animated = true)
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                settleTo(height.toFloat(), animated = true)
            }
        }
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (state == State.CLOSED)
            setDrawerTranslation(height.toFloat())
    }

    override fun onDetachedFromWindow() {
        cancelSettle()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    private fun updateDrawerPosition(distance: Float) {
        val drawer = drawerView ?: return
        val clampedDistance = distance.coerceIn(0f, height.toFloat())
        setDrawerTranslation(height - clampedDistance)
    }

    private fun drawerProgress(): Float {
        val drawer = drawerView ?: return 0f
        if (height == 0) return 0f
        return (1f - drawer.translationY / height).coerceIn(0f, 1f)
    }

    private fun settleTo(
        target: Float,
        animated: Boolean,
        onComplete: (() -> Unit)? = null,
    ) {
        val drawer = drawerView ?: run {
            state = State.CLOSED
            onComplete?.invoke()
            return
        }

        val current = drawer.translationY
        val distance = abs(target - current)
        val animationsDisabled = context.isSystemAnimationsDisabled() || context.isEinkDisplay()
        if (!animated || animationsDisabled || distance < 1f) {
            finishAt(target, onComplete)
            return
        }

        state = State.SETTLING
        val duration = (distance / height * MAX_SETTLE_DURATION_MS)
            .roundToLong()
            .coerceIn(MIN_SETTLE_DURATION_MS, MAX_SETTLE_DURATION_MS)
        val animator = ValueAnimator.ofFloat(current, target).apply {
            this.duration = duration
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                setDrawerTranslation(valueAnimator.animatedValue as Float)
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (settleAnimator !== animator) return
                settleAnimator = null
                finishAt(target, onComplete)
            }
        })
        settleAnimator = animator
        animator.start()
    }

    private fun finishAt(target: Float, onComplete: (() -> Unit)?) {
        val drawer = drawerView ?: return
        setDrawerTranslation(target)
        if (target <= 0f) {
            state = State.OPEN
            drawer.visibility = View.VISIBLE
            drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            onDrawerOpened?.invoke()
        } else {
            state = State.CLOSED
            drawer.visibility = View.INVISIBLE
            drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            onDrawerClosed?.invoke()
            onComplete?.invoke()
        }
    }

    private fun cancelSettle() {
        settleAnimator?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        settleAnimator = null
    }

    private fun setDrawerTranslation(translationY: Float) {
        val drawer = drawerView ?: return
        drawer.translationY = translationY
        onDrawerProgress?.invoke(drawerProgress())
    }

    companion object {
        private const val OPEN_THRESHOLD = 0.33f
        private const val MIN_SETTLE_DURATION_MS = 120L
        private const val MAX_SETTLE_DURATION_MS = 220L
    }
}
