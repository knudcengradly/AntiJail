package com.antijail.overlay.storage

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

data class SavedSnapshot(
    val file: File,
    val widthPx: Int,
    val heightPx: Int,
    val savedToPcSharedFolder: Boolean
) {
    fun displayPath(): String = file.absolutePath

    fun toastMessage(): String {
        val size = "${widthPx}x${heightPx} px"
        return if (savedToPcSharedFolder) {
            "Saved $size\nPC: XuanZhi9\\Pictures\\${file.name}\nRun sync-snapshots.ps1 to copy into AntiJail/images"
        } else {
            "Saved $size\n${file.absolutePath}\nRun sync-snapshots.ps1 on PC to copy into AntiJail/images"
        }
    }
}

/**
 * Saves PNG snapshots at exact captured pixel size (no scaling).
 *
 * **LDPlayer shared folder (your PC path):** `Documents/XuanZhi9/Pictures` ↔ `/mnt/shared/`
 * Snap appears there immediately. Run `sync-snapshots.ps1` in the AntiJail project to copy
 * into `AntiJail/images/` next to rockimage.png.
 */
object SnapshotStorage {
    private const val TAG = "SnapshotStorage"

    /** LDPlayer maps Pictures to PC XuanZhi9\\Pictures (via /mnt/shared/Pictures). */
    private val PC_SHARED_CANDIDATES = listOf(
        "/mnt/shared/Pictures",
        "/storage/emulated/0/Pictures",
        "/sdcard/Pictures",
        "/mnt/shared",
        "/mnt/shared/images"
    )

    fun save(context: Context, bitmap: Bitmap): SavedSnapshot {
        val w = bitmap.width
        val h = bitmap.height
        val fileName = "snapshot_${w}x${h}_${System.currentTimeMillis()}.png"

        for (basePath in PC_SHARED_CANDIDATES) {
            val dir = File(basePath)
            if (!ensureDir(dir)) continue
            val file = File(dir, fileName)
            if (writePng(bitmap, file)) {
                Log.d(TAG, "Saved snapshot to shared folder: ${file.absolutePath}")
                mirrorToEmulator(bitmap, fileName)
                return SavedSnapshot(file, w, h, savedToPcSharedFolder = true)
            }
        }

        val emulatorDir = File(Environment.getExternalStorageDirectory(), "AntiJail/images")
        ensureDir(emulatorDir)
        val emulatorFile = File(emulatorDir, fileName)
        if (writePng(bitmap, emulatorFile)) {
            Log.d(TAG, "Saved snapshot to emulator storage: ${emulatorFile.absolutePath}")
            return SavedSnapshot(emulatorFile, w, h, savedToPcSharedFolder = false)
        }

        val appDir = File(context.getExternalFilesDir(null), "images").apply { mkdirs() }
        val appFile = File(appDir, fileName)
        writePng(bitmap, appFile)
        return SavedSnapshot(appFile, w, h, savedToPcSharedFolder = false)
    }

    private fun mirrorToEmulator(bitmap: Bitmap, fileName: String) {
        runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "AntiJail/images")
            if (ensureDir(dir)) {
                writePng(bitmap, File(dir, fileName))
            }
        }
    }

    private fun ensureDir(dir: File): Boolean =
        dir.exists() || dir.mkdirs()

    private fun writePng(bitmap: Bitmap, file: File): Boolean =
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "writePng failed: ${file.absolutePath}", e)
            false
        }
}
