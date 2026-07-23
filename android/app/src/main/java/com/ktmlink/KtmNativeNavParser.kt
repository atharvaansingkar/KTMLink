package com.ktmlink

import android.util.Log
import java.util.Calendar

/**
 * Parses Google Maps navigation notification fields into structured dash data.
 * Exact port of NavParser.ts — same regex logic, same field extraction order,
 * same ETA fallback calculation.
 *
 * Input fields match what MapScraperService broadcasts in ACTION_MAPS_UPDATE:
 *   title   — usually the road name
 *   text    — distance + maneuver, e.g. "In 300 m, turn left"
 *   subText — ETA + remaining distance, e.g. "12:45 PM - 23 km"
 *   bigText — expanded text, sometimes has ETA
 */
object KtmNativeNavParser {

    private const val TAG = "NavParser"

    data class ParsedNavData(
        val distance: String,
        val road: String,
        val turnIcon: KtmNativeProtocol.TurnIcon,
        val turnIconName: String,
        val maneuver: String,
        val eta: String,
        val remainingDistance: String,
        val timeRemaining: String,
    )

    // ── Junk filter ──────────────────────────────────────────────────────────
    private val JUNK_PHRASES = listOf(
        "traffic", "heavier than usual", "speed trap", "finding best route",
        "searching for route", "faster route", "route overview", "heavy congestion",
        "moderate traffic", "rerouting", "finding gps", "no gps signal",
        "preparing route", "loading",
    )

    fun isUsefulNavData(title: String, text: String): Boolean {
        val combined = "$title $text".lowercase()
        return JUNK_PHRASES.none { combined.contains(it) }
    }

    // ── Regexes ───────────────────────────────────────────────────────────────
    // Distance prefix: "In 300 m," or "300 m" or "1.2 km" etc.
    private val DIST_RE = Regex(
        """^(?:In\s+)?(\d[\d.,]*\s*(?:m|km|mi|ft|metres?|meters?|miles?|feet))\b""",
        RegexOption.IGNORE_CASE
    )
    private val DIST_DEEP_RE = Regex(
        """(?:in|after|for)\s+(\d[\d.,]*\s*(?:m|km|mi|ft))""",
        RegexOption.IGNORE_CASE
    )

    // Separators left after distance removal
    private val LEADING_SEP_RE = Regex("""^[\s•\-,·.]+""")

    // Onto/towards split for maneuver vs road
    private val ONTO_RE = Regex("""(?:^|\s+)(?:onto|on|towards|toward|to)\s+""", RegexOption.IGNORE_CASE)

    // Trailing qualifiers that add no value in a 16-char slot
    private val MANEUVER_TAIL_RE = Regex(
        """\s+(?:after|before|at|in|onto|on|towards|toward)\s+.*""",
        RegexOption.IGNORE_CASE
    )

    // Starts-with maneuver words
    private val MANEUVER_START_RE = Regex(
        """^(?:Turn|Keep|Take|Head|Continue|Merge|Exit|Arrive)\b""",
        RegexOption.IGNORE_CASE
    )

    // ETA: "12:45 PM", "1:30pm", "10:26 am" — both cases
    private val ETA_RE = Regex("""(\d{1,2}:\d{2}\s*(?:AM|PM|am|pm)?)""")

    // Space before am/pm to strip: "10:26 pm" → "10:26pm"
    private val ETA_SPACE_RE = Regex("""\s+(am|pm)""", RegexOption.IGNORE_CASE)

    // Remaining distance: first km/mi/m number in subText/bigText
    private val REM_DIST_RE = Regex("""(\d[\d.,]*\s*(?:km|mi|m))""")

    // Time remaining: "28 min", "1 hr 5 min", "2h 30m" — NOT "km"
    private val TIME_REM_RE = Regex(
        """\b(\d+\s*(?:hrs?|h)\s*\d*\s*(?:min)?|\d+\s*min)\b""",
        RegexOption.IGNORE_CASE
    )

    // ── Main parser ───────────────────────────────────────────────────────────
    fun parse(
        title: String,
        text: String,
        subText: String = "",
        bigText: String = "",
    ): ParsedNavData {
        val combinedText = "$title $text".trim()

        // 1. Distance
        var distance = ""
        var remainder = combinedText

        val distMatch = DIST_RE.find(remainder)
        if (distMatch != null) {
            distance = distMatch.groupValues[1].trim()
            remainder = remainder.substring(distMatch.value.length).trim()
        } else {
            val deepMatch = DIST_DEEP_RE.find(remainder)
            if (deepMatch != null) {
                distance = deepMatch.groupValues[1].trim()
                remainder = remainder.replace(deepMatch.value, "").trim()
            }
        }

        // Fallback: search bigText for "In X m/km" — handles newer Maps versions that put distance
        // only in RemoteViews (scraped to bigText) while EXTRA_TEXT has road/maneuver only.
        // "In" prefix required to avoid picking up remaining distance ("8.1 km").
        if (distance.isEmpty() && bigText.isNotEmpty()) {
            val bigDistRe = Regex("""\bIn\s+(\d[\d.,]*\s*(?:m|km|mi|ft))\b""", RegexOption.IGNORE_CASE)
            val bigDistMatch = bigDistRe.find(bigText)
            if (bigDistMatch != null) {
                distance = bigDistMatch.groupValues[1].trim()
            }
        }

        remainder = LEADING_SEP_RE.replace(remainder, "").trim()

        // 2. Maneuver and Road
        var maneuver = ""
        var road = ""

        val ontoMatch = ONTO_RE.find(remainder)
        if (ontoMatch != null) {
            maneuver = remainder.substring(0, ontoMatch.range.first).trim()
            road = remainder.substring(ontoMatch.range.last + 1).trim()
        } else {
            if (MANEUVER_START_RE.containsMatchIn(remainder)) {
                maneuver = remainder
            } else {
                road = remainder
            }
        }

        maneuver = MANEUVER_TAIL_RE.replace(maneuver, "").trim()

        // 3. Turn icon
        val fullTextForIcon = "$combinedText $bigText"
        val icon = KtmNativeTurnIconMapper.mapTextToTurnIcon(fullTextForIcon)

        // 4. ETA, remaining distance, time remaining — from subText / bigText
        var eta = ""
        var remainingDistance = ""
        var timeRemaining = ""

        val allText = "$subText | $bigText | $combinedText"
        val remSource = "$subText | $bigText"

        val etaMatch = ETA_RE.find(allText)
        if (etaMatch != null) {
            eta = etaMatch.groupValues[1].trim()
            eta = ETA_SPACE_RE.replace(eta) { it.groupValues[1] }  // strip space before am/pm
        }

        val remDistMatch = REM_DIST_RE.find(remSource)
        if (remDistMatch != null) {
            remainingDistance = remDistMatch.groupValues[1].trim()
        }

        val timeMatch = TIME_REM_RE.find(remSource)
        if (timeMatch != null) {
            timeRemaining = timeMatch.groupValues[1].trim()
        }

        // 5. ETA fallback: calculate time remaining from ETA minus current time
        if (timeRemaining.isEmpty() && eta.isNotEmpty()) {
            timeRemaining = calculateTimeRemainingFromEta(eta)
        }

        Log.d(TAG, "Parsed: icon=${icon.name} dist=$distance road=$road maneuver=$maneuver eta=$eta rem=$remainingDistance timeRem=$timeRemaining")

        return ParsedNavData(
            distance        = distance,
            road            = road,
            turnIcon        = icon,
            turnIconName    = icon.name,
            maneuver        = maneuver,
            eta             = eta,
            remainingDistance = remainingDistance,
            timeRemaining   = timeRemaining,
        )
    }

    // ── ETA fallback: "10:26pm" → "28 min" ──────────────────────────────────
    private val ETA_PARSE_RE = Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)

    private fun calculateTimeRemainingFromEta(eta: String): String {
        return try {
            val m = ETA_PARSE_RE.find(eta) ?: return ""
            var hours = m.groupValues[1].toInt()
            val minutes = m.groupValues[2].toInt()
            val modifier = m.groupValues[3].lowercase()

            if (modifier == "pm" && hours < 12) hours += 12
            if (modifier == "am" && hours == 12) hours = 0

            val now = Calendar.getInstance()
            val etaCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hours)
                set(Calendar.MINUTE, minutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (etaCal.before(now)) etaCal.add(Calendar.DATE, 1)

            val diffMins = ((etaCal.timeInMillis - now.timeInMillis) / 60000).toInt()
            if (diffMins <= 0) return ""

            if (diffMins >= 60) {
                val h = diffMins / 60
                val min = diffMins % 60
                if (min > 0) "${h}h ${min}min" else "${h}h"
            } else {
                "$diffMins min"
            }
        } catch (e: Exception) {
            ""
        }
    }
}
