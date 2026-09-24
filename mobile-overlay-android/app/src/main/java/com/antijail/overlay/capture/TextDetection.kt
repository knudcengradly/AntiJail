package com.antijail.overlay.capture



import android.graphics.Bitmap

import com.antijail.overlay.model.Point

import com.google.mlkit.vision.common.InputImage

import com.google.mlkit.vision.text.TextRecognition

import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import kotlinx.coroutines.suspendCancellableCoroutine

import kotlin.coroutines.resume



object TextDetection {

    private val recognizer by lazy {

        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    }



    data class MiningTextScanResult(

        val noMine: Boolean,

        val batteryDead: Boolean,

        val rawText: String = ""

    )



    private fun matchesNoMineMessage(text: String): Boolean {

        val normalized = normalizeGameText(text)

        if (normalized.contains("nothing here to mine")) return true

        if (normalized.contains("nothing to mine")) return true

        if (normalized.contains("here to mine")) return true

        return normalized.contains("nothing") && normalized.contains("mine")

    }



    private fun matchesBatteryDeadMessage(text: String): Boolean {

        val normalized = normalizeGameText(text)

        if (normalized.contains("battery is dead")) return true

        if (normalized.contains("battery dead")) return true

        return normalized.contains("battery") && normalized.contains("dead")

    }



    private fun analyzeMiningText(text: String): MiningTextScanResult =

        MiningTextScanResult(

            noMine = matchesNoMineMessage(text),

            batteryDead = matchesBatteryDeadMessage(text),

            rawText = text

        )



    private fun normalizeGameText(text: String): String =

        text.lowercase()

            .replace('\n', ' ')

            .replace(Regex("[^a-z0-9 ]"), " ")

            .replace(Regex("\\s+"), " ")

            .trim()



    suspend fun scanMiningMessages(bitmap: Bitmap): MiningTextScanResult = suspendCancellableCoroutine { cont ->

        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)

            .addOnSuccessListener { result ->

                if (cont.isActive) cont.resume(analyzeMiningText(result.text))

            }

            .addOnFailureListener {

                if (cont.isActive) cont.resume(MiningTextScanResult(false, false))

            }

    }



    suspend fun containsPhrase(bitmap: Bitmap, phrase: String): Boolean =

        when {

            phrase.contains("battery", ignoreCase = true) ->

                scanMiningMessages(bitmap).batteryDead

            phrase.contains("nothing", ignoreCase = true) ->

                scanMiningMessages(bitmap).noMine

            else ->

                scanMiningMessages(bitmap).rawText.contains(phrase, ignoreCase = true)

        }

    private fun matchesPhrase(text: String, phrase: String): Boolean {
        val hay = normalizeGameText(text)
        val needle = normalizeGameText(phrase)
        if (needle.isEmpty()) return false
        if (hay.contains(needle)) return true
        // Loose match for "Graal Online Era" when OCR splits/drops words.
        val parts = needle.split(' ').filter { it.length >= 3 }
        return parts.isNotEmpty() && parts.all { hay.contains(it) }
    }

    /**
     * Returns the center of the first OCR line/block matching [phrase], in [bitmap] coordinates.
     */
    suspend fun findPhraseCenter(bitmap: Bitmap, phrase: String): Point? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (!cont.isActive) return@addOnSuccessListener
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            if (!matchesPhrase(line.text, phrase)) continue
                            val box = line.boundingBox ?: continue
                            cont.resume(
                                Point(box.exactCenterX(), box.exactCenterY())
                            )
                            return@addOnSuccessListener
                        }
                        if (matchesPhrase(block.text, phrase)) {
                            val box = block.boundingBox
                            if (box != null) {
                                cont.resume(
                                    Point(box.exactCenterX(), box.exactCenterY())
                                )
                                return@addOnSuccessListener
                            }
                        }
                    }
                    cont.resume(null)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

}


