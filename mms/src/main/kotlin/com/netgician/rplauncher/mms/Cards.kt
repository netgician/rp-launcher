package com.netgician.rplauncher.mms

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One line of a vCard or calendar file: NAME;PARAM=VALUE:value. */
internal class CardLine(val name: String, val params: Map<String, String>, val types: Set<String>, val value: String)

/** Reads the line-based formats used by contact cards (vCard 2.1/3.0/4.0) and invites (vCalendar, iCalendar). */
internal object CardLines {
    fun read(text: String): List<CardLine> = unfold(text).mapNotNull(::parse)

    /**
     * Long lines continue on the next line when it starts with a space or tab. vCard 2.1
     * quoted-printable lines continue after a trailing "=" instead.
     */
    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        var softBreak = false
        for (line in raw) {
            when {
                softBreak && out.isNotEmpty() -> out[out.lastIndex] = out.last().dropLast(1) + line
                (line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty() -> out[out.lastIndex] = out.last() + line.substring(1)
                else -> out += line
            }
            val last = out.lastOrNull().orEmpty()
            softBreak = last.endsWith("=") && last.substringBefore(':').contains("QUOTED-PRINTABLE", ignoreCase = true)
        }
        return out.filter { it.isNotBlank() }
    }

    private fun parse(line: String): CardLine? {
        val colon = indexOfColon(line)
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        var value = line.substring(colon + 1)
        val bits = head.split(';')
        val name = bits[0].substringAfterLast('.').uppercase() // "item1.TEL" → "TEL"
        val params = mutableMapOf<String, String>()
        val types = mutableSetOf<String>()
        for (b in bits.drop(1)) {
            val eq = b.indexOf('=')
            if (eq < 0) {
                types += b.uppercase() // vCard 2.1: TEL;CELL:...
            } else {
                val k = b.substring(0, eq).uppercase()
                val v = b.substring(eq + 1).trim('"')
                params[k] = v
                if (k == "TYPE") v.split(',').forEach { types += it.uppercase() }
            }
        }
        if (params["ENCODING"].equals("QUOTED-PRINTABLE", ignoreCase = true)) {
            value = quotedPrintable(value, params["CHARSET"])
        }
        return CardLine(name, params, types, value)
    }

    /** The colon that ends the name and parameters, skipping colons inside quoted parameter values. */
    private fun indexOfColon(line: String): Int {
        var quoted = false
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) return i
        }
        return -1
    }

    private fun quotedPrintable(s: String, charset: String?): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '=' && i + 3 <= s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) { bytes.write(v); i += 3; continue }
            }
            bytes.write(c.code and 0xFF)
            i++
        }
        val cs = charset?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes.toByteArray(), cs)
    }

    /** Undoes the text escapes: \n, \, \; and \\. */
    fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** Splits on separators that aren't escaped. */
    fun split(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { cur.append(c).append(s[i + 1]); i += 2; continue }
            if (c == sep) { out += cur.toString(); cur.clear() } else cur.append(c)
            i++
        }
        out += cur.toString()
        return out
    }
}

/** A phone number on a contact card, with what kind it is. */
data class CardPhone(val number: String, val kind: String)

/** A contact card sent in a text: who it is and how to reach them. */
data class ContactCard(
    val name: String,
    val phones: List<CardPhone>,
    val emails: List<String>,
    val organization: String?,
    val address: String?,
    val note: String?,
) {
    /** What is said aloud, and shown, in reading order. */
    val speech: String
        get() = buildList {
            add("Contact card. ${name.ifBlank { "No name" }}.")
            organization?.let { add("$it.") }
            phones.forEach { add("${it.kind} ${spacedNumber(it.number)}.") }
            emails.forEach { add("Email $it.") }
            address?.let { add("Address $it.") }
            note?.let { add(it) }
        }.joinToString(" ")

    companion object {
        /** All the cards in a .vcf file (one file can hold several). */
        fun read(text: String): List<ContactCard> {
            val cards = mutableListOf<ContactCard>()
            var lines = mutableListOf<CardLine>()
            var inside = false
            for (l in CardLines.read(text)) {
                when {
                    l.name == "BEGIN" && l.value.equals("VCARD", ignoreCase = true) -> { inside = true; lines = mutableListOf() }
                    l.name == "END" && l.value.equals("VCARD", ignoreCase = true) -> { if (inside) cards += build(lines); inside = false }
                    inside -> lines += l
                }
            }
            return cards.filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
        }

        private fun build(lines: List<CardLine>): ContactCard {
            fun first(n: String) = lines.firstOrNull { it.name == n }?.value?.let(CardLines::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            val formatted = first("FN")
            val fromParts = lines.firstOrNull { it.name == "N" }?.let { n ->
                // Family;Given;Middle;Prefix;Suffix → "Prefix Given Middle Family Suffix"
                val p = CardLines.split(n.value, ';').map { CardLines.unescape(it).trim() } + List(5) { "" }
                listOf(p[3], p[1], p[2], p[0], p[4]).filter { it.isNotEmpty() }.joinToString(" ")
            }
            val phones = lines.filter { it.name == "TEL" }.mapNotNull { l ->
                val number = l.value.removePrefix("tel:").trim()
                if (number.isEmpty()) null else CardPhone(number, phoneKind(l.types))
            }
            val emails = lines.filter { it.name == "EMAIL" }.map { it.value.removePrefix("mailto:").trim() }.filter { it.isNotEmpty() }
            val org = lines.firstOrNull { it.name == "ORG" }?.let { o ->
                CardLines.split(o.value, ';').map { CardLines.unescape(it).trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            }?.takeIf { it.isNotEmpty() }
            val adr = lines.firstOrNull { it.name == "ADR" }?.let { a ->
                // PO box;extended;street;city;region;postcode;country
                CardLines.split(a.value, ';').map { CardLines.unescape(it).replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            }?.takeIf { it.isNotEmpty() }
            return ContactCard(
                name = formatted ?: fromParts.orEmpty(),
                phones = phones,
                emails = emails,
                organization = org,
                address = adr,
                note = first("NOTE"),
            )
        }

        private fun phoneKind(types: Set<String>): String = when {
            "CELL" in types || "MOBILE" in types || "IPHONE" in types -> "Mobile"
            "HOME" in types -> "Home"
            "WORK" in types -> "Work"
            "FAX" in types -> "Fax"
            else -> "Phone"
        }

        /** "9195550100" → "9 1 9, 5 5 5, 0 1 0 0" so it's read digit by digit, in groups. */
        fun spacedNumber(number: String): String {
            val plus = number.trim().startsWith("+")
            val d = number.filter { it.isDigit() }
            val groups = when {
                d.length == 10 -> listOf(d.substring(0, 3), d.substring(3, 6), d.substring(6))
                d.length == 11 && d.startsWith("1") -> listOf("1", d.substring(1, 4), d.substring(4, 7), d.substring(7))
                d.length > 4 -> d.chunked(3).let { c -> if (c.last().length == 1 && c.size > 1) c.dropLast(2) + (c[c.size - 2] + c.last()) else c }
                else -> listOf(d)
            }
            val spoken = groups.joinToString(", ") { it.toCharArray().joinToString(" ") }
            return if (plus) "plus $spoken" else spoken
        }
    }
}

/** A calendar invite sent in a text. Times are in epoch milliseconds. */
data class CalendarInvite(
    val title: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
) {
    /** What is said aloud: the title, then when and where. */
    fun speech(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String = buildList {
        add("Calendar invite. ${title.ifBlank { "No title" }}.")
        startMillis?.let { add(whenText(it, zone, locale) + ".") }
        location?.let { add("At $it.") }
        description?.let { add(it) }
    }.joinToString(" ")

    /** "Saturday, October 17, 2026 at 7:00 PM", or just the day for an all-day event. */
    fun whenText(start: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        if (allDay) {
            // All-day dates are stored as midnight UTC, so read them in UTC to keep the right day.
            return Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).format(day)
        }
        val t = Instant.ofEpochMilli(start).atZone(zone)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        val joiner = if (locale.language == "es") "a las" else "at"
        return "${t.format(day)} $joiner ${t.format(time)}"
    }

    companion object {
        /** All the events in a .ics or .vcs file. */
        fun read(text: String, defaultZone: ZoneId = ZoneId.systemDefault()): List<CalendarInvite> {
            val events = mutableListOf<CalendarInvite>()
            var lines = mutableListOf<CardLine>()
            var depth = 0
            var inEvent = false
            for (l in CardLines.read(text)) {
                if (l.name == "BEGIN") {
                    if (l.value.equals("VEVENT", ignoreCase = true)) { inEvent = true; lines = mutableListOf() }
                    else if (inEvent) depth++ // e.g. a VALARM inside the event
                    continue
                }
                if (l.name == "END") {
                    if (l.value.equals("VEVENT", ignoreCase = true) && inEvent) { events += build(lines, defaultZone); inEvent = false }
                    else if (inEvent && depth > 0) depth--
                    continue
                }
                if (inEvent && depth == 0) lines += l
            }
            return events
        }

        private fun build(lines: List<CardLine>, zone: ZoneId): CalendarInvite {
            fun text(n: String) = lines.firstOrNull { it.name == n }?.value?.let(CardLines::unescape)?.trim()?.takeIf { it.isNotEmpty() }
            val start = lines.firstOrNull { it.name == "DTSTART" }
            val end = lines.firstOrNull { it.name == "DTEND" }
            val allDay = start != null && isDateOnly(start)
            val startMs = start?.let { time(it, zone) }
            var endMs = end?.let { time(it, zone) }
            if (endMs == null && startMs != null) {
                endMs = duration(lines.firstOrNull { it.name == "DURATION" }?.value)?.let { startMs + it }
                    ?: if (allDay) startMs + 86_400_000L else startMs + 3_600_000L
            }
            return CalendarInvite(
                title = text("SUMMARY").orEmpty(),
                startMillis = startMs,
                endMillis = endMs,
                allDay = allDay,
                location = text("LOCATION"),
                description = text("DESCRIPTION"),
            )
        }

        private fun isDateOnly(l: CardLine) =
            l.params["VALUE"].equals("DATE", ignoreCase = true) || l.value.trim().length == 8

        /** 20261017T190000Z (UTC), 20261017T190000 with TZID (that zone) or without (the phone's), 20261017 (all day). */
        private fun time(l: CardLine, zone: ZoneId): Long? = runCatching {
            val v = l.value.trim()
            if (isDateOnly(l)) {
                return LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            val local = LocalDateTime.parse(v.removeSuffix("Z").take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            val z: ZoneId = when {
                v.endsWith("Z") -> ZoneOffset.UTC
                l.params["TZID"] != null -> runCatching { ZoneId.of(l.params["TZID"]!!.trim('/')) }.getOrDefault(zone)
                else -> zone
            }
            ZonedDateTime.of(local, z).toInstant().toEpochMilli()
        }.getOrNull()

        /** PT1H30M, P1D... in milliseconds. */
        private fun duration(v: String?): Long? {
            val m = Regex("^-?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(v?.trim().orEmpty()) ?: return null
            val (w, d, h, min, s) = m.destructured
            fun n(x: String) = x.toLongOrNull() ?: 0L
            val total = (((n(w) * 7 + n(d)) * 24 + n(h)) * 60 + n(min)) * 60 + n(s)
            return if (total > 0) total * 1000 else null
        }
    }
}
