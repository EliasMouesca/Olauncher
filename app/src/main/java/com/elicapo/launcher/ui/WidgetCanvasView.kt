package com.elicapo.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.elicapo.launcher.data.WidgetPlacement
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A deliberately invisible launcher grid. AppWidgetHostView instances remain responsible for
 * drawing and handling their own controls; this view only owns their placement and edit gestures.
 */
class WidgetCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class InteractionMode { MOVE, RESIZE }

    companion object {
        const val PORTRAIT_COLUMNS = 4
        const val PORTRAIT_ROWS = 6
        const val LANDSCAPE_COLUMNS = 8
        const val LANDSCAPE_ROWS = 4
    }

    private val density = resources.displayMetrics.density
    private val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        LANDSCAPE_COLUMNS
    } else {
        PORTRAIT_COLUMNS
    }
    private val baseRows = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        LANDSCAPE_ROWS
    } else {
        PORTRAIT_ROWS
    }
    private val horizontalPadding = 16 * density
    private val gridTop = 112 * density
    private val gridBottom = 64 * density

    private val placements = linkedMapOf<Int, WidgetPlacement>()
    private val placementViews = linkedMapOf<Int, View>()
    private var onWidgetLongClick: ((WidgetPlacement) -> Unit)? = null
    private var onPlacementChanged: ((WidgetPlacement) -> Unit)? = null
    private var onInteractionStateChanged: ((InteractionMode?) -> Unit)? = null
    private var interactionMode: InteractionMode? = null
    private var interactionWidgetId: Int? = null
    private var interactionDownX = 0f
    private var interactionDownY = 0f
    private var interactionStartX = 0
    private var interactionStartY = 0
    private var interactionStartSpanX = 1
    private var interactionStartSpanY = 1
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var rowCount = baseRows

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun setWidgetLongClickListener(listener: (WidgetPlacement) -> Unit) {
        onWidgetLongClick = listener
    }

    fun setPlacementChangedListener(listener: (WidgetPlacement) -> Unit) {
        onPlacementChanged = listener
    }

    fun setInteractionStateListener(listener: (InteractionMode?) -> Unit) {
        onInteractionStateChanged = listener
    }

    fun setPlacements(value: List<WidgetPlacement>) {
        placements.clear()
        value.forEach { originalPlacement ->
            val placement = originalPlacement.copy(
                cellX = originalPlacement.cellX.coerceAtLeast(0),
                cellY = originalPlacement.cellY.coerceAtLeast(0),
                spanX = originalPlacement.spanX.coerceIn(1, columns),
                spanY = originalPlacement.spanY.coerceAtLeast(1),
            ).also {
                it.cellX = it.cellX.coerceAtMost(columns - it.spanX)
            }
            placements[placement.appWidgetId] = placement
        }
        requestLayout()
    }

    fun getPlacement(appWidgetId: Int): WidgetPlacement? = placements[appWidgetId]

    fun clearWidgetViews() {
        placementViews.clear()
        removeAllViews()
    }

    fun addWidgetView(placement: WidgetPlacement, view: View) {
        val normalizedPlacement = placement.copy(
            cellX = placement.cellX.coerceAtLeast(0),
            cellY = placement.cellY.coerceAtLeast(0),
            spanX = placement.spanX.coerceIn(1, columns),
            spanY = placement.spanY.coerceAtLeast(1),
        ).also {
            it.cellX = it.cellX.coerceAtMost(columns - it.spanX)
        }
        placements[placement.appWidgetId] = normalizedPlacement
        placementViews[placement.appWidgetId]?.let { removeView(it) }
        placementViews[placement.appWidgetId] = view
        view.tag = normalizedPlacement.appWidgetId
        (view as? EditableAppWidgetHostView)?.setWidgetLongPressListener {
            onWidgetLongClick?.invoke(placements[normalizedPlacement.appWidgetId] ?: normalizedPlacement)
        }
        (view as? EditableAppWidgetHostView)?.setInteractionTouchListener { event ->
            if (interactionWidgetId == normalizedPlacement.appWidgetId && interactionMode != null) {
                handleInteractionTouch(view, event)
            } else {
                false
            }
        }
        addView(view)
        requestLayout()
    }

    fun removeWidgetView(appWidgetId: Int) {
        placementViews.remove(appWidgetId)?.let { removeView(it) }
        placements.remove(appWidgetId)
        if (interactionWidgetId == appWidgetId) cancelInteraction()
        requestLayout()
    }

    fun beginInteraction(appWidgetId: Int, mode: InteractionMode) {
        val placement = placements[appWidgetId] ?: return
        interactionWidgetId = appWidgetId
        interactionMode = mode
        interactionStartX = placement.cellX
        interactionStartY = placement.cellY
        interactionStartSpanX = placement.spanX
        interactionStartSpanY = placement.spanY
        placementViews[appWidgetId]?.alpha = 0.82f
        onInteractionStateChanged?.invoke(mode)
        invalidate()
    }

    fun cancelInteraction() {
        val wasInteracting = interactionMode != null
        interactionWidgetId?.let { placementViews[it]?.alpha = 1f }
        interactionWidgetId = null
        interactionMode = null
        requestDisallowInterceptTouchEvent(false)
        if (wasInteracting) onInteractionStateChanged?.invoke(null)
        invalidate()
    }

    fun finishInteraction(): WidgetPlacement? {
        val placement = interactionWidgetId?.let { placements[it]?.copy() }
        cancelInteraction()
        return placement
    }

    fun findAvailablePosition(spanX: Int, spanY: Int): Pair<Int, Int> {
        val safeSpanX = spanX.coerceIn(1, columns)
        val safeSpanY = spanY.coerceAtLeast(1)
        val maxY = max(baseRows, placements.values.maxOfOrNull { it.cellY + it.spanY } ?: 0) + 12
        for (y in 0..maxY) {
            for (x in 0..columns - safeSpanX) {
                if (canPlace(x, y, safeSpanX, safeSpanY, null)) return x to y
            }
        }
        return 0 to maxY + 1
    }

    fun calculateSpans(minWidthDp: Int, minHeightDp: Int): Pair<Int, Int> {
        val cellWidthDp = if (cellWidth > 1f) cellWidth / density else 80f
        val cellHeightDp = if (cellHeight > 1f) cellHeight / density else 80f
        val spanX = ceil(minWidthDp.coerceAtLeast(1) / cellWidthDp).toInt()
            .coerceIn(1, columns)
        val spanY = ceil(minHeightDp.coerceAtLeast(1) / cellHeightDp).toInt()
            .coerceAtLeast(1)
        return spanX to spanY
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> resources.displayMetrics.heightPixels
            else -> MeasureSpec.getSize(heightMeasureSpec)
        }.coerceAtLeast(1)
        val availableHeight = (viewportHeight - gridTop - gridBottom).coerceAtLeast(1f)
        cellWidth = ((width - horizontalPadding * 2) / columns).coerceAtLeast(1f)
        cellHeight = (availableHeight / baseRows).coerceAtLeast(1f)
        rowCount = max(baseRows, placements.values.maxOfOrNull { it.cellY + it.spanY } ?: 0)

        val desiredHeight = (gridTop + cellHeight * rowCount + gridBottom).roundToInt()
        val measuredHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> {
                if (rowCount > baseRows) desiredHeight else MeasureSpec.getSize(heightMeasureSpec)
            }
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredHeight
        }
        setMeasuredDimension(width, measuredHeight)

        placementViews.forEach { (id, view) ->
            val placement = placements[id] ?: return@forEach
            val childWidth = (cellWidth * placement.spanX).roundToInt().coerceAtLeast(1)
            val childHeight = (cellHeight * placement.spanY).roundToInt().coerceAtLeast(1)
            view.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        placementViews.forEach { (id, view) ->
            val placement = placements[id] ?: return@forEach
            val childLeft = (horizontalPadding + cellWidth * placement.cellX).roundToInt()
            val childTop = (gridTop + cellHeight * placement.cellY).roundToInt()
            view.layout(childLeft, childTop, childLeft + view.measuredWidth, childTop + view.measuredHeight)
        }
    }

    private fun handleInteractionTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interactionDownX = event.rawX
                interactionDownY = event.rawY
                requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val placement = interactionWidgetId?.let { placements[it] } ?: return true
                val mode = interactionMode ?: return true
                val deltaX = ((event.rawX - interactionDownX) / cellWidth).roundToInt()
                val deltaY = ((event.rawY - interactionDownY) / cellHeight).roundToInt()
                val candidate = when (mode) {
                    InteractionMode.MOVE -> placement.copy(
                        cellX = (interactionStartX + deltaX).coerceIn(0, columns - placement.spanX),
                        cellY = (interactionStartY + deltaY).coerceAtLeast(0),
                    )
                    InteractionMode.RESIZE -> placement.copy(
                        spanX = (interactionStartSpanX + deltaX).coerceIn(1, columns - placement.cellX),
                        spanY = (interactionStartSpanY + deltaY).coerceAtLeast(1),
                    )
                }
                if (canPlace(candidate.cellX, candidate.cellY, candidate.spanX, candidate.spanY, candidate.appWidgetId)) {
                    placements[candidate.appWidgetId] = candidate
                    requestLayout()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                finishInteraction()?.let { onPlacementChanged?.invoke(it) }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelInteraction()
                return true
            }
        }
        return true
    }

    private fun canPlace(
        cellX: Int,
        cellY: Int,
        spanX: Int,
        spanY: Int,
        ignoredWidgetId: Int?,
    ): Boolean {
        if (cellX < 0 || cellY < 0 || cellX + spanX > columns) return false
        return placements.values.none { other ->
            other.appWidgetId != ignoredWidgetId &&
                    cellX < other.cellX + other.spanX &&
                    cellX + spanX > other.cellX &&
                    cellY < other.cellY + other.spanY &&
                    cellY + spanY > other.cellY
        }
    }
}
