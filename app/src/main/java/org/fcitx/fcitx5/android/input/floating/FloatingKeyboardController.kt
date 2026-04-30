/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.radiusDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Controls the floating keyboard mode: positioning, dragging, and resizing.
 *
 * The keyboard content is rendered at full screen width and then uniformly scaled down
 * using [View.setScaleX]/[View.setScaleY], so that fonts, icons, and all other elements
 * scale proportionally with the container size.
 */
class FloatingKeyboardController(
    private val theme: Theme,
    private val onBoundsChanged: () -> Unit
) {

    companion object {
        const val DRAG_HANDLE_HEIGHT_DP = 20
        const val RESIZE_HANDLE_SIZE_DP = 24
        const val MIN_WIDTH_DP = 200
        const val BORDER_RADIUS_DP = 12
        const val MAX_HEIGHT_FRACTION = 0.9f
    }

    private val prefs = AppPrefs.getInstance()
    private val internalPrefs = prefs.internal

    private var parentWidth = 0
    private var parentHeight = 0

    /** The floating container that wraps the keyboard view */
    var floatingContainer: FrameLayout? = null
        private set

    /** The inner wrapper that applies scale to the keyboard */
    private var scaledWrapper: ScaleToFitLayout? = null

    val bounds = Rect()

    /**
     * A FrameLayout that measures its children at a fixed "full" width/height,
     * then applies scaleX/scaleY to fit within its actual allocated size.
     * This ensures the keyboard is laid out at full screen width so font sizes
     * and all other dimension-based content scale uniformly.
     */
    private class ScaleToFitLayout(context: android.content.Context) : FrameLayout(context) {
        var fullWidth: Int = 0
        var fullHeight: Int = 0

        init {
            clipChildren = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (fullWidth > 0 && fullHeight > 0) {
                // Measure children at the full (unscaled) size
                val childWidthSpec = MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY)
                val childHeightSpec = MeasureSpec.makeMeasureSpec(fullHeight, MeasureSpec.EXACTLY)
                for (i in 0 until childCount) {
                    getChildAt(i).measure(childWidthSpec, childHeightSpec)
                }
                // But report our own size as the actual allocated space
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(w, h)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            // Layout children at their measured (full) size, starting at 0,0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            }
            val actualWidth = right - left
            val actualHeight = bottom - top
            if (fullWidth > 0 && fullHeight > 0 && actualWidth > 0 && actualHeight > 0) {
                val widthScale = actualWidth.toFloat() / fullWidth.toFloat()
                val heightScale = actualHeight.toFloat() / fullHeight.toFloat()
                val scale = min(widthScale, heightScale)
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    child.pivotX = 0f
                    child.pivotY = 0f
                    child.scaleX = scale
                    child.scaleY = scale
                }
            }
        }
    }

    /**
     * Creates the floating container wrapping the given keyboard view.
     * Returns the container to be added to the InputView.
     *
     * The keyboard view is placed inside a [ScaleToFitLayout] that measures it at full screen
     * width and then scales the rendering down. This ensures all content (text, icons)
     * scales proportionally with the floating window size.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun createFloatingContainer(
        context: android.content.Context,
        keyboardView: View
    ): FrameLayout {
        val container = FrameLayout(context).apply {
            background = radiusDrawable(context.dp(BORDER_RADIUS_DP).toFloat(), theme.barColor)
            clipToOutline = true
            clipChildren = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = context.dp(8).toFloat()
        }

        // Drag handle bar (visual indicator)
        val handle = View(context).apply {
            backgroundColor = theme.altKeyTextColor
            alpha = 0.3f
        }
        val handleLp = FrameLayout.LayoutParams(
            context.dp(40),
            context.dp(4)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(6)
        }
        container.addView(handle, handleLp)

        val dragArea = View(context).apply {
            setOnTouchListener(DragTouchListener())
        }
        val dragAreaLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.dp(DRAG_HANDLE_HEIGHT_DP)
        ).apply {
            gravity = Gravity.BOTTOM
        }
        container.addView(dragArea, dragAreaLp)

        // ScaleToFitLayout: measures keyboard at full screen width, renders scaled down
        val wrapper = ScaleToFitLayout(context)
        val wrapperLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            bottomMargin = context.dp(DRAG_HANDLE_HEIGHT_DP)
        }

        // Remove keyboard from its current parent if needed
        (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
        wrapper.addView(keyboardView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        container.addView(wrapper, wrapperLp)
        scaledWrapper = wrapper

        // Resize handle at bottom-right
        val resizeView = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle)
            scaleType = ImageView.ScaleType.CENTER
            alpha = 0.5f
            rotation = -45f
            setOnTouchListener(ResizeTouchListener())
        }
        val resizeLp = FrameLayout.LayoutParams(
            context.dp(RESIZE_HANDLE_SIZE_DP),
            context.dp(RESIZE_HANDLE_SIZE_DP)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }
        container.addView(resizeView, resizeLp)

        floatingContainer = container
        return container
    }

    /**
     * Updates position and size of the floating container based on saved prefs and available space.
     * Sets the full (unscaled) measurement size on the ScaleToFitLayout so fonts scale correctly.
     * Floating keyboard uses the same layout regardless of orientation.
     *
     * @param availableWidth  the actual width of the parent container (floatingRoot)
     * @param availableHeight the actual height of the parent container (floatingRoot)
     * @param fullKeyboardWidth  the width at which the keyboard is designed (device short edge)
     * @param fullKeyboardHeight the full height of the keyboardView (toolbar + keys + padding)
     */
    fun updateLayout(
        availableWidth: Int, availableHeight: Int,
        fullKeyboardWidth: Int, fullKeyboardHeight: Int
    ) {
        parentWidth = availableWidth
        parentHeight = availableHeight
        val container = floatingContainer ?: return

        val widthPercent = getWidthPercent()
        var containerWidth = (availableWidth * widthPercent / 100f).roundToInt()
            .coerceAtMost(availableWidth)
        val dragHandleHeight = container.context.dp(DRAG_HANDLE_HEIGHT_DP)
        val keyboardScale = containerWidth.toFloat() / fullKeyboardWidth.toFloat()
        var containerHeight = (fullKeyboardHeight * keyboardScale).roundToInt() + dragHandleHeight

        val maxHeight = (availableHeight * MAX_HEIGHT_FRACTION).roundToInt()
        if (containerHeight > maxHeight) {
            containerHeight = maxHeight
            val contentHeight = containerHeight - dragHandleHeight
            containerWidth = (contentHeight.toFloat() * fullKeyboardWidth / fullKeyboardHeight)
                .roundToInt().coerceAtMost(availableWidth)
        }

        val (savedX, savedY) = getSavedPosition()
        val x = if (savedX < 0) {
            // Center horizontally
            (availableWidth - containerWidth) / 2
        } else {
            savedX.coerceIn(0, (availableWidth - containerWidth).coerceAtLeast(0))
        }
        val y = if (savedY < 0) {
            // Position near bottom
            (availableHeight - containerHeight - container.context.dp(20)).coerceAtLeast(0)
        } else {
            savedY.coerceIn(0, (availableHeight - containerHeight).coerceAtLeast(0))
        }

        container.layoutParams = FrameLayout.LayoutParams(containerWidth, containerHeight).apply {
            leftMargin = x
            topMargin = y
        }
        container.requestLayout()

        // Tell the ScaleToFitLayout to measure keyboard at full device width.
        // The wrapper will scale it down to fit containerWidth.
        scaledWrapper?.apply {
            fullWidth = fullKeyboardWidth
            fullHeight = fullKeyboardHeight
            requestLayout()
        }

        updateBounds(x, y, containerWidth, containerHeight)
    }

    private fun getWidthPercent(): Int {
        return internalPrefs.floatingKeyboardWidthPercent.getValue()
    }

    private fun getSavedPosition(): Pair<Int, Int> {
        return Pair(
            internalPrefs.floatingKeyboardX.getValue(),
            internalPrefs.floatingKeyboardY.getValue()
        )
    }

    private fun savePosition(x: Int, y: Int) {
        internalPrefs.floatingKeyboardX.setValue(x)
        internalPrefs.floatingKeyboardY.setValue(y)
    }

    private fun saveSize(widthPercent: Int) {
        internalPrefs.floatingKeyboardWidthPercent.setValue(widthPercent)
    }

    private fun updateBounds(x: Int, y: Int, width: Int, height: Int) {
        val newRight = x + width
        val newBottom = y + height
        if (bounds.left == x && bounds.top == y &&
            bounds.right == newRight && bounds.bottom == newBottom
        ) return
        bounds.set(x, y, newRight, newBottom)
        onBoundsChanged()
    }

    /**
     * Handles dragging the floating keyboard
     */
    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startMarginLeft = 0
        private var startMarginTop = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val container = floatingContainer ?: return false
            val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startMarginLeft = lp.leftMargin
                    startMarginTop = lp.topMargin
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).roundToInt()
                    val dy = (event.rawY - startY).roundToInt()
                    val newX = (startMarginLeft + dx).coerceIn(
                        0, (parentWidth - container.width).coerceAtLeast(0)
                    )
                    val newY = (startMarginTop + dy).coerceIn(
                        0, (parentHeight - container.height).coerceAtLeast(0)
                    )
                    lp.leftMargin = newX
                    lp.topMargin = newY
                    container.layoutParams = lp
                    updateBounds(newX, newY, container.width, container.height)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition(lp.leftMargin, lp.topMargin)
                    return true
                }
            }
            return false
        }
    }

    /**
     * Handles resizing the floating keyboard.
     * Resize maintains aspect ratio: only horizontal drag is used to compute new width,
     * height is derived proportionally to keep keyboard proportions correct.
     */
    private inner class ResizeTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startWidth = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val container = floatingContainer ?: return false
            val lp = container.layoutParams as? FrameLayout.LayoutParams ?: return false
            val ctx = container.context
            val wrapper = scaledWrapper ?: return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidth = container.width
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startX).roundToInt()
                    val minW = ctx.dp(MIN_WIDTH_DP)
                    val maxW = parentWidth - lp.leftMargin
                    var newWidth = (startWidth + dx).coerceIn(minW, maxW)
                    // Derive height from width using keyboard's aspect ratio + drag handle
                    val dragHandleH = ctx.dp(DRAG_HANDLE_HEIGHT_DP)
                    val maxH = min(
                        parentHeight - lp.topMargin,
                        (parentHeight * MAX_HEIGHT_FRACTION).roundToInt()
                    )
                    var newHeight = if (wrapper.fullWidth > 0 && wrapper.fullHeight > 0) {
                        val contentHeight =
                            (newWidth.toFloat() * wrapper.fullHeight / wrapper.fullWidth).roundToInt()
                        contentHeight + dragHandleH
                    } else {
                        (newWidth.toFloat() * container.height / container.width).roundToInt()
                    }
                    if (newHeight > maxH) {
                        newHeight = maxH
                        if (wrapper.fullWidth > 0 && wrapper.fullHeight > 0) {
                            val contentH = newHeight - dragHandleH
                            newWidth = (contentH.toFloat() * wrapper.fullWidth / wrapper.fullHeight)
                                .roundToInt().coerceIn(minW, maxW)
                        }
                    }
                    lp.width = newWidth
                    lp.height = newHeight
                    container.layoutParams = lp
                    updateBounds(lp.leftMargin, lp.topMargin, newWidth, newHeight)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Save as percentage
                    if (parentWidth > 0) {
                        val widthPercent = (container.width * 100f / parentWidth).roundToInt()
                            .coerceIn(30, 100)
                        saveSize(widthPercent)
                    }
                    return true
                }
            }
            return false
        }
    }
}
