package com.netgician.rplauncher.ocr

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import javax.imageio.ImageIO

/**
 * End to end: the bundled models read photo-like English and Spanish labels and letters,
 * including blurred, low-contrast and yellow-on-black ones.
 */
class ReadsRealTextTest {

    companion object {
        private lateinit var engine: OcrEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = OcrEngine.fromResources()
        }

        @AfterClass
        @JvmStatic
        fun close() {
            engine.close()
        }

        private val expected = mapOf(
            "en_label_photo" to "Take 2 tablets every 6 hours\nDo not exceed 8 tablets in 24 hours\nKeep out of reach of children",
            "es_label_photo" to "Tome una tableta cada día\nNiños menores de 12 años:\npregunte al médico antes de usar",
            "en_mail_blur_lowcontrast" to "Your appointment is on Monday\nOctober 20 at 10:30 AM\nCall 919-555-0100 to change it",
            "es_mail_yellow_on_black" to "Su cita es el lunes\n¿Necesita cambiarla? Llame al\n919-555-0100, extensión 4",
        )
    }

    private fun load(name: String): RgbImage {
        val stream = requireNotNull(javaClass.getResourceAsStream("/samples/$name.jpg")) { "missing $name" }
        val img = stream.use { ImageIO.read(it) }
        val pixels = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return RgbImage(img.width, img.height, IntArray(pixels.size) { pixels[it] and 0xFFFFFF })
    }

    /** 1.0 for identical text; dashes are normalised, since they all sound the same. */
    private fun similarity(a: String, b: String): Double {
        val x = a.replace('–', '-')
        val y = b.replace('–', '-')
        val d = Array(x.length + 1) { IntArray(y.length + 1) }
        for (i in 0..x.length) d[i][0] = i
        for (j in 0..y.length) d[0][j] = j
        for (i in 1..x.length) for (j in 1..y.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1)
        }
        return 1.0 - d[x.length][y.length].toDouble() / maxOf(x.length, y.length, 1)
    }

    @Test
    fun readsEnglishAndSpanishSamples() {
        for ((name, want) in expected) {
            val got = engine.read(load(name)).text
            val s = similarity(got, want)
            println("$name: ${"%.3f".format(s)}\n$got\n")
            assertTrue("$name read as:\n$got\n(similarity $s)", s >= 0.95)
        }
    }

    @Test
    fun keepsSpanishAccents() {
        val got = engine.read(load("es_label_photo")).text
        for (word in listOf("día", "Niños", "años", "médico")) {
            assertTrue("missing $word in:\n$got", got.contains(word))
        }
    }

    @Test
    fun blankPictureHasNoText() {
        val result = engine.read(RgbImage(640, 480, IntArray(640 * 480) { 0xF0F0EC }))
        assertTrue(result.isEmpty)
    }
}
