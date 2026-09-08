package com.antijail.overlay.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antijail.overlay.R
import com.antijail.overlay.model.DetectionRegion
import com.antijail.overlay.model.SetupLayout
import com.antijail.overlay.model.SpiderClusterResult
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private companion object {
        private const val TAG = "AntiJailCapture"
        private val openCvLock = Any()

        @Volatile
        private var openCvLoaded: Boolean = false

        /**
         * Fixed grass HSV band derived from typical greens #72993c, #608c2b, #456f19 with tolerance
         * (OpenCV CV_8U HSV: H 0–180, S and V 0–255). Detection area is only used to crop the frame;
         * no sampling from the bitmap defines these bounds.
         */
        private val GRASS_HSV_LOWER = Scalar(20.0, 70.0, 40.0)
        private val GRASS_HSV_UPPER = Scalar(60.0, 255.0, 255.0)

        private const val GRASS_COUNT_NONZERO_MIN = 50

        private const val MIN_CROP_W = 4
        private const val MIN_CROP_H = 4

        /** Same list + tolerance as desktop AntiJail.detect_drill_colors / hex_color_matches. */
        private val DRILL_COLORS = listOf("#317d73", "#338072")
        private const val COLOR_TOLERANCE = 22
        private const val MIN_DRILL_PIXELS = 5
        private const val MIN_ROCK_PIXELS = 2

        /** Mined-rock template — match at [VisionConfig.ROCK_MIN_MATCH_SCORE] on opposite side. */
        private val ROCK_MINED_TEMPLATES = intArrayOf(R.drawable.rock)
        private val FIRE_TEMPLATES = intArrayOf(R.drawable.fire1, R.drawable.fire2)
        private val PLANT_TEMPLATE = R.drawable.plant_image
        private val BATTERY_TEMPLATE = R.drawable.battery_image
        private val YES_TEMPLATE = R.drawable.yes_image
        private val BUY_TEMPLATE = R.drawable.buy_image
        private val OK_TEMPLATE = R.drawable.ok_image
        private val THRIFT_SHOP_TEMPLATE = R.drawable.thriftshop
        private val DRILLIMG_TEMPLATE = R.drawable.drillimg
        /** Reuse shared frame if newer than this (hot rock/HP/drill path). */
        private const val SHARED_FRAME_HOT_MAX_AGE_MS = 40L
        /** Slightly looser reuse for slower watchers (fire / OCR). */
        private const val SHARED_FRAME_WATCH_MAX_AGE_MS = 100L
    }
    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListenerRegistered = false

    @Suppress("DEPRECATION")
    private var lastDisplayRotation: Int = -1

    @Volatile
    private var lastFrameMismatchNudgeMs: Long = 0L

    /** One shared full-frame bitmap for parallel watchers — avoids ImageReader contention. */
    private val sharedFrameLock = Any()
    private var sharedFrameBitmap: Bitmap? = null
    private var sharedFrameW: Int = 0
    private var sharedFrameH: Int = 0
    private var sharedFrameMs: Long = 0L

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            mainHandler.post { maybeRestartCaptureForDisplayChange() }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureMediaProjectionForeground()
        return START_STICKY
    }

    /** Required before [MediaProjectionManager.getMediaProjection] on API 34+. */
    private fun ensureMediaProjectionForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                101,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(101, notification)
        }
    }

    fun beginProjection(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "beginProjection: result not OK ($resultCode)")
            return false
        }
        ensureMediaProjectionForeground()
        stopProjection()
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, Intent(data))
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (projection == null) {
            Log.e(TAG, "beginProjection: getMediaProjection returned null")
            return false
        }
        mediaProjection = projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopProjection() }
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, mainHandler)
        refreshDisplayMetrics()
        if (!startCapture()) {
            stopProjection()
            return false
        }
        registerDisplayListener()
        Log.d(TAG, "beginProjection ok w=$screenWidth h=$screenHeight dpi=$screenDensity rot=$lastDisplayRotation")
        return true
    }

    @Suppress("DEPRECATION")
    private fun refreshDisplayMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        lastDisplayRotation = wm.defaultDisplay.rotation
    }

    /**
     * Overlay [DetectionRegion] uses the same coordinate system as [WindowManager] / [getRealMetrics]
     * for the **current** rotation. MediaProjection's [ImageReader] size is fixed until we recreate it —
     * if the user rotates (portrait ↔ landscape), the buffer stays e.g. 1080×2412 while the UI becomes
     * 2412×1080, so crops read the wrong pixels or black bars. Recreate the virtual display on change.
     */
    /** Call before automation when the display may have rotated (e.g. home → portrait → game → landscape). */
    fun refreshCaptureForCurrentDisplay(): Boolean {
        maybeRestartCaptureForDisplayChange()
        return isCaptureReady()
    }

    private fun maybeRestartCaptureForDisplayChange() {
        if (mediaProjection == null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val nw = metrics.widthPixels
        val nh = metrics.heightPixels
        @Suppress("DEPRECATION")
        val nRot = wm.defaultDisplay.rotation
        if (nw == screenWidth && nh == screenHeight && nRot == lastDisplayRotation) return
        Log.d(
            TAG,
            "Display changed: ${screenWidth}x${screenHeight} rot=$lastDisplayRotation -> ${nw}x${nh} rot=$nRot; restarting capture"
        )
        refreshDisplayMetrics()
        startCapture()
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, mainHandler)
        displayListenerRegistered = true
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
    }

    /**
     * Creates the virtual display once per projection. On rotation / metric changes, resizes the
     * existing display — Android 14+ throws SecurityException if [createVirtualDisplay] is called
     * again on the same [MediaProjection].
     */
    private fun startCapture(): Boolean {
        val projection = mediaProjection ?: return false
        clearSharedFrame()
        val existingDisplay = virtualDisplay
        if (existingDisplay != null) {
            releaseImageReaderOnly()
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            existingDisplay.resize(screenWidth, screenHeight, screenDensity)
            existingDisplay.surface = imageReader?.surface
            Log.d(TAG, "startCapture resized to ${screenWidth}x$screenHeight")
            return imageReader != null
        }
        releaseCapture()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        return try {
            virtualDisplay = projection.createVirtualDisplay(
                "AntiJailCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            Log.d(
                TAG,
                "startCapture imageReader=${imageReader != null} virtualDisplay=${virtualDisplay != null}"
            )
            virtualDisplay != null
        } catch (e: SecurityException) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            releaseCapture()
            false
        }
    }

    fun isCaptureReady(): Boolean = mediaProjection != null && imageReader != null && virtualDisplay != null

    private fun clearSharedFrame() {
        synchronized(sharedFrameLock) {
            sharedFrameBitmap?.recycle()
            sharedFrameBitmap = null
            sharedFrameW = 0
            sharedFrameH = 0
            sharedFrameMs = 0L
        }
    }

    /**
     * Refresh the shared full-frame bitmap from ImageReader when older than [maxAgeMs]
     * (or always when [force]). Parallel detectors reuse this instead of each acquiring.
     */
    fun refreshLatestFrame(
        maxAgeMs: Long = SHARED_FRAME_HOT_MAX_AGE_MS,
        force: Boolean = false
    ): Boolean {
        synchronized(sharedFrameLock) {
            val now = SystemClock.elapsedRealtime()
            if (!force && sharedFrameBitmap != null && now - sharedFrameMs <= maxAgeMs) {
                return true
            }
            val image = acquireLatestImageWithRetry(attempts = 8, sleepMs = 20L)
                ?: return sharedFrameBitmap != null
            return try {
                val frameW = image.width
                val frameH = image.height
                if (frameW != screenWidth || frameH != screenHeight) {
                    Log.w(
                        TAG,
                        "Frame ${frameW}x${frameH} != service ${screenWidth}x${screenHeight} — rotation mismatch; nudge capture restart"
                    )
                    if (now - lastFrameMismatchNudgeMs >= 600L) {
                        lastFrameMismatchNudgeMs = now
                        mainHandler.post { maybeRestartCaptureForDisplayChange() }
                    }
                }
                val plane = image.planes.firstOrNull() ?: return sharedFrameBitmap != null
                val buffer = plane.buffer.duplicate().apply { rewind() }
                val bitmapWidth = (plane.rowStride / plane.pixelStride).coerceAtLeast(frameW)
                val existing = sharedFrameBitmap
                val bitmap = if (
                    existing != null &&
                    !existing.isRecycled &&
                    existing.width == bitmapWidth &&
                    existing.height == frameH
                ) {
                    existing
                } else {
                    existing?.recycle()
                    Bitmap.createBitmap(bitmapWidth, frameH, Bitmap.Config.ARGB_8888).also {
                        sharedFrameBitmap = it
                    }
                }
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                sharedFrameW = frameW
                sharedFrameH = frameH
                sharedFrameMs = SystemClock.elapsedRealtime()
                true
            } finally {
                image.close()
            }
        }
    }

    private fun acquireLatestImageWithRetry(attempts: Int = 12, sleepMs: Long = 50L): Image? {
        repeat(attempts) { idx ->
            val img = imageReader?.acquireLatestImage()
            if (img != null) return img
            if (idx < attempts - 1) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        Log.d(TAG, "acquireLatestImageWithRetry: no frame (attempts=$attempts sleepMs=$sleepMs)")
        return null
    }

    private inline fun <T> withMappedRegionFromImage(
        region: DetectionRegion,
        image: Image,
        block: (bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int) -> T
    ): T? {
        refreshDisplayMetrics()
        val mapped = mapRegionToFrame(region, image.width, image.height)
        return withBitmapForRegion(mapped, image, block)
    }

    /**
     * Shared-frame crop for OCR/templates. Copies under the lock so heavy work runs unlocked.
     * [block] gets the crop plus its frame origin for tap coordinates.
     */
    private inline fun <T> withSharedCrop(
        region: DetectionRegion,
        maxAgeMs: Long = SHARED_FRAME_HOT_MAX_AGE_MS,
        forceFresh: Boolean = false,
        block: (crop: Bitmap, originX: Int, originY: Int) -> T
    ): T? {
        if (!refreshLatestFrame(maxAgeMs = maxAgeMs, force = forceFresh)) return null
        var crop: Bitmap? = null
        var originX = 0
        var originY = 0
        synchronized(sharedFrameLock) {
            val frame = sharedFrameBitmap ?: return null
            val fw = sharedFrameW
            val fh = sharedFrameH
            refreshDisplayMetrics()
            val mapped = mapRegionToFrame(region, fw, fh)
            val clip = regionPixelsClampedToFrame(mapped, fw, fh)
            val rw = clip.right - clip.left
            val rh = clip.bottom - clip.top
            if (rw < 1 || rh < 1) return null
            crop = Bitmap.createBitmap(frame, clip.left, clip.top, rw, rh)
            originX = clip.left
            originY = clip.top
        }
        val bitmap = crop ?: return null
        return try {
            block(bitmap, originX, originY)
        } finally {
            bitmap.recycle()
        }
    }

    /** Fast colour scans: refresh shared frame, then read pixels under the lock (no extra crop). */
    private inline fun <T> withSharedFrameScan(
        region: DetectionRegion,
        maxAgeMs: Long = SHARED_FRAME_HOT_MAX_AGE_MS,
        forceFresh: Boolean = false,
        block: (bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int, frameW: Int, frameH: Int) -> T
    ): T? {
        if (!refreshLatestFrame(maxAgeMs = maxAgeMs, force = forceFresh)) return null
        synchronized(sharedFrameLock) {
            val frame = sharedFrameBitmap ?: return null
            val fw = sharedFrameW
            val fh = sharedFrameH
            refreshDisplayMetrics()
            val mapped = mapRegionToFrame(region, fw, fh)
            val clip = regionPixelsClampedToFrame(mapped, fw, fh)
            return block(frame, clip.left, clip.top, clip.right, clip.bottom, fw, fh)
        }
    }

    private inline fun <T> withLatestMappedRegion(
        region: DetectionRegion,
        attempts: Int = 20,
        sleepMs: Long = 75L,
        forceFresh: Boolean = false,
        maxAgeMs: Long = SHARED_FRAME_WATCH_MAX_AGE_MS,
        block: (bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int) -> T
    ): T? {
        @Suppress("UNUSED_PARAMETER")
        val unusedAttempts = attempts
        @Suppress("UNUSED_PARAMETER")
        val unusedSleep = sleepMs
        return withSharedCrop(region, maxAgeMs = maxAgeMs, forceFresh = forceFresh) { crop, _, _ ->
            block(crop, 0, 0, crop.width, crop.height)
        }
    }

    private inline fun <T> withBitmapForRegion(
        region: DetectionRegion,
        image: Image,
        block: (bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int) -> T
    ): T? {
        val frameW = image.width
        val frameH = image.height
        if (frameW != screenWidth || frameH != screenHeight) {
            Log.w(
                TAG,
                "Frame ${frameW}x${frameH} != service ${screenWidth}x${screenHeight} — rotation mismatch; nudge capture restart"
            )
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMismatchNudgeMs >= 600L) {
                lastFrameMismatchNudgeMs = now
                mainHandler.post { maybeRestartCaptureForDisplayChange() }
            }
        }
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val bitmapWidth = (plane.rowStride / plane.pixelStride).coerceAtLeast(frameW)
        val bitmap = Bitmap.createBitmap(bitmapWidth, frameH, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return try {
            val clip = regionPixelsClampedToFrame(region, frameW, frameH)
            block(bitmap, clip.left, clip.top, clip.right, clip.bottom)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Clamps [region] to the capture frame. If the box hangs off-screen (e.g. right edge),
     * naive clamping can yield a 1px-wide strip — [left]=1079 [right]=1080 on a 1080-wide display.
     * In that case we re-expand around the region center using the nominal drill box size.
     */
    private fun regionPixelsClampedToFrame(region: DetectionRegion, sw: Int, sh: Int): ClipRect {
        var left = region.left.toInt().coerceIn(0, sw - 1)
        var top = region.top.toInt().coerceIn(0, sh - 1)
        var right = region.right.toInt().coerceIn(1, sw)
        var bottom = region.bottom.toInt().coerceIn(1, sh)
        if (right <= left) right = kotlin.math.min(left + 1, sw)
        if (bottom <= top) bottom = kotlin.math.min(top + 1, sh)

        val w = right - left
        val h = bottom - top
        if (w >= MIN_CROP_W && h >= MIN_CROP_H) {
            return ClipRect(left, top, right, bottom)
        }

        val cx = ((region.left + region.right) / 2f).roundToInt().coerceIn(0, sw - 1)
        val cy = ((region.top + region.bottom) / 2f).roundToInt().coerceIn(0, sh - 1)
        val nominalW = (region.right - region.left).toInt().coerceAtLeast(MIN_CROP_W)
        val nominalH = (region.bottom - region.top).toInt().coerceAtLeast(MIN_CROP_H)
        val halfW = nominalW / 2
        val halfH = nominalH / 2
        left = (cx - halfW).coerceAtLeast(0)
        right = (cx + halfW).coerceAtMost(sw)
        top = (cy - halfH).coerceAtLeast(0)
        bottom = (cy + halfH).coerceAtMost(sh)

        if (right - left < 2) {
            right = kotlin.math.min(left + nominalW, sw)
            left = (right - nominalW).coerceAtLeast(0)
        }
        if (bottom - top < 2) {
            bottom = kotlin.math.min(top + nominalH, sh)
            top = (bottom - nominalH).coerceAtLeast(0)
        }
        if (right <= left) right = kotlin.math.min(left + 1, sw)
        if (bottom <= top) bottom = kotlin.math.min(top + 1, sh)

        Log.w(
            TAG,
            "regionPixelsClampedToFrame: thin/off-screen crop expanded from center=($cx,$cy) " +
                "to [$left,$top,$right,$bottom] (raw region L=${region.left} T=${region.top} R=${region.right} B=${region.bottom})"
        )
        return ClipRect(left, top, right, bottom)
    }

    private data class ClipRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class DrillScanResult(
        val matchingPixels: Int,
        val centerColorHex: String,
        val regionBounds: String,
        val frameSize: String,
        val detected: Boolean
    )

    /** True when ≥ [MIN_DRILL_PIXELS] pixels anywhere inside [region] match drill colours. */
    fun regionShowsDrillCyan(region: DetectionRegion): Boolean {
        val result = scanDrillCyanRegion(region)
        if (!result.detected) {
            Log.d(TAG, "regionShowsDrillCyan: not detected — $result")
        }
        return result.detected
    }

    /**
     * Scans every pixel in the saved drill box — same algorithm as desktop [AntiJail.detect_drill_colors].
     */
    fun scanDrillCyanRegion(region: DetectionRegion): DrillScanResult {
        refreshDisplayMetrics()
        return withSharedFrameScan(region, maxAgeMs = SHARED_FRAME_HOT_MAX_AGE_MS) { bitmap, left, top, right, bottom, frameW, frameH ->
            var matched = 0
            val cx = ((left + right) / 2).coerceIn(left, max(left, right - 1))
            val cy = ((top + bottom) / 2).coerceIn(top, max(top, bottom - 1))
            val centerPixel = bitmap.getPixel(cx.coerceIn(0, bitmap.width - 1), cy.coerceIn(0, bitmap.height - 1))
            val centerColorHex = String.format("#%06x", centerPixel and 0xFFFFFF)

            for (y in top until bottom) {
                if (y >= frameH || y >= bitmap.height) continue
                for (x in left until right) {
                    if (x >= frameW || x >= bitmap.width) continue
                    val pixel = bitmap.getPixel(x, y)
                    if (pixelMatchesDrillColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                        matched++
                        if (matched >= MIN_DRILL_PIXELS) break
                    }
                }
                if (matched >= MIN_DRILL_PIXELS) break
            }

            val bounds = "[$left,$top,$right,$bottom]"
            val detected = matched >= MIN_DRILL_PIXELS
            Log.d(
                TAG,
                "scanDrillCyanRegion: matched=$matched min=$MIN_DRILL_PIXELS center=$centerColorHex " +
                    "clip=$bounds raw=${formatRegion(region)} frame=${frameW}x${frameH} " +
                    "colors=$DRILL_COLORS tol=$COLOR_TOLERANCE detected=$detected"
            )
            DrillScanResult(
                matchingPixels = matched,
                centerColorHex = centerColorHex,
                regionBounds = bounds,
                frameSize = "${frameW}x${frameH}",
                detected = detected
            )
        } ?: DrillScanResult(
            matchingPixels = 0,
            centerColorHex = "#000000",
            regionBounds = formatRegion(region),
            frameSize = "no_frame",
            detected = false
        )
    }

    /** Maps overlay/UI coordinates to the current capture frame when sizes differ. */
    private fun mapRegionToFrame(region: DetectionRegion, frameW: Int, frameH: Int): DetectionRegion {
        if (frameW == screenWidth && frameH == screenHeight) return region
        val sx = frameW.toFloat() / screenWidth.coerceAtLeast(1)
        val sy = frameH.toFloat() / screenHeight.coerceAtLeast(1)
        Log.w(
            TAG,
            "mapRegionToFrame: metrics=${screenWidth}x${screenHeight} frame=${frameW}x${frameH} " +
                "scale=($sx,$sy) raw=${formatRegion(region)}"
        )
        return DetectionRegion(
            left = region.left * sx,
            top = region.top * sy,
            right = region.right * sx,
            bottom = region.bottom * sy
        )
    }

    /** Desktop AntiJail.hex_color_matches — per-channel tolerance on RGB. */
    private fun hexColorMatches(r: Int, g: Int, b: Int, drillHex: String, tolerance: Int): Boolean {
        val (rT, gT, bT) = hexToRgb(drillHex)
        return abs(r - rT) <= tolerance && abs(g - gT) <= tolerance && abs(b - bT) <= tolerance
    }

    /** Mined-rock match — same algorithm as [pixelMatchesDrillColor]. */
    private fun pixelMatchesRockColor(r: Int, g: Int, b: Int): Boolean {
        val hex = String.format("#%02x%02x%02x", r, g, b)
        if (VisionConfig.ROCK_MINED_COLORS.contains(hex)) return true
        for (rockColor in VisionConfig.ROCK_MINED_COLORS) {
            if (hexColorMatches(r, g, b, rockColor, VisionConfig.ROCK_COLOR_TOLERANCE)) return true
        }
        return false
    }

    private fun pixelMatchesDrillColor(r: Int, g: Int, b: Int): Boolean {
        val hex = String.format("#%02x%02x%02x", r, g, b)
        if (DRILL_COLORS.contains(hex)) return true
        for (drillColor in DRILL_COLORS) {
            if (hexColorMatches(r, g, b, drillColor, COLOR_TOLERANCE)) return true
        }
        return false
    }

    private fun scanDrillColorInImage(
        region: DetectionRegion,
        image: Image,
        minMatches: Int
    ): DrillScanResult {
        val frameW = image.width
        val frameH = image.height
        if (frameW != screenWidth || frameH != screenHeight) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMismatchNudgeMs >= 600L) {
                lastFrameMismatchNudgeMs = now
                mainHandler.post { maybeRestartCaptureForDisplayChange() }
            }
        }
        val result = withBitmapForRegion(region, image) { bitmap, left, top, right, bottom ->
            var matched = 0
            val cx = ((left + right) / 2).coerceIn(left, right - 1)
            val cy = ((top + bottom) / 2).coerceIn(top, bottom - 1)
            val centerPixel = bitmap.getPixel(cx, cy)
            val centerColorHex = String.format("#%06x", centerPixel and 0xFFFFFF)

            // Scan every pixel in the rectangle (not center-only); center hex is diagnostic.
            for (y in top until bottom) {
                for (x in left until right) {
                    if (x >= frameW) continue
                    val pixel = bitmap.getPixel(x, y)
                    if (pixelMatchesDrillColor(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                        matched++
                        if (matched >= minMatches) break
                    }
                }
                if (matched >= minMatches) break
            }

            val bounds = "[$left,$top,$right,$bottom]"
            val detected = matched >= minMatches
            Log.d(
                TAG,
                "scanDrillColorInImage: matched=$matched min=$minMatches center=$centerColorHex " +
                    "clip=$bounds raw=${formatRegion(region)} frame=${frameW}x${frameH} " +
                    "colors=$DRILL_COLORS tol=$COLOR_TOLERANCE detected=$detected"
            )
            DrillScanResult(
                matchingPixels = matched,
                centerColorHex = centerColorHex,
                regionBounds = bounds,
                frameSize = "${frameW}x${frameH}",
                detected = detected
            )
        }
        return result ?: DrillScanResult(
            matchingPixels = 0,
            centerColorHex = "#000000",
            regionBounds = formatRegion(region),
            frameSize = "${frameW}x${frameH}",
            detected = false
        )
    }

    private fun formatRegion(region: DetectionRegion): String =
        "[${region.left.toInt()},${region.top.toInt()},${region.right.toInt()},${region.bottom.toInt()}]"

    /**
     * Crops the latest capture frame to [region] and returns an ARGB bitmap at exact on-screen pixel size.
     * Caller must recycle the returned bitmap when done.
     */
    fun captureRegionBitmap(region: DetectionRegion): Bitmap? {
        refreshDisplayMetrics()
        val image = acquireLatestImageWithRetry(attempts = 20, sleepMs = 75L) ?: return null
        return try {
            val frameW = image.width
            val frameH = image.height
            val mapped = mapRegionToFrame(region, frameW, frameH)
            withBitmapForRegion(mapped, image) { bitmap, left, top, right, bottom ->
                val rw = right - left
                val rh = bottom - top
                if (rw < 1 || rh < 1) return@withBitmapForRegion null
                Bitmap.createBitmap(bitmap, left, top, rw, rh)
            }
        } finally {
            image.close()
        }
    }

    /**
     * Fraction (0..1) of sampled pixels that remain visually close to [baseline].
     * A failed capture is treated as changed; captureRegionBitmap already retries for ~1.5s.
     */
    fun regionSimilarity(region: DetectionRegion, baseline: Bitmap): Float {
        val current = captureRegionBitmap(region) ?: return 0f
        return try {
            bitmapSimilarity(baseline, current)
        } finally {
            runCatching { current.recycle() }
        }
    }

    private fun bitmapSimilarity(a: Bitmap, b: Bitmap): Float {
        val n = 32
        val sa = Bitmap.createScaledBitmap(a, n, n, true)
        val sb = Bitmap.createScaledBitmap(b, n, n, true)
        return try {
            var matchingPixels = 0
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val pa = sa.getPixel(x, y)
                    val pb = sb.getPixel(x, y)
                    val dr = abs(Color.red(pa) - Color.red(pb))
                    val dg = abs(Color.green(pa) - Color.green(pb))
                    val db = abs(Color.blue(pa) - Color.blue(pb))
                    // Small capture/compression variations are allowed per pixel; a visibly
                    // changed pixel no longer gets diluted by unchanged background pixels.
                    if ((dr + dg + db) / 3 <= 24) matchingPixels++
                }
            }
            matchingPixels.toFloat() / (n * n)
        } finally {
            if (sa != a) runCatching { sa.recycle() }
            if (sb != b) runCatching { sb.recycle() }
        }
    }

    fun regionContainsColor(
        region: DetectionRegion,
        targetHex: String,
        tolerance: Int,
        minMatches: Int = 3,
        @Suppress("UNUSED_PARAMETER") scanStep: Int = 2
    ): Boolean {
        val image = acquireLatestImageWithRetry() ?: return false
        return try {
            val (rT, gT, bT) = hexToRgb(targetHex)
            withMappedRegionFromImage(region, image) { bitmap, left, top, right, bottom ->
                var matched = 0
                for (y in top until bottom) {
                    for (x in left until right) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        if (abs(r - rT) <= tolerance && abs(g - gT) <= tolerance && abs(b - bT) <= tolerance) {
                            matched++
                            if (matched >= minMatches) return@withMappedRegionFromImage true
                        }
                    }
                }
                false
            } ?: false
        } finally {
            image.close()
        }
    }

    /** True when at least [minDistinctColors] of [colors] each have ≥ [minPixelsPerColor] hits in [region]. */
    fun regionContainsColorSet(
        region: DetectionRegion,
        colors: List<String>,
        tolerance: Int = 12,
        minDistinctColors: Int,
        minPixelsPerColor: Int = 1
    ): Boolean {
        var found = 0
        for (hex in colors) {
            if (regionContainsColor(region, hex, tolerance, minPixelsPerColor)) found++
        }
        return found >= minDistinctColors
    }

    private val templateCache = mutableMapOf<Int, Bitmap>()

    private fun loadTemplate(resId: Int): Bitmap? {
        templateCache[resId]?.let { return it }
        val decoded = android.graphics.BitmapFactory.decodeResource(resources, resId)
            ?: return null
        val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
        if (copy !== decoded) decoded.recycle()
        templateCache[resId] = copy
        return copy
    }

    private data class TemplateMatchResult(
        val score: Double,
        val centerX: Float,
        val centerY: Float
    )

    /** Best TM_CCOEFF_NORMED match for [template] inside [crop] across [scales]. */
    private fun bestTemplateMatch(
        crop: Bitmap,
        template: Bitmap,
        scales: FloatArray = VisionConfig.TEMPLATE_SCALES
    ): TemplateMatchResult {
        if (!ensureOpenCvLoaded()) return TemplateMatchResult(0.0, 0f, 0f)
        if (crop.width < 4 || crop.height < 4) return TemplateMatchResult(0.0, 0f, 0f)
        var best = TemplateMatchResult(0.0, 0f, 0f)
        for (scale in scales) {
            val tw = (template.width * scale).roundToInt().coerceAtLeast(4)
            val th = (template.height * scale).roundToInt().coerceAtLeast(4)
            if (tw > crop.width || th > crop.height) continue
            val scaled = Bitmap.createScaledBitmap(template, tw, th, true)
            val cropMat = Mat()
            val templMat = Mat()
            val result = Mat()
            try {
                Utils.bitmapToMat(crop, cropMat)
                Utils.bitmapToMat(scaled, templMat)
                Imgproc.matchTemplate(cropMat, templMat, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal > best.score) {
                    best = TemplateMatchResult(
                        mm.maxVal,
                        (mm.maxLoc.x + tw / 2f).toFloat(),
                        (mm.maxLoc.y + th / 2f).toFloat()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "bestTemplateMatch failed scale=$scale", e)
            } finally {
                if (scaled !== template) scaled.recycle()
                cropMat.release()
                templMat.release()
                result.release()
            }
        }
        return best
    }

    /** Best TM_CCOEFF_NORMED score for [template] inside [crop] across [scales]. */
    private fun bestTemplateScore(
        crop: Bitmap,
        template: Bitmap,
        scales: FloatArray = VisionConfig.TEMPLATE_SCALES
    ): Double = bestTemplateMatch(crop, template, scales).score

    fun regionMatchesTemplate(
        region: DetectionRegion,
        templateResId: Int,
        minScore: Double = VisionConfig.ROCK_MIN_MATCH_SCORE,
        scales: FloatArray = VisionConfig.TEMPLATE_SCALES
    ): Boolean {
        val template = loadTemplate(templateResId) ?: return false
        val image = acquireLatestImageWithRetry() ?: return false
        return try {
            withMappedRegionFromImage(region, image) { bitmap, left, top, right, bottom ->
                val rw = right - left
                val rh = bottom - top
                if (rw < 4 || rh < 4) return@withMappedRegionFromImage false
                val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
                try {
                    bestTemplateScore(cropped, template, scales) >= minScore
                } finally {
                    cropped.recycle()
                }
            } ?: false
        } finally {
            image.close()
        }
    }

    private fun subRegion(parent: DetectionRegion, side: String): DetectionRegion {
        val cx = (parent.left + parent.right) / 2f
        val cy = (parent.top + parent.bottom) / 2f
        return when (side.uppercase()) {
            "UP" -> DetectionRegion(parent.left, parent.top, parent.right, cy)
            "DOWN" -> DetectionRegion(parent.left, cy, parent.right, parent.bottom)
            "LEFT" -> DetectionRegion(parent.left, parent.top, cx, parent.bottom)
            "RIGHT" -> DetectionRegion(cx, parent.top, parent.right, parent.bottom)
            else -> parent
        }
    }

    private fun maxTemplateScoreInRegion(region: DetectionRegion, templateIds: List<Int>): Double {
        var best = 0.0
        for (id in templateIds) {
            val template = loadTemplate(id) ?: continue
            val image = acquireLatestImageWithRetry() ?: continue
            try {
                val score = withMappedRegionFromImage(region, image) { bitmap, left, top, right, bottom ->
                    val rw = right - left
                    val rh = bottom - top
                    if (rw < 4 || rh < 4) return@withMappedRegionFromImage 0.0
                    val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
                    try {
                        bestTemplateScore(cropped, template)
                    } finally {
                        cropped.recycle()
                    }
                } ?: 0.0
                best = max(best, score)
            } finally {
                image.close()
            }
        }
        return best
    }

    fun isRockMined(region: DetectionRegion): Boolean =
        regionShowsRockMined(region)

    /** True when ≥ [MIN_ROCK_PIXELS] pixel anywhere inside [region] matches a rock colour. */
    fun regionShowsRockMined(region: DetectionRegion): Boolean {
        val result = scanRockColorsRegion(region)
        if (!result.detected) {
            Log.d(TAG, "regionShowsRockMined: not detected — $result")
        }
        return result.detected
    }

    /** Scans every pixel in [region] — mirrors [scanDrillCyanRegion]. */
    fun scanRockColorsRegion(region: DetectionRegion): DrillScanResult =
        scanRockColorsRegionInternal(region, fastCapture = false)

    /**
     * Post-attack rock path — short frame grabs so mined-rock hits stay near real-time
     * (the default retry loop can take well over a second per poll).
     */
    fun scanRockColorsRegionFast(region: DetectionRegion): DrillScanResult =
        scanRockColorsRegionInternal(region, fastCapture = true)

    private fun scanRockColorsRegionInternal(
        region: DetectionRegion,
        fastCapture: Boolean
    ): DrillScanResult {
        refreshDisplayMetrics()
        val maxAge = if (fastCapture) SHARED_FRAME_HOT_MAX_AGE_MS else SHARED_FRAME_WATCH_MAX_AGE_MS
        return withSharedFrameScan(region, maxAgeMs = maxAge) { bitmap, left, top, right, bottom, frameW, frameH ->
            scanColorInRegionFromBitmap(
                region = region,
                bitmap = bitmap,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                frameW = frameW,
                frameH = frameH,
                minMatches = MIN_ROCK_PIXELS,
                logLabel = "scanRockColorInImage",
                extraLog = "colors=${VisionConfig.ROCK_MINED_COLORS} tol=${VisionConfig.ROCK_COLOR_TOLERANCE}",
                colorMatcher = ::pixelMatchesRockColor
            )
        } ?: DrillScanResult(
            matchingPixels = 0,
            centerColorHex = "#000000",
            regionBounds = formatRegion(region),
            frameSize = "no_frame",
            detected = false
        )
    }

    private fun scanRockColorInImage(
        region: DetectionRegion,
        image: Image,
        minMatches: Int
    ): DrillScanResult =
        scanColorInRegionFromImage(
            region = region,
            image = image,
            minMatches = minMatches,
            logLabel = "scanRockColorInImage",
            extraLog = "colors=${VisionConfig.ROCK_MINED_COLORS} tol=${VisionConfig.ROCK_COLOR_TOLERANCE}",
            colorMatcher = ::pixelMatchesRockColor
        )

    private fun scanColorInRegionFromBitmap(
        region: DetectionRegion,
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        frameW: Int,
        frameH: Int,
        minMatches: Int,
        logLabel: String,
        extraLog: String = "",
        colorMatcher: (r: Int, g: Int, b: Int) -> Boolean
    ): DrillScanResult {
        if (frameW != screenWidth || frameH != screenHeight) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMismatchNudgeMs >= 600L) {
                lastFrameMismatchNudgeMs = now
                mainHandler.post { maybeRestartCaptureForDisplayChange() }
            }
        }
        var matched = 0
        val cx = ((left + right) / 2).coerceIn(left, max(left, right - 1))
        val cy = ((top + bottom) / 2).coerceIn(top, max(top, bottom - 1))
        var centerR = 0
        var centerG = 0
        var centerB = 0

        for (y in top until bottom) {
            if (y >= frameH || y >= bitmap.height) continue
            for (x in left until right) {
                if (x >= frameW || x >= bitmap.width) continue
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (x == cx && y == cy) {
                    centerR = r
                    centerG = g
                    centerB = b
                }
                if (colorMatcher(r, g, b)) {
                    matched++
                    if (matched >= minMatches) break
                }
            }
            if (matched >= minMatches) break
        }

        val centerColorHex = String.format("#%02x%02x%02x", centerR, centerG, centerB)
        val bounds = "[$left,$top,$right,$bottom]"
        val detected = matched >= minMatches
        Log.d(
            TAG,
            "$logLabel: matched=$matched min=$minMatches center=$centerColorHex " +
                "clip=$bounds raw=${formatRegion(region)} frame=${frameW}x${frameH} " +
                "$extraLog detected=$detected"
        )
        return DrillScanResult(
            matchingPixels = matched,
            centerColorHex = centerColorHex,
            regionBounds = bounds,
            frameSize = "${frameW}x${frameH}",
            detected = detected
        )
    }

    /**
     * Reads pixels straight from the capture [Image] plane (no bitmap stride padding).
     * Same full-rectangle loop as drill detection.
     */
    private fun scanColorInRegionFromImage(
        region: DetectionRegion,
        image: Image,
        minMatches: Int,
        logLabel: String,
        extraLog: String = "",
        colorMatcher: (r: Int, g: Int, b: Int) -> Boolean
    ): DrillScanResult {
        val frameW = image.width
        val frameH = image.height
        if (frameW != screenWidth || frameH != screenHeight) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMismatchNudgeMs >= 600L) {
                lastFrameMismatchNudgeMs = now
                mainHandler.post { maybeRestartCaptureForDisplayChange() }
            }
        }
        val clip = regionPixelsClampedToFrame(region, frameW, frameH)
        val plane = image.planes.firstOrNull()
            ?: return DrillScanResult(
                matchingPixels = 0,
                centerColorHex = "#000000",
                regionBounds = formatRegion(region),
                frameSize = "${frameW}x${frameH}",
                detected = false
            )

        val buffer = plane.buffer.duplicate().apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val limit = buffer.limit()

        var matched = 0
        val cx = ((clip.left + clip.right) / 2).coerceIn(clip.left, max(clip.left, clip.right - 1))
        val cy = ((clip.top + clip.bottom) / 2).coerceIn(clip.top, max(clip.top, clip.bottom - 1))
        var centerR = 0
        var centerG = 0
        var centerB = 0

        for (y in clip.top until clip.bottom) {
            if (y >= frameH) continue
            for (x in clip.left until clip.right) {
                if (x >= frameW) continue
                val offset = y * rowStride + x * pixelStride
                if (offset + 2 >= limit) continue
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                if (x == cx && y == cy) {
                    centerR = r
                    centerG = g
                    centerB = b
                }
                if (colorMatcher(r, g, b)) {
                    matched++
                    if (matched >= minMatches) break
                }
            }
            if (matched >= minMatches) break
        }

        val centerColorHex = String.format("#%02x%02x%02x", centerR, centerG, centerB)
        val bounds = "[${clip.left},${clip.top},${clip.right},${clip.bottom}]"
        val detected = matched >= minMatches
        Log.d(
            TAG,
            "$logLabel: matched=$matched min=$minMatches center=$centerColorHex " +
                "clip=$bounds raw=${formatRegion(region)} frame=${frameW}x${frameH} " +
                "$extraLog detected=$detected"
        )
        return DrillScanResult(
            matchingPixels = matched,
            centerColorHex = centerColorHex,
            regionBounds = bounds,
            frameSize = "${frameW}x${frameH}",
            detected = detected
        )
    }

    /** @deprecated Use [regionShowsRockMined]. */
    fun regionContainsRockMinedColors(region: DetectionRegion): Boolean =
        regionShowsRockMined(region)

    /** @deprecated Template matching — use [isRockMined] colour scan instead. */
    fun isRockPresent(region: DetectionRegion): Boolean = isRockMined(region)

    /** Best mined-rock template score in [region] (0.0–1.0). Matches either snapshot. */
    fun rockMatchScore(region: DetectionRegion): Double =
        withLatestMappedRegion(region) { bitmap, left, top, right, bottom ->
            val rw = right - left
            val rh = bottom - top
            if (rw < 4 || rh < 4) return@withLatestMappedRegion 0.0
            val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
            try {
                var best = 0.0
                for (resId in ROCK_MINED_TEMPLATES) {
                    val template = loadTemplate(resId) ?: continue
                    best = max(
                        best,
                        bestTemplateScore(cropped, template, VisionConfig.ROCK_TEMPLATE_SCALES)
                    )
                }
                Log.d(TAG, "rockMatchScore: best=$best region=${formatRegion(region)}")
                best
            } finally {
                cropped.recycle()
            }
        } ?: 0.0

    /** White/cream Graal chat text — fallback when OCR misses pixel font. */
    private fun cropHasLightGameText(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return false
        var light = 0
        val total = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bitmap.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (r >= 170 && g >= 170 && b >= 140) light++
            }
        }
        return light >= max(10, total / 18)
    }

    suspend fun regionScanMiningText(region: DetectionRegion): TextDetection.MiningTextScanResult {
        val result = withLatestMappedRegion(region) { bitmap, left, top, right, bottom ->
            val rw = right - left
            val rh = bottom - top
            if (rw < 4 || rh < 4) return@withLatestMappedRegion TextDetection.MiningTextScanResult(false, false)
            val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
            try {
                val lightText = cropHasLightGameText(cropped)
                val scale = 3
                val scaled = Bitmap.createScaledBitmap(
                    cropped,
                    (rw * scale).coerceAtLeast(1),
                    (rh * scale).coerceAtLeast(1),
                    true
                )
                try {
                    val ocr = TextDetection.scanMiningMessages(scaled)
                    TextDetection.MiningTextScanResult(
                        noMine = lightText || ocr.noMine,
                        batteryDead = ocr.batteryDead,
                        rawText = ocr.rawText
                    )
                } finally {
                    if (scaled !== cropped) scaled.recycle()
                }
            } finally {
                cropped.recycle()
            }
        } ?: TextDetection.MiningTextScanResult(false, false)
        Log.d(
            TAG,
            "regionScanMiningText: noMine=${result.noMine} batteryDead=${result.batteryDead} " +
                "raw='${result.rawText.take(80)}' region=${formatRegion(region)}"
        )
        return result
    }

    suspend fun regionContainsNoMineMessage(region: DetectionRegion): Boolean =
        regionScanMiningText(region).noMine

    suspend fun regionContainsBatteryDeadMessage(region: DetectionRegion): Boolean =
        regionScanMiningText(region).batteryDead

    /**
     * OCR [phrase] inside [region] and return a screen tap at the match center.
     * Upscales the crop (same as mining OCR) so Graal UI text is readable.
     */
    suspend fun findTextTapPoint(region: DetectionRegion, phrase: String): com.antijail.overlay.model.Point? {
        val result = withSharedCrop(
            region,
            maxAgeMs = SHARED_FRAME_WATCH_MAX_AGE_MS,
            forceFresh = true
        ) { crop, originX, originY ->
            if (crop.width < 4 || crop.height < 4) return@withSharedCrop null
            val scale = 3
            val scaled = Bitmap.createScaledBitmap(
                crop,
                (crop.width * scale).coerceAtLeast(1),
                (crop.height * scale).coerceAtLeast(1),
                true
            )
            try {
                val local = TextDetection.findPhraseCenter(scaled, phrase) ?: return@withSharedCrop null
                com.antijail.overlay.model.Point(
                    originX + local.x / scale,
                    originY + local.y / scale
                )
            } finally {
                if (scaled !== crop) scaled.recycle()
            }
        }
        Log.d(
            TAG,
            "findTextTapPoint: phrase='$phrase' tap=${result?.let { "(${it.x},${it.y})" } ?: "null"} " +
                "region=${formatRegion(region)}"
        )
        return result
    }

    private fun pixelMatchesSpiderColor(
        r: Int,
        g: Int,
        b: Int,
        tolerance: Int = VisionConfig.SPIDER_COLOR_TOLERANCE
    ): Int {
        for (i in VisionConfig.SPIDER_BODY_COLORS.indices) {
            if (hexColorMatches(r, g, b, VisionConfig.SPIDER_BODY_COLORS[i], tolerance)) {
                return i
            }
        }
        return -1
    }

    private data class SpiderMatch(val x: Int, val y: Int, val colorIndex: Int)

    /** Player health hit colours in the cyan setup box — go signal after rock is mined. */
    fun regionHasHealthHitSignal(region: DetectionRegion): Boolean =
        regionHasHealthHitSignalInternal(region, fastCapture = false)

    /**
     * Post-rock / combat path — one short frame grab so HP detection stays near-instant
     * (the default path can retry for hundreds of ms per colour).
     */
    fun regionHasHealthHitSignalFast(region: DetectionRegion): Boolean =
        regionHasHealthHitSignalInternal(region, fastCapture = true)

    private fun regionHasHealthHitSignalInternal(
        region: DetectionRegion,
        fastCapture: Boolean
    ): Boolean {
        val maxAge = if (fastCapture) SHARED_FRAME_HOT_MAX_AGE_MS else SHARED_FRAME_WATCH_MAX_AGE_MS
        val targets = VisionConfig.HEALTH_SIGNAL_COLORS.map { hexToRgb(it) }
        val tolerance = VisionConfig.HEALTH_SIGNAL_TOLERANCE
        val minPixels = VisionConfig.HEALTH_SIGNAL_MIN_PIXELS
        val minDistinct = if (fastCapture) {
            VisionConfig.HEALTH_SIGNAL_MIN_COLORS_FAST
        } else {
            VisionConfig.HEALTH_SIGNAL_MIN_COLORS
        }
        return withSharedFrameScan(region, maxAgeMs = maxAge) { bitmap, left, top, right, bottom, _, _ ->
            val hits = IntArray(targets.size)
            for (y in top until bottom) {
                if (y >= bitmap.height) continue
                for (x in left until right) {
                    if (x >= bitmap.width) continue
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    for (i in targets.indices) {
                        if (hits[i] >= minPixels) continue
                        val (rT, gT, bT) = targets[i]
                        if (abs(r - rT) <= tolerance &&
                            abs(g - gT) <= tolerance &&
                            abs(b - bT) <= tolerance
                        ) {
                            hits[i]++
                        }
                    }
                }
            }
            hits.count { it >= minPixels } >= minDistinct
        } ?: false
    }

    /**
     * Pirate combat while fishing — requires at least one pirate HP colour AND player HP red
     * inside the same detection box.
     */
    fun regionHasPirateCombatSignal(region: DetectionRegion): Boolean {
        val hasPirate = VisionConfig.PIRATE_HP_COLORS.any { hex ->
            regionContainsColor(
                region,
                hex,
                VisionConfig.PIRATE_HP_TOLERANCE,
                VisionConfig.PIRATE_HP_MIN_PIXELS
            )
        }
        if (!hasPirate) return false
        return regionContainsColor(
            region,
            VisionConfig.PLAYER_HP_COLOR,
            VisionConfig.PIRATE_HP_TOLERANCE,
            VisionConfig.PIRATE_HP_MIN_PIXELS
        )
    }

    /** True when fire1/fire2 template matches anywhere inside [region] (game screen area). */
    fun regionHasFireSignal(region: DetectionRegion): Boolean =
        fireTemplateMatchScore(region) >= VisionConfig.FIRE_MIN_MATCH_SCORE

    /** Best fire template score in [region] (0.0–1.0). Matches either fire sprite. */
    fun fireTemplateMatchScore(region: DetectionRegion): Double =
        withLatestMappedRegion(region) { bitmap, left, top, right, bottom ->
            val rw = right - left
            val rh = bottom - top
            if (rw < 4 || rh < 4) return@withLatestMappedRegion 0.0
            val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
            try {
                var best = 0.0
                for (resId in FIRE_TEMPLATES) {
                    val template = loadTemplate(resId) ?: continue
                    best = max(
                        best,
                        bestTemplateScore(cropped, template, VisionConfig.FIRE_TEMPLATE_SCALES)
                    )
                }
                Log.d(TAG, "fireTemplateMatchScore: best=$best region=${formatRegion(region)}")
                best
            } finally {
                cropped.recycle()
            }
        } ?: 0.0

    /**
     * Finds a template in [region] and returns the screen tap point (region origin + match center).
     */
    fun findTemplateTapPoint(
        region: DetectionRegion,
        templateResId: Int,
        minScore: Double = VisionConfig.SHOP_UI_MIN_MATCH_SCORE,
        scales: FloatArray = VisionConfig.SHOP_UI_TEMPLATE_SCALES,
        logTag: String = "findTemplateTapPoint"
    ): com.antijail.overlay.model.Point? =
        // Force a fresh frame after gestures so Buy/Yes/OK aren't stale.
        withSharedCrop(region, maxAgeMs = SHARED_FRAME_HOT_MAX_AGE_MS, forceFresh = true) { crop, originX, originY ->
            if (crop.width < 4 || crop.height < 4) return@withSharedCrop null
            val template = loadTemplate(templateResId) ?: return@withSharedCrop null
            val match = bestTemplateMatch(crop, template, scales)
            if (match.score < minScore) {
                Log.d(TAG, "$logTag: no match score=${match.score}")
                return@withSharedCrop null
            }
            val tap = com.antijail.overlay.model.Point(
                originX + match.centerX,
                originY + match.centerY
            )
            Log.d(TAG, "$logTag: score=${match.score} tap=(${tap.x},${tap.y}) region=${formatRegion(region)}")
            tap
        }

    fun findBatteryImageTapPoint(region: DetectionRegion): com.antijail.overlay.model.Point? =
        findTemplateTapPoint(
            region,
            BATTERY_TEMPLATE,
            VisionConfig.BATTERY_MIN_MATCH_SCORE,
            VisionConfig.BATTERY_TEMPLATE_SCALES,
            "findBatteryImageTapPoint"
        )

    fun findYesImageTapPoint(region: DetectionRegion): com.antijail.overlay.model.Point? =
        findTemplateTapPoint(region, YES_TEMPLATE, logTag = "findYesImageTapPoint")

    fun findBuyImageTapPoint(region: DetectionRegion): com.antijail.overlay.model.Point? =
        findTemplateTapPoint(region, BUY_TEMPLATE, logTag = "findBuyImageTapPoint")

    fun findOkImageTapPoint(region: DetectionRegion): com.antijail.overlay.model.Point? =
        findTemplateTapPoint(region, OK_TEMPLATE, logTag = "findOkImageTapPoint")

    /** True when drillimg.png matches inside the T2 tool slot (60% similarity). */
    fun regionShowsDrillOnTool2(region: DetectionRegion): Boolean =
        regionMatchesTemplate(
            region,
            DRILLIMG_TEMPLATE,
            minScore = VisionConfig.DRILL_ON_TOOL2_MIN_MATCH_SCORE,
            scales = VisionConfig.TOOL_SLOT_TEMPLATE_SCALES
        )

    private fun regionContainsCollapseMarker(
        region: DetectionRegion,
        templateResId: Int,
        logTag: String,
        minScore: Double = VisionConfig.COLLAPSE_MARKER_MIN_MATCH_SCORE
    ): Boolean =
        withLatestMappedRegion(region) { bitmap, left, top, right, bottom ->
            val rw = right - left
            val rh = bottom - top
            if (rw < 4 || rh < 4) return@withLatestMappedRegion false
            val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
            try {
                val template = loadTemplate(templateResId) ?: return@withLatestMappedRegion false
                val match = bestTemplateMatch(cropped, template, VisionConfig.COLLAPSE_MARKER_TEMPLATE_SCALES)
                val detected = match.score >= minScore
                Log.d(
                    TAG,
                    "$logTag: detected=$detected score=${match.score} " +
                        "min=$minScore region=${formatRegion(region)}"
                )
                detected
            } finally {
                cropped.recycle()
            }
        } ?: false

    fun regionHasThriftShop(region: DetectionRegion): Boolean =
        regionContainsCollapseMarker(
            region,
            THRIFT_SHOP_TEMPLATE,
            "regionHasThriftShop",
            minScore = VisionConfig.THRIFT_SHOP_MIN_MATCH_SCORE
        )

    /**
     * Finds plant_image.png in [region] and returns which half of the game area it sits in.
     * @return "LEFT", "RIGHT", or null when no confident match.
     */
    fun scanPlantSide(region: DetectionRegion): String? =
        withLatestMappedRegion(region) { bitmap, left, top, right, bottom ->
            val rw = right - left
            val rh = bottom - top
            if (rw < 4 || rh < 4) return@withLatestMappedRegion null
            val cropped = Bitmap.createBitmap(bitmap, left, top, rw, rh)
            try {
                val template = loadTemplate(PLANT_TEMPLATE) ?: return@withLatestMappedRegion null
                val match = bestTemplateMatch(cropped, template, VisionConfig.PLANT_TEMPLATE_SCALES)
                if (match.score < VisionConfig.PLANT_MIN_MATCH_SCORE) {
                    Log.d(TAG, "scanPlantSide: no match score=${match.score}")
                    return@withLatestMappedRegion null
                }
                val side = if (match.centerX < cropped.width / 2f) "LEFT" else "RIGHT"
                Log.d(
                    TAG,
                    "scanPlantSide: side=$side score=${match.score} cx=${match.centerX} half=${cropped.width / 2f}"
                )
                side
            } finally {
                cropped.recycle()
            }
        }

    /**
     * Finds a local cluster of spider body colours (3+ distinct shades co-located).
     * Direction points from the spider area centre toward the cluster centroid.
     */
    fun scanSpiderCluster(region: DetectionRegion): SpiderClusterResult =
        scanSpiderClusterInternal(
            region,
            VisionConfig.SPIDER_MIN_DISTINCT_COLORS,
            VisionConfig.SPIDER_MIN_CLUSTER_PIXELS,
            fastCapture = false
        )

    /** Post-rock / combat — short frame grab + looser UP-spider thresholds. */
    fun scanSpiderClusterFast(region: DetectionRegion): SpiderClusterResult =
        scanSpiderClusterInternal(
            region,
            VisionConfig.SPIDER_MIN_DISTINCT_COLORS_FAST,
            VisionConfig.SPIDER_MIN_CLUSTER_PIXELS,
            fastCapture = true
        )

    fun isSpiderAliveColor(region: DetectionRegion): Boolean =
        scanSpiderClusterInternal(
            region,
            VisionConfig.SPIDER_ALIVE_MIN_DISTINCT_COLORS,
            VisionConfig.SPIDER_MIN_CLUSTER_PIXELS
        ).detected

    fun spiderClusterDirection(region: DetectionRegion): String =
        scanSpiderCluster(region).direction

    private fun spiderTriangleDirection(relX: Float, relY: Float): String =
        when {
            relY < -abs(relX) -> "UP"
            relY > abs(relX) -> "DOWN"
            relX > abs(relY) -> "RIGHT"
            relX < -abs(relY) -> "LEFT"
            else -> {
                // On a diagonal; pick the closest cardinal axis.
                if (abs(relX) >= abs(relY)) {
                    if (relX >= 0f) "RIGHT" else "LEFT"
                } else {
                    if (relY >= 0f) "DOWN" else "UP"
                }
            }
        }

    private fun scanSpiderClusterInternal(
        region: DetectionRegion,
        minDistinctColors: Int,
        minClusterPixels: Int,
        fastCapture: Boolean = false
    ): SpiderClusterResult {
        val colorTol = if (fastCapture) {
            VisionConfig.SPIDER_COLOR_TOLERANCE_FAST
        } else {
            VisionConfig.SPIDER_COLOR_TOLERANCE
        }
        val maxAge = if (fastCapture) SHARED_FRAME_HOT_MAX_AGE_MS else SHARED_FRAME_WATCH_MAX_AGE_MS
        return withSharedFrameScan(region, maxAgeMs = maxAge) { bitmap, left, top, right, bottom, frameW, frameH ->
            val matches = ArrayList<SpiderMatch>(64)
            for (y in top until bottom) {
                if (y >= frameH || y >= bitmap.height) continue
                for (x in left until right) {
                    if (x >= frameW || x >= bitmap.width) continue
                    val pixel = bitmap.getPixel(x, y)
                    val colorIndex = pixelMatchesSpiderColor(
                        Color.red(pixel),
                        Color.green(pixel),
                        Color.blue(pixel),
                        colorTol
                    )
                    if (colorIndex >= 0) {
                        matches.add(SpiderMatch(x, y, colorIndex))
                    }
                }
            }

            if (matches.size < minClusterPixels) {
                return@withSharedFrameScan SpiderClusterResult(false, 0)
            }

            val window = if (fastCapture) {
                VisionConfig.SPIDER_CLUSTER_WINDOW_PX_FAST
            } else {
                VisionConfig.SPIDER_CLUSTER_WINDOW_PX
            }
            val step = if (fastCapture) {
                VisionConfig.SPIDER_CLUSTER_STEP_PX_FAST
            } else {
                VisionConfig.SPIDER_CLUSTER_STEP_PX
            }
            var bestDistinct = 0
            var bestMatches: List<SpiderMatch> = emptyList()

            var wy = top
            while (wy < bottom) {
                val wyEnd = min(wy + window, bottom)
                var wx = left
                while (wx < right) {
                    val wxEnd = min(wx + window, right)
                    val inWindow = matches.filter { it.x in wx until wxEnd && it.y in wy until wyEnd }
                    val distinct = inWindow.map { it.colorIndex }.toSet().size
                    if (distinct > bestDistinct ||
                        (distinct == bestDistinct && inWindow.size > bestMatches.size)
                    ) {
                        bestDistinct = distinct
                        bestMatches = inWindow
                    }
                    wx += step
                }
                wy += step
            }

            if (bestDistinct < minDistinctColors || bestMatches.size < minClusterPixels) {
                Log.d(
                    TAG,
                    "scanSpiderCluster: no cluster distinct=$bestDistinct pixels=${bestMatches.size} " +
                        "region=${formatRegion(region)}"
                )
                return@withSharedFrameScan SpiderClusterResult(false, bestDistinct)
            }

            val centroidX = bestMatches.map { it.x }.average().toFloat()
            val centroidY = bestMatches.map { it.y }.average().toFloat()
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val votes = linkedMapOf("UP" to 0, "RIGHT" to 0, "DOWN" to 0, "LEFT" to 0)
            val distinctByDir = linkedMapOf(
                "UP" to HashSet<Int>(),
                "RIGHT" to HashSet(),
                "DOWN" to HashSet(),
                "LEFT" to HashSet()
            )
            for (m in bestMatches) {
                val dir = spiderTriangleDirection(m.x - centerX, m.y - centerY)
                votes[dir] = (votes[dir] ?: 0) + 1
                distinctByDir[dir]?.add(m.colorIndex)
            }

            val centroidDx = centroidX - centerX
            val centroidDy = centroidY - centerY
            val centroidFallback = when {
                abs(centroidDx) > abs(centroidDy) -> if (centroidDx > 0) "RIGHT" else "LEFT"
                else -> if (centroidDy > 0) "DOWN" else "UP"
            }

            val direction = votes.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { distinctByDir[it.key]?.size ?: 0 }
                        .thenBy { if (it.key == centroidFallback) 0 else 1 }
                )
                .first()
                .key

            Log.d(
                TAG,
                "scanSpiderCluster: detected distinct=$bestDistinct pixels=${bestMatches.size} " +
                    "dir=$direction votes=$votes centroid=($centroidX,$centroidY) region=${formatRegion(region)}"
            )
            SpiderClusterResult(
                detected = true,
                distinctColors = bestDistinct,
                direction = direction
            )
        } ?: SpiderClusterResult(detected = false, distinctColors = 0)
    }

    /**
     * True when a local cluster of the battery-dead landmark colour (#9c7952) is visible in [region].
     */
    fun regionHasBatteryLandmarkCluster(region: DetectionRegion): Boolean {
        val (rT, gT, bT) = hexToRgb(VisionConfig.BATTERY_LANDMARK_COLOR)
        val tolerance = VisionConfig.BATTERY_LANDMARK_TOLERANCE
        return withSharedFrameScan(region, maxAgeMs = SHARED_FRAME_WATCH_MAX_AGE_MS, forceFresh = true) {
                bitmap, left, top, right, bottom, frameW, frameH ->
            val matches = ArrayList<Pair<Int, Int>>(64)
            for (y in top until bottom) {
                if (y >= frameH || y >= bitmap.height) continue
                for (x in left until right) {
                    if (x >= frameW || x >= bitmap.width) continue
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    if (abs(r - rT) <= tolerance && abs(g - gT) <= tolerance && abs(b - bT) <= tolerance) {
                        matches.add(x to y)
                    }
                }
            }

            if (matches.size < VisionConfig.BATTERY_LANDMARK_MIN_CLUSTER_PIXELS) {
                return@withSharedFrameScan false
            }

            val window = VisionConfig.BATTERY_LANDMARK_CLUSTER_WINDOW_PX
            val step = VisionConfig.BATTERY_LANDMARK_CLUSTER_STEP_PX
            val minPixels = VisionConfig.BATTERY_LANDMARK_MIN_CLUSTER_PIXELS
            var bestCount = 0

            var wy = top
            while (wy < bottom) {
                val wyEnd = min(wy + window, bottom)
                var wx = left
                while (wx < right) {
                    val wxEnd = min(wx + window, right)
                    var count = 0
                    for ((mx, my) in matches) {
                        if (mx in wx until wxEnd && my in wy until wyEnd) {
                            count++
                            if (count >= minPixels) {
                                Log.d(
                                    TAG,
                                    "batteryLandmark: cluster found pixels=$count region=${formatRegion(region)}"
                                )
                                return@withSharedFrameScan true
                            }
                        }
                    }
                    if (count > bestCount) bestCount = count
                    wx += step
                }
                wy += step
            }

            Log.d(
                TAG,
                "batteryLandmark: not found bestCluster=$bestCount region=${formatRegion(region)}"
            )
            false
        } ?: false
    }

    /** @deprecated Colour-only spider scan — use [scanSpiderCluster]. */
    fun detectSpider(spiderArea: DetectionRegion, @Suppress("UNUSED_PARAMETER") healthArea: DetectionRegion): SpiderClusterResult =
        scanSpiderCluster(spiderArea)

    /** @deprecated Use [isSpiderAliveColor]. */
    fun isSpiderAlive(spiderArea: DetectionRegion): Boolean = isSpiderAliveColor(spiderArea)

    /** @deprecated Use [spiderClusterDirection]. */
    fun spiderDirection(spiderArea: DetectionRegion): String = spiderClusterDirection(spiderArea)

    /**
     * Crops the latest frame to [region], converts ARGB bitmap → Mat (BGR), `cvtColor(BGR2HSV)`,
     * `Core.inRange` with fixed grass bounds (not learned from the crop), `countNonZero` > 50.
     */
    fun regionShowsDrillGreen(region: DetectionRegion): Boolean {
        val image = acquireLatestImageWithRetry()
        if (image == null) {
            Log.d(TAG, "regionShowsDrillGreen: no image (projection=${mediaProjection != null} reader=${imageReader != null})")
            return false
        }
        return try {
            withBitmapForRegion(region, image) { bitmap, left, top, right, bottom ->
                val rw = right - left
                val rh = bottom - top
                if (rw < 2 || rh < 2) {
                    Log.d(TAG, "regionShowsDrillGreen: degenerate region left=$left top=$top right=$right bottom=$bottom")
                    return@withBitmapForRegion false
                }
                val cropped = runCatching {
                    Bitmap.createBitmap(bitmap, left, top, rw, rh)
                }.getOrElse { e ->
                    Log.w(TAG, "regionShowsDrillGreen: createBitmap failed", e)
                    return@withBitmapForRegion false
                }
                try {
                    if (!ensureOpenCvLoaded()) {
                        Log.e(TAG, "regionShowsDrillGreen: OpenCV not loaded")
                        return@withBitmapForRegion false
                    }
                    val greenPixels = grassMaskNonZeroCount(cropped)
                    val detected = greenPixels > GRASS_COUNT_NONZERO_MIN
                    Log.d(
                        TAG,
                        "regionShowsDrillGreen countNonZero=$greenPixels (min=$GRASS_COUNT_NONZERO_MIN) detected=$detected " +
                            "crop=[$left,$top,$right,$bottom]"
                    )
                    detected
                } finally {
                    cropped.recycle()
                }
            } ?: false
        } finally {
            image.close()
        }
    }

    private fun ensureOpenCvLoaded(): Boolean {
        if (openCvLoaded) return true
        synchronized(openCvLock) {
            if (openCvLoaded) return true
            val ok = try {
                OpenCVLoader.initLocal() || run {
                    @Suppress("DEPRECATION")
                    OpenCVLoader.initDebug()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "OpenCV init error", e)
                false
            }
            openCvLoaded = ok
            if (ok) Log.d(TAG, "OpenCV loaded")
            else Log.e(TAG, "OpenCV failed to load")
            return ok
        }
    }

    /** Bitmap (ARGB_8888) -> RGBA Mat -> explicit RGB/BGR -> HSV -> inRange -> max(countNonZero). */
    private fun grassMaskNonZeroCount(cropped: Bitmap): Int {
        val rgba = Mat()
        val rgb = Mat()
        val bgr = Mat()
        val hsvBgr = Mat()
        val hsvRgb = Mat()
        val maskBgr = Mat()
        val maskRgb = Mat()
        return try {
            Utils.bitmapToMat(cropped, rgba) // Android bitmap -> RGBA
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            Imgproc.cvtColor(bgr, hsvBgr, Imgproc.COLOR_BGR2HSV)
            Core.inRange(hsvBgr, GRASS_HSV_LOWER, GRASS_HSV_UPPER, maskBgr)
            val countBgr = Core.countNonZero(maskBgr)

            Imgproc.cvtColor(rgb, hsvRgb, Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsvRgb, GRASS_HSV_LOWER, GRASS_HSV_UPPER, maskRgb)
            val countRgb = Core.countNonZero(maskRgb)

            val selected = max(countBgr, countRgb)
            val avgRgb = Core.mean(rgb)
            val avgHsv = Core.mean(hsvRgb)
            Log.d(
                TAG,
                "grassMaskNonZeroCount bgr=$countBgr rgb=$countRgb selected=$selected " +
                    "avgRgb=[${avgRgb.`val`[0]},${avgRgb.`val`[1]},${avgRgb.`val`[2]}] " +
                    "avgHsv=[${avgHsv.`val`[0]},${avgHsv.`val`[1]},${avgHsv.`val`[2]}]"
            )
            selected
        } catch (e: Exception) {
            Log.w(TAG, "grassMaskNonZeroCount failed", e)
            -1
        } finally {
            rgba.release()
            rgb.release()
            bgr.release()
            hsvBgr.release()
            hsvRgb.release()
            maskBgr.release()
            maskRgb.release()
        }
    }

    fun regionContainsGreenSimilarity(
        region: DetectionRegion,
        similarity: Float = 0.70f,
        minMatches: Int = 8
    ): Boolean {
        val image = acquireLatestImageWithRetry() ?: return false
        return try {
            withBitmapForRegion(region, image) { bitmap, left, top, right, bottom ->
                var matched = 0
                val maxBlueRed = ((1f - similarity) * 255f).toInt().coerceAtLeast(20)
                for (y in top until bottom step 2) {
                    for (x in left until right step 2) {
                        val pixel = bitmap.getPixel(x, y)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)
                        val dominantGap = g - max(r, b)
                        if (dominantGap >= maxBlueRed && g >= 85) {
                            matched++
                            if (matched >= minMatches) return@withBitmapForRegion true
                        }
                    }
                }
                false
            } ?: false
        } finally {
            image.close()
        }
    }

    private fun hexToRgb(hex: String): Triple<Int, Int, Int> {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16)
        val g = clean.substring(2, 4).toInt(16)
        val b = clean.substring(4, 6).toInt(16)
        return Triple(r, g, b)
    }

    private fun buildNotification(): Notification {
        val channelId = "antijail_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "AntiJail Capture", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Screen capture is active for drill detection")
            .setOngoing(true)
            .build()
    }

    private fun releaseImageReaderOnly() {
        imageReader?.close()
        imageReader = null
    }

    private fun releaseCapture() {
        clearSharedFrame()
        virtualDisplay?.release()
        releaseImageReaderOnly()
        virtualDisplay = null
    }

    private fun stopProjection() {
        unregisterDisplayListener()
        releaseCapture()
        projectionCallback?.let { callback ->
            mediaProjection?.unregisterCallback(callback)
        }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }
}
