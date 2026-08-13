package com.antijail.overlay.accessibility



import android.accessibilityservice.AccessibilityService

import android.accessibilityservice.GestureDescription

import android.graphics.Path

import android.view.accessibility.AccessibilityEvent

import com.antijail.overlay.model.Point



class AutoClickAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit



    override fun onInterrupt() = Unit



    fun tap(point: Point, holdMs: Long = 50L, onDone: (() -> Unit)? = null) {

        val path = Path().apply { moveTo(point.x, point.y) }

        val gesture = GestureDescription.Builder()

            .addStroke(GestureDescription.StrokeDescription(path, 0L, holdMs))

            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {

            override fun onCompleted(gestureDescription: GestureDescription?) {

                onDone?.invoke()

            }

            override fun onCancelled(gestureDescription: GestureDescription?) {

                onDone?.invoke()

            }

        }, null)

    }



    fun drag(start: Point, end: Point, durationMs: Long = 250L, onDone: (() -> Unit)? = null) {

        val path = Path().apply {

            moveTo(start.x, start.y)

            lineTo(end.x, end.y)

        }

        val gesture = GestureDescription.Builder()

            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))

            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {

            override fun onCompleted(gestureDescription: GestureDescription?) {

                onDone?.invoke()

            }

            override fun onCancelled(gestureDescription: GestureDescription?) {

                onDone?.invoke()

            }

        }, null)

    }



    /** Drag to [end], then hold finger at [end] for [holdMs] before lifting (joystick hold). */

    fun dragWithHoldAtEnd(

        start: Point,

        end: Point,

        dragDurationMs: Long = 220L,

        holdMs: Long = 0L,

        onDone: (() -> Unit)? = null

    ) {

        val dragPath = Path().apply {

            moveTo(start.x, start.y)

            lineTo(end.x, end.y)

        }

        val builder = GestureDescription.Builder()

            .addStroke(GestureDescription.StrokeDescription(dragPath, 0L, dragDurationMs))

        if (holdMs > 0L) {

            val holdPath = Path().apply { moveTo(end.x, end.y) }

            builder.addStroke(GestureDescription.StrokeDescription(holdPath, dragDurationMs, holdMs))

        }

        dispatchGesture(builder.build(), object : GestureResultCallback() {

            override fun onCompleted(gestureDescription: GestureDescription?) {

                onDone?.invoke()

            }

            override fun onCancelled(gestureDescription: GestureDescription?) {

                onDone?.invoke()

            }

        }, null)

    }



    fun cancelPendingGestures() {

        // Start a tiny neutral gesture to interrupt any currently queued/active one.

        val path = Path().apply { moveTo(1f, 1f) }

        val gesture = GestureDescription.Builder()

            .addStroke(GestureDescription.StrokeDescription(path, 0L, 1L))

            .build()

        dispatchGesture(gesture, null, null)

    }

    /** Device Back — used by location-3 return-to-game before resuming mining. */
    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)



    companion object {

        @Volatile

        var instance: AutoClickAccessibilityService? = null

            private set

    }



    override fun onServiceConnected() {

        super.onServiceConnected()

        instance = this

    }



    override fun onDestroy() {

        instance = null

        super.onDestroy()

    }

}


