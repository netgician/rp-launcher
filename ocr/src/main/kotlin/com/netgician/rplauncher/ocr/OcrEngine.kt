package com.netgician.rplauncher.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

data class OcrLine(val text: String, val boxes: List<TextBox>, val confidence: Float)

data class OcrResult(val lines: List<OcrLine>, val imageWidth: Int, val imageHeight: Int) {
    val text: String get() = lines.joinToString("\n") { it.text }
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Reads printed text in a picture, entirely on the phone. PP-OCRv6 tiny models from
 * PaddleOCR (Apache-2.0): about 6 MB, Latin letters including Spanish accents.
 */
class OcrEngine(detModel: ByteArray, recModel: ByteArray, dictText: String, threads: Int = 2) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det: OrtSession = env.createSession(detModel, options)
    private val rec: OrtSession = env.createSession(recModel, options)
    private val detInput = det.inputNames.first()
    private val recInput = rec.inputNames.first()
    private val classes: List<String> = Pipeline.classes(Pipeline.parseDict(dictText))

    fun read(img: RgbImage): OcrResult {
        val boxes = detect(img)
        val lines = Pipeline.lines(boxes).mapNotNull { line ->
            val parts = line.mapNotNull { box ->
                val (text, conf) = recognize(img.crop(box.left, box.top, box.right, box.bottom))
                if (conf >= Pipeline.DROP_SCORE && text.isNotBlank()) Triple(text.trim(), box, conf) else null
            }
            if (parts.isEmpty()) null
            else OcrLine(
                text = parts.joinToString(" ") { it.first },
                boxes = parts.map { it.second },
                confidence = parts.map { it.third }.average().toFloat(),
            )
        }
        return OcrResult(lines, img.width, img.height)
    }

    private fun detect(img: RgbImage): List<TextBox> {
        val (h, w) = Pipeline.detSize(img.height, img.width)
        val input = Pipeline.detInput(img, h, w)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            det.run(mapOf(detInput to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                val mapH = shape[2].toInt()
                val mapW = shape[3].toInt()
                val buf = out.floatBuffer
                val prob = FloatArray(buf.remaining()).also { buf.get(it) }
                return Pipeline.boxes(prob, mapW, mapH, img.width, img.height)
            }
        }
    }

    private fun recognize(crop: RgbImage): Pair<String, Float> {
        val (inputWidth, resized) = Pipeline.recWidths(crop.width, crop.height)
        val input = Pipeline.recInput(crop, inputWidth, resized)
        val shape = longArrayOf(1, 3, Pipeline.REC_HEIGHT.toLong(), inputWidth.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            rec.run(mapOf(recInput to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val outShape = out.info.shape
                val steps = outShape[1].toInt()
                val classCount = outShape[2].toInt()
                check(classCount == classes.size) { "Model has $classCount classes, dictionary ${classes.size}" }
                val buf = out.floatBuffer
                val probs = FloatArray(buf.remaining()).also { buf.get(it) }
                return Pipeline.ctcDecode(probs, steps, classCount, classes)
            }
        }
    }

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }

    companion object {
        private fun resource(name: String): ByteArray =
            requireNotNull(OcrEngine::class.java.getResourceAsStream("/ocr/$name")) { "Missing /ocr/$name" }
                .use { it.readBytes() }

        /** Loads the bundled models. Takes about a second; do it once, off the main thread. */
        fun fromResources(threads: Int = 2): OcrEngine =
            OcrEngine(resource("det.onnx"), resource("rec.onnx"), resource("dict.txt").decodeToString(), threads)
    }
}
