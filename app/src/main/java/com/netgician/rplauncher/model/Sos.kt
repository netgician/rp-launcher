package com.netgician.rplauncher.model

import java.util.Locale
import kotlin.math.roundToInt

/** Where the phone is: a position, how sure it is, when it was found, and the street address if known. */
data class Place(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float?,
    val fixTime: Long,
    val address: String?,
)

/** The words of an SOS, and of "Where am I". */
object Sos {
    val COUNTDOWN_CHOICES = listOf(3, 5, 10)

    /** A map link anyone can open, on any phone. */
    fun mapLink(lat: Double, lng: Double): String =
        String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lng)

    private fun meters(p: Place): Int? = p.accuracyMeters?.let { (it.roundToInt() / 5 * 5).coerceAtLeast(5) }

    /** The emergency text. Short and plain, so it reads well on any phone. */
    fun message(place: Place?, fixTimeText: String, language: String = "en"): String {
        val es = language == "es"
        if (place == null) {
            return if (es) "SOS: Necesito ayuda. Mi ubicación no está disponible. Enviado por RP Launcher."
            else "SOS: I need help. My location isn't available. Sent by RP Launcher."
        }
        val near = place.address?.let { if (es) " Estoy cerca de $it." else " I'm near $it." } ?: ""
        val within = meters(place)?.let { if (es) "a $it m, " else "within $it m, " } ?: ""
        return if (es) "SOS: Necesito ayuda.$near Mapa: ${mapLink(place.lat, place.lng)} (${within}$fixTimeText). Enviado por RP Launcher."
        else "SOS: I need help.$near Map: ${mapLink(place.lat, place.lng)} (${within}$fixTimeText). Sent by RP Launcher."
    }

    /** What's said for "Where am I". */
    fun spokenPlace(place: Place?): String {
        if (place == null) return "Your location isn't available right now."
        val near = place.address?.let { "You're near $it." } ?: "There's no street address for this spot."
        val sure = meters(place)?.let { m -> if (m >= 500) " This is only a rough area." else " Accurate to about $m meters." } ?: ""
        return near + sure
    }

    /** Text for "Text my location". */
    fun shareText(place: Place): String =
        (place.address?.let { "I'm near $it. " } ?: "") + "Map: ${mapLink(place.lat, place.lng)}"

    /** "SOS. Texting Mary and Bob in 5 seconds. Tap Cancel to stop." */
    fun countdownWords(seconds: Int, names: List<String>, practice: Boolean): String {
        val who = when (names.size) {
            0 -> "no one"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        val start = if (practice) "Practice SOS. Nothing will really be sent." else "SOS."
        return "$start Texting $who with your location in $seconds seconds. Tap Cancel to stop."
    }

    /** The local emergency number, by the phone's country. 112 works on most mobile networks. */
    fun emergencyNumber(country: String): String = when (country.uppercase()) {
        "US", "CA", "MX", "PR", "GU", "VI", "PH" -> "911"
        "GB", "IE" -> "999"
        "AU" -> "000"
        "NZ" -> "111"
        else -> "112"
    }
}
