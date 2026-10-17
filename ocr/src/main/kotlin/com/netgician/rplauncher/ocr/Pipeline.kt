package com.netgician.rplauncher.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** A picture as packed 0xRRGGBB ints (alpha ignored), row by row. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad image size" }
    }

    /** A copy of the area [left, right) x [top, bottom), clipped to the picture. */
    fun crop(left: Int, top: Int, right: Int, bottom: Int): RgbImage {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        val w = r - l
        val out = IntArray(w * (b - t))
        for (y in t until b) System.arraycopy(pixels, y * width + l, out, (y - t) * w, w)
        return RgbImage(w, b - t, out)
    }
}

/** A text area in picture pixels: [left, right) x [top, bottom). */
data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
}

/** The image maths of the pipeline, kept free of ONNX Runtime so it can be tested on its own. */
object Pipeline {
    // Detection normalisation, applied in B, G, R order exactly as PaddleOCR does.
    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    const val DET_LIMIT = 736
    const val DET_CAP = 1280
    const val DET_THRESH = 0.2f
    const val BOX_THRESH = 0.4f
    const val UNCLIP = 1.4f
    const val REC_HEIGHT = 48
    const val REC_MIN_WIDTH = 320
    const val DROP_SCORE = 0.5f

    /**
     * Bilinear resize with OpenCV's INTER_LINEAR pixel-centre convention.
     * Returns height x width x 3 floats in R, G, B order.
     */
    fun bilinear(img: RgbImage, newWidth: Int, newHeight: Int): FloatArray {
        val out = FloatArray(newWidth * newHeight * 3)
        val sx = img.width.toFloat() / newWidth
        val sy = img.height.toFloat() / newHeight
        val x0s = IntArray(newWidth)
        val x1s = IntArray(newWidth)
        val fxs = FloatArray(newWidth)
        for (x in 0 until newWidth) {
            val src = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (img.width - 1).toFloat())
            val x0 = src.toInt()
            x0s[x] = x0
            x1s[x] = min(x0 + 1, img.width - 1)
            fxs[x] = src - x0
        }
        for (y in 0 until newHeight) {
            val src = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (img.height - 1).toFloat())
            val y0 = src.toInt()
            val y1 = min(y0 + 1, img.height - 1)
            val fy = src - y0
            val row0 = y0 * img.width
            val row1 = y1 * img.width
            for (x in 0 until newWidth) {
                val fx = fxs[x]
                val a = img.pixels[row0 + x0s[x]]
                val b = img.pixels[row0 + x1s[x]]
                val c = img.pixels[row1 + x0s[x]]
                val d = img.pixels[row1 + x1s[x]]
                val o = (y * newWidth + x) * 3
                for (ch in 0..2) {
                    val shift = 16 - ch * 8
                    val top = ((a shr shift) and 0xFF) * (1 - fx) + ((b shr shift) and 0xFF) * fx
                    val bottom = ((c shr shift) and 0xFF) * (1 - fx) + ((d shr shift) and 0xFF) * fx
                    out[o + ch] = top * (1 - fy) + bottom * fy
                }
            }
        }
        return out
    }

    /** Detection input size: shortest side up to [DET_LIMIT], longest capped, both multiples of 32. */
    fun detSize(height: Int, width: Int): Pair<Int, Int> {
        var ratio = if (min(height, width) < DET_LIMIT) DET_LIMIT.toDouble() / min(height, width) else 1.0
        if (max(height, width) * ratio > DET_CAP) ratio = DET_CAP.toDouble() / max(height, width)
        val h = max((round((height * ratio).toInt() / 32.0) * 32).toInt(), 32)
        val w = max((round((width * ratio).toInt() / 32.0) * 32).toInt(), 32)
        return h to w
    }

    /** CHW floats in B, G, R planes, normalised for the detector. */
    fun detInput(img: RgbImage, height: Int, width: Int): FloatArray {
        val rgb = bilinear(img, width, height)
        val plane = width * height
        val out = FloatArray(plane * 3)
        for (i in 0 until plane) {
            for (c in 0..2) {
                val v = rgb[i * 3 + (2 - c)] // c = 0 is blue
                out[c * plane + i] = (v / 255f - DET_MEAN[c]) / DET_STD[c]
            }
        }
        return out
    }

    /** The recogniser's input width for a crop, and the width the crop itself is resized to. */
    fun recWidths(cropWidth: Int, cropHeight: Int): Pair<Int, Int> {
        val ratio = cropWidth.toDouble() / cropHeight
        val inputWidth = max(REC_MIN_WIDTH, (REC_HEIGHT * ratio).toInt())
        val resized = min(inputWidth, ceil(REC_HEIGHT * ratio).toInt()).coerceAtLeast(1)
        return inputWidth to resized
    }

    /** CHW floats in B, G, R planes scaled to -1..1, padded on the right with zeros. */
    fun recInput(crop: RgbImage, inputWidth: Int, resizedWidth: Int): FloatArray {
        val rgb = bilinear(crop, resizedWidth, REC_HEIGHT)
        val plane = inputWidth * REC_HEIGHT
        val out = FloatArray(plane * 3)
        for (y in 0 until REC_HEIGHT) for (x in 0 until resizedWidth) {
            val i = (y * resizedWidth + x) * 3
            val o = y * inputWidth + x
            for (c in 0..2) out[c * plane + o] = (rgb[i + (2 - c)] / 255f - 0.5f) / 0.5f
        }
        return out
    }

    /**
     * Turns the detector's probability map into text boxes in picture pixels: connected
     * areas above [DET_THRESH], kept if their mean score passes [BOX_THRESH], grown by the
     * DB "unclip" distance, then scaled back to the picture.
     */
    fun boxes(
        prob: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): List<TextBox> {
        val label = IntArray(prob.size)
        val stack = IntArray(prob.size)
        val result = mutableListOf<TextBox>()
        var next = 0
        for (start in prob.indices) {
            if (prob[start] <= DET_THRESH || label[start] != 0) continue
            next++
            var top = 0
            stack[top++] = start
            label[start] = next
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            var sum = 0.0
            var count = 0
            while (top > 0) {
                val p = stack[--top]
                val x = p % mapWidth
                val y = p / mapWidth
                sum += prob[p]
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) top = push(p - 1, prob, label, stack, top, next)
                if (x < mapWidth - 1) top = push(p + 1, prob, label, stack, top, next)
                if (y > 0) top = push(p - mapWidth, prob, label, stack, top, next)
                if (y < mapHeight - 1) top = push(p + mapWidth, prob, label, stack, top, next)
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < 3) continue
            if (sum / count < BOX_THRESH) continue
            val d = bw.toFloat() * bh * UNCLIP / (2f * (bw + bh))
            val sx = imageWidth.toFloat() / mapWidth
            val sy = imageHeight.toFloat() / mapHeight
            val left = max(0, ((minX - d) * sx).toInt())
            val topY = max(0, ((minY - d) * sy).toInt())
            val right = min(imageWidth, ceil((maxX + 1 + d) * sx).toInt())
            val bottom = min(imageHeight, ceil((maxY + 1 + d) * sy).toInt())
            if (right - left > 3 && bottom - topY > 3) result += TextBox(left, topY, right, bottom)
        }
        return result
    }

    private fun push(p: Int, prob: FloatArray, label: IntArray, stack: IntArray, top: Int, id: Int): Int {
        if (prob[p] <= DET_THRESH || label[p] != 0) return top
        label[p] = id
        stack[top] = p
        return top + 1
    }

    /** Groups boxes into lines, top to bottom, each line left to right. */
    fun lines(boxes: List<TextBox>): List<List<TextBox>> {
        val lines = mutableListOf<MutableList<TextBox>>()
        for (b in boxes.sortedWith(compareBy({ it.top }, { it.left }))) {
            val line = lines.firstOrNull { l ->
                val ref = l.first()
                kotlin.math.abs(b.centerY - ref.centerY) < min(b.height, ref.height) / 2f
            }
            if (line != null) line += b else lines += mutableListOf(b)
        }
        return lines.map { l -> l.sortedBy { it.left } }
    }

    /**
     * Greedy CTC decoding: best class per step, repeats merged, blanks (class 0) dropped.
     * Returns the text and the mean confidence of the kept characters.
     */
    fun ctcDecode(probs: FloatArray, steps: Int, classes: Int, chars: List<String>): Pair<String, Float> {
        val text = StringBuilder()
        var confidence = 0.0
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            var best = 0
            var bestP = probs[t * classes]
            for (c in 1 until classes) {
                val p = probs[t * classes + c]
                if (p > bestP) { best = c; bestP = p }
            }
            if (best != previous && best != 0) {
                text.append(chars.getOrElse(best) { "" })
                confidence += bestP
                kept++
            }
            previous = best
        }
        return text.toString() to if (kept == 0) 0f else (confidence / kept).toFloat()
    }

    /** The recogniser's classes: blank, the dictionary, then a space. */
    fun classes(dictLines: List<String>): List<String> = listOf("") + dictLines + " "

    fun parseDict(text: String): List<String> {
        val lines = text.split("\n").map { it.removeSuffix("\r") }
        return if (lines.lastOrNull() == "") lines.dropLast(1) else lines
    }
}
