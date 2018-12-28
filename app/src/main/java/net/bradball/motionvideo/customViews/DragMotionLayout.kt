package net.bradball.motionvideo.customViews

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.customview.widget.ViewDragHelper
import net.bradball.motionvideo.R

/**
 * DragMotionLayout extends ConstraintLayout and adds a single additional attribute,
 *  draggableView, which contains the Resource ID of a child view that is draggable.
 *
 *  The view sets up the appropriate listeners and functionality to implement dragging
 *  of the specified child view, within the bounds of this view.
 */
class DragMotionLayout(context: Context, attrSet: AttributeSet?, defStyleAttr: Int): MotionLayout(context, attrSet, defStyleAttr) {
    constructor(context: Context): this(context, null, 0)
    constructor(context: Context, attrSet: AttributeSet): this(context, attrSet, 0)


    /**
     * Android component that actually handles touch events and moves
     * the draggable view around on the screen
     */
    private lateinit var dragHelper: ViewDragHelper

    /**
     * vertical range specifies the maximum vertical range in which the
     * draggable view can be moved. We default it here
     * to the height of this container.
     */
    private var verticalRange = this.height

    /**
     * horizontal range specifies the horizontal range in which the
     * draggable view can be moved. We default it here
     * to the width of this container
     */
    private var horizontalRange = this.width

    /**
     * The Resource ID of the child view that is draggable.
     * This will usually be set in the layout XML, and
     * this property is populated from the xml value in the
     * init block below
     */
    private var draggableViewId: Int = View.NO_ID

    /**
     * The android View object for the child view that is draggable.
     * This is populated below in onFinishInflate
     */
    private var draggableView: View? = null

    /**
     * Is dragging currently enabled?
     * This is set below in the init block via an xml attribute
     * in the layout, but can be changed after initialized.
     */
    var draggingEnabled = true

    init {
        if (attrSet != null) {
            processLayoutAttributes(attrSet, defStyleAttr)
        }
    }

    /**
     * Helper class used by ViewDragHelper to figure out
     * what/how/where the view can be dragged.
     */
    private inner class DragHelperCallback: ViewDragHelper.Callback() {

        /**
         * Is the view that was clicked draggable?
         */
        override fun tryCaptureView(view: View, p1: Int): Boolean {
            return (view.id == draggableViewId)
        }

        /**
         * Set the vertical range in which the view can be dragged.
         */
        override fun getViewVerticalDragRange(child: View): Int {
            return verticalRange
        }

        /**
         * Don't allow the view being dragged to go outside of
         * this vertical value.
         */
        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            val topBound = paddingTop
            val bottomBound = verticalRange - child.height
            return Math.min(Math.max(top, topBound), bottomBound)
        }

        /**
         * Set the horizontal range in which the view can be dragged.
         */
        override fun getViewHorizontalDragRange(child: View): Int {
            return horizontalRange
        }

        /**
         * Don't allow the view being dragged to go outside of
         * this horizontal value
         */
        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            val leftBound = paddingLeft
            val rightBound = horizontalRange - child.width
            return Math.min(Math.max(left, leftBound), rightBound)
        }
    }

    /**
     * Get attributes that were set in the layout XML, and set the
     * corresponding properties.
     */
    private fun processLayoutAttributes(attrSet: AttributeSet, defStyleAttr: Int) {
        val attributes = context.obtainStyledAttributes(
                    attrSet,
                    R.styleable.DragMotionLayout,
                    defStyleAttr,
                    R.style.Component_DragMotionLayout)

        draggableViewId = attributes.getResourceId(R.styleable.DragMotionLayout_draggableView, 0)
        draggingEnabled = attributes.getBoolean(R.styleable.DragMotionLayout_draggingEnabled, true)
        attributes.recycle()
    }

    /**
     * When the view is done inflating, we can setup our drag helper
     * and find the child view that is draggable.
     */
    override fun onFinishInflate() {
        draggableView = findViewById(draggableViewId)
        dragHelper = ViewDragHelper.create(this, 1.0f, DragHelperCallback())
        super.onFinishInflate()
    }

    /**
     * if this view changes size (say, on orientation change), then
     * we need to update our draggable ranges.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        verticalRange = h
        horizontalRange = w
        super.onSizeChanged(w, h, oldw, oldh)
    }


    /**
     * Called by android when the user touches the screen, and should return
     * true if we want to intercept the touch event (to do dragging).
     * If we return true, children won't get a touch event, so we only
     * want to return true if we're going to do some dragging.
     * Note, we don't actually handle the touch event here,
     * we just specify that we WANT to handle the touch event.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return (draggingEnabled && ev != null && isViewTarget(ev) && dragHelper.shouldInterceptTouchEvent(ev))
    }

    /**
     * Handle the touch event, passing it to the drag helper
     * to do some dragging.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return if (event != null && isViewTarget(event)) {
            dragHelper.processTouchEvent(event)
            true
        } else {
            super.onTouchEvent(event)
        }
    }

    /**
     * Is the view that was touched, the child view that is
     * draggable?
     */
    private fun isViewTarget(event: MotionEvent): Boolean {
        if (draggableView == null) {
            return false
        }

        val view = draggableView as View

        val childViewLocation = IntArray(2)
        view.getLocationOnScreen(childViewLocation)
        val upperLimit = childViewLocation[1] + view.measuredHeight
        val lowerLimit = childViewLocation[1]
        val y = event.rawY.toInt()
        return  (y in lowerLimit..upperLimit)
    }
}