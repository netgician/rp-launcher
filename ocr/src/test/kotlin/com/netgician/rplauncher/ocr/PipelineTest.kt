package com.netgician.rplauncher.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {

    @Test
    fun bilinearMatchesTheReference() {
        // 2x2 picture: black, red / green, blue. Values from the Python reference (OpenCV convention).
        val img = RgbImage(2, 2, intArrayOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF))
        val out = Pipeline.bilinear(img, 4, 4)
        val red = (0 until 16).map { out[it * 3] }
        val green = (0 until 16).map { out[it * 3 + 1] }
        val wantRed = listOf(0.0, 63.75, 191.25, 255.0, 0.0, 47.812, 143.438, 191.25, 0.0, 15.938, 47.812, 63.75, 0.0, 0.0, 0.0, 0.0)
        val wantGreen = listOf(0.0, 0.0, 0.0, 0.0, 63.75, 47.812, 15.938, 0.0, 191.25, 143.438, 47.812, 0.0, 255.0, 191.25, 63.75, 0.0)
        for (i in 0 until 16) {
            assertEquals("red $i", wantRed[i], red[i].toDouble(), 0.01)
            assertEquals("green $i", wantGreen[i], green[i].toDouble(), 0.01)
        }
    }

    @Test
    fun bilinearKeepsFlatColours() {
        val img = RgbImage(7, 5, IntArray(35) { 0x336699 })
        val out = Pipeline.bilinear(img, 3, 11)
        for (i in 0 until 33) {
            assertEquals(0x33.toFloat(), out[i * 3], 0.001f)
            assertEquals(0x66.toFloat(), out[i * 3 + 1], 0.001f)
            assertEquals(0x99.toFloat(), out[i * 3 + 2], 0.001f)
        }
    }

    @Test
    fun detectionSizesMatchTheReference() {
        // Values from the Python reference, which rounds halves to even.
        assertEquals(736 to 1088, Pipeline.detSize(720, 1080))
        assertEquals(1280 to 960, Pipeline.detSize(1440, 1080))
        assertEquals(736 to 992, Pipeline.detSize(300, 400))
        assertEquals(1280 to 960, Pipeline.detSize(4000, 3000))
        for ((h, w) in listOf(17 to 900, 3000 to 40, 640 to 480)) {
            val (dh, dw) = Pipeline.detSize(h, w)
            assertTrue(dh % 32 == 0 && dw % 32 == 0 && dh >= 32 && dw >= 32)
        }
    }

    @Test
    fun detectionInputIsBgrNormalised() {
        val img = RgbImage(1, 1, intArrayOf(0xFF0000)) // pure red
        val x = Pipeline.detInput(img, 32, 32)
        val plane = 32 * 32
        assertEquals((0f - 0.485f) / 0.229f, x[0], 1e-4f) // blue plane first
        assertEquals((0f - 0.456f) / 0.224f, x[plane], 1e-4f)
        assertEquals((1f - 0.406f) / 0.225f, x[2 * plane], 1e-4f) // red plane last
    }

    @Test
    fun recognitionInputIsPaddedOnTheRight() {
        val crop = RgbImage(10, 10, IntArray(100) { 0xFFFFFF })
        val (inputWidth, resized) = Pipeline.recWidths(10, 10)
        assertEquals(320, inputWidth)
        assertEquals(48, resized)
        val x = Pipeline.recInput(crop, inputWidth, resized)
        assertEquals(1f, x[0], 1e-5f) // white -> +1
        assertEquals(0f, x[inputWidth - 1], 1e-5f) // padding stays 0
        val (wide, wideResized) = Pipeline.recWidths(1000, 20)
        assertEquals(2400, wide)
        assertEquals(2400, wideResized)
    }

    @Test
    fun twoBlobsBecomeTwoGrownBoxes() {
        val w = 40
        val h = 20
        val prob = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, v: Float) {
            for (y in y0 until y1) for (x in x0 until x1) prob[y * w + x] = v
        }
        fill(2, 2, 18, 8, 0.9f) // strong
        fill(22, 12, 38, 18, 0.9f) // strong
        fill(30, 2, 34, 6, 0.3f) // above threshold but too weak on average: dropped
        fill(10, 15, 11, 18, 0.9f) // 1 pixel wide: dropped
        val boxes = Pipeline.boxes(prob, w, h, w * 2, h * 2) // picture is twice the map size
        assertEquals(2, boxes.size)
        val first = boxes.minByOrNull { it.top }!!
        // 16x6 area grown by 16*6*1.4/(2*22) ≈ 3.05 on each side, then doubled.
        assertTrue(first.left in 0..0 && first.top in 0..0)
        assertEquals(43, first.right)
        assertEquals(23, first.bottom)
    }

    @Test
    fun boxesGroupIntoLinesInReadingOrder() {
        val a = TextBox(200, 10, 300, 40)
        val b = TextBox(10, 14, 150, 42) // same line as a, further left
        val c = TextBox(10, 60, 120, 90)
        val lines = Pipeline.lines(listOf(c, a, b))
        assertEquals(listOf(listOf(b, a), listOf(c)), lines)
    }

    @Test
    fun ctcMergesRepeatsAndDropsBlanks() {
        val chars = Pipeline.classes(listOf("a", "b", "c")) // 0 blank, 1 a, 2 b, 3 c, 4 space
        val classes = chars.size
        // steps: a a blank a b space c c
        val best = intArrayOf(1, 1, 0, 1, 2, 4, 3, 3)
        val probs = FloatArray(best.size * classes)
        best.forEachIndexed { t, c -> probs[t * classes + c] = 0.9f }
        val (text, conf) = Pipeline.ctcDecode(probs, best.size, classes, chars)
        assertEquals("aab c", text)
        assertEquals(0.9f, conf, 1e-5f)
    }

    @Test
    fun dictionaryParsingKeepsEveryLine() {
        assertEquals(listOf("a", "b", " ", "ñ"), Pipeline.parseDict("a\r\nb\n \nñ\n"))
    }

    @Test
    fun cropStaysInsideThePicture() {
        val img = RgbImage(4, 3, IntArray(12) { it })
        val c = img.crop(-5, 1, 99, 99)
        assertEquals(4, c.width)
        assertEquals(2, c.height)
        assertEquals(4, c.pixels[0])
    }
}
