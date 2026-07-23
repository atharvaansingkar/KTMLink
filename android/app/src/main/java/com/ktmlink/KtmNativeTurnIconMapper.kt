package com.ktmlink

/**
 * Maps Google Maps notification text to KTM TFT dash TurnIcon values.
 * Exact port of TurnIconMapper.ts — same rules, same priority order, same
 * roundabout logic. Confirmed against BccuProtocol.kt TurnIcon enum.
 *
 * India drives on the LEFT side of the road → right-hand traffic (RH roundabouts).
 */
object KtmNativeTurnIconMapper {

    private val RIGHT_HAND_TRAFFIC = true

    // ── Roundabout exit number extraction ────────────────────────────────────
    // Handles: "Take the 2nd exit", "Take exit 3", "2nd exit at the roundabout"
    private val ORDINALS = mapOf(
        "1st" to 1, "first" to 1,
        "2nd" to 2, "second" to 2,
        "3rd" to 3, "third" to 3,
        "4th" to 4, "fourth" to 4,
        "5th" to 5, "fifth" to 5,
        "6th" to 6, "sixth" to 6,
        "7th" to 7, "seventh" to 7,
        "8th" to 8, "eighth" to 8,
    )

    private val NUM_EXIT_RE   = Regex("""(\d+)\s*(?:st|nd|rd|th)?\s*exit""", RegexOption.IGNORE_CASE)
    private val EXIT_NUM_RE   = Regex("""exit\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val ROUNDABOUT_RE = Regex("""roundabout|traffic\s*circle|rotary|चक्कर""", RegexOption.IGNORE_CASE)

    private fun extractRoundaboutExit(text: String): Int? {
        val lower = text.lowercase()
        for ((word, num) in ORDINALS) {
            if (lower.contains(word)) return num
        }
        NUM_EXIT_RE.find(lower)?.let { return it.groupValues[1].toIntOrNull() }
        EXIT_NUM_RE.find(lower)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    // ── Mapping rules — priority ordered, first match wins ───────────────────
    private data class Rule(val pattern: Regex, val icon: KtmNativeProtocol.TurnIcon)

    private val RULES = listOf(
        // Arrival
        Rule(Regex("""(?:arrived|destination|reach(?:ed)?)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.END),

        // U-turns (before generic turns)
        Rule(Regex("""u.?turn.*(?:left|लेफ्ट|बाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.UTURN_LEFT),
        Rule(Regex("""u.?turn.*(?:right|राइट|दाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.UTURN_RIGHT),
        Rule(Regex("""u.?turn""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.UTURN_RIGHT),  // India: U swings right

        // Highway / motorway
        Rule(Regex("""(?:enter|merge|join).*(?:highway|motorway|expressway|freeway).*(?:left)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.ENTER_HIGHWAY_LEFT),
        Rule(Regex("""(?:enter|merge|join).*(?:highway|motorway|expressway|freeway)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.ENTER_HIGHWAY_RIGHT),
        Rule(Regex("""(?:exit|leave|take.*ramp|off.*ramp).*(?:left)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.LEAVE_HIGHWAY_LEFT),
        Rule(Regex("""(?:exit|leave|take.*ramp|off.*ramp)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.LEAVE_HIGHWAY_RIGHT),

        // Keep / fork
        Rule(Regex("""keep\s*(?:to\s*(?:the\s*)?)?(?:left|बाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.KEEP_LEFT),
        Rule(Regex("""keep\s*(?:to\s*(?:the\s*)?)?(?:right|दाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.KEEP_RIGHT),
        Rule(Regex("""keep\s*(?:to\s*(?:the\s*)?)?(?:middle|center|straight)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.KEEP_MIDDLE),
        Rule(Regex("""(?:stay.*left|fork.*left|bear.*left)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.KEEP_LEFT),
        Rule(Regex("""(?:stay.*right|fork.*right|bear.*right)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.KEEP_RIGHT),

        // Sharp turns (before normal turns)
        Rule(Regex("""sharp(?:ly)?\s*(?:turn\s*)?(?:left|बाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.HEAVY_LEFT),
        Rule(Regex("""sharp(?:ly)?\s*(?:turn\s*)?(?:right|दाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.HEAVY_RIGHT),

        // Slight turns (before normal turns)
        Rule(Regex("""slight(?:ly)?\s*(?:turn\s*)?(?:left|बाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.LIGHT_LEFT),
        Rule(Regex("""slight(?:ly)?\s*(?:turn\s*)?(?:right|दाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.LIGHT_RIGHT),

        // Normal turns
        Rule(Regex("""(?:turn|मुड़ें)\s*(?:left|बाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.QUITE_LEFT),
        Rule(Regex("""(?:turn|मुड़ें)\s*(?:right|दाएं)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.QUITE_RIGHT),
        Rule(Regex("""(?:left|बाएं)\s*(?:turn|मुड़ें)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.QUITE_LEFT),
        Rule(Regex("""(?:right|दाएं)\s*(?:turn|मुड़ें)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.QUITE_RIGHT),

        // Head / depart
        Rule(Regex("""(?:head|depart|start|चलें)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.HEAD_TO),

        // Continue / straight (lowest priority)
        Rule(Regex("""continue|straight|सीधे""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.GO_STRAIGHT),

        // Ferry / lane change
        Rule(Regex("""ferry""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.FERRY),
        Rule(Regex("""change\s*(?:lane|line)""", RegexOption.IGNORE_CASE),
            KtmNativeProtocol.TurnIcon.CHANGE_LINE),
    )

    // ── Main mapping function ─────────────────────────────────────────────────
    fun mapTextToTurnIcon(text: String): KtmNativeProtocol.TurnIcon {
        // Roundabouts handled specially to pick the right exit sector
        if (ROUNDABOUT_RE.containsMatchIn(text)) {
            val exit = extractRoundaboutExit(text)
            if (exit != null && exit in 1..16) {
                return if (RIGHT_HAND_TRAFFIC)
                    KtmNativeProtocol.TurnIcon.fromValue(KtmNativeProtocol.TurnIcon.RAB_SECT_1_RH.value + exit - 1)
                else
                    KtmNativeProtocol.TurnIcon.fromValue(KtmNativeProtocol.TurnIcon.RAB_SECT_1_LH.value + exit - 1)
            }
            return if (RIGHT_HAND_TRAFFIC) KtmNativeProtocol.TurnIcon.RAB_SECT_1_RH
            else KtmNativeProtocol.TurnIcon.RAB_SECT_1_LH
        }

        for (rule in RULES) {
            if (rule.pattern.containsMatchIn(text)) return rule.icon
        }

        // GO_STRAIGHT is safest fallback — renders a visible forward arrow.
        // UNKNOWN(0) and UNDEFINED(1) render nothing on the TFT.
        return KtmNativeProtocol.TurnIcon.GO_STRAIGHT
    }
}
