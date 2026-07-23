package com.ktmlink

import java.util.UUID

/**
 * GATT UUIDs, enums, and payload builders for the KTM BCCU BLE protocol.
 * Byte-exact port of KtmProtocol.ts, confirmed against BccuProtocol.kt in src/others/.
 *
 * Critical: Visibility values are 1/2/3 — never 0. Sending 0 breaks TFT rendering.
 */
object KtmNativeProtocol {

    private fun uuid(suffix: String) = UUID.fromString("71ced1ac-$suffix-44f5-9454-806ff70b3e02")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val MAIN_SERVICE:       UUID = uuid("0700")
    val AUTH_REQ:           UUID = uuid("0701")  // Bike → App, indications
    val AUTH_REP:           UUID = uuid("0702")  // App → Bike, write
    val NAVIGATION_STATE:   UUID = uuid("0703")  // guidanceOn + gpsIcon
    val TURN_ICON:          UUID = uuid("0704")  // Turn arrow icon byte
    val TURN_DISTANCE:      UUID = uuid("0705")  // Distance to next turn (max 8 chars)
    val TURN_INFO:          UUID = uuid("0706")  // Secondary maneuver / time remaining (max 16 chars)
    val TURN_ROAD:          UUID = uuid("0707")  // Road/street name (max 32 chars)
    val ETA:                UUID = uuid("0708")  // Arrival time (max 8 chars)
    val REMAINING_DISTANCE: UUID = uuid("0709")  // Total remaining distance (max 8 chars)
    val NOTIFICATION:       UUID = uuid("070a")  // Bottom banner notification

    // ── Visibility enum ──────────────────────────────────────────────────────
    // Confirmed from BccuProtocol.kt and com.ktm.mob.services.etbt.Visibility.binary()
    enum class Visibility(val value: Int) {
        OFF(1), HALF(2), FULL(3)
    }

    // ── Notification icon enum ───────────────────────────────────────────────
    enum class NotificationIcon(val value: Int) {
        UNKNOWN(0), REROUTING(1), WAYPOINT(2), TARGET_REACHED(3),
        GPS_LOST(4), WARNING(5), INFORMATION(6), SPEED(7)
    }

    // ── Turn icon enum ───────────────────────────────────────────────────────
    // Values confirmed byte-exact from BccuProtocol.kt and TurnIconMapper.ts.
    enum class TurnIcon(val value: Int) {
        UNKNOWN(0), UNDEFINED(1), GO_STRAIGHT(2),
        UTURN_RIGHT(3), UTURN_LEFT(4),
        KEEP_RIGHT(5), LIGHT_RIGHT(6), QUITE_RIGHT(7), HEAVY_RIGHT(8),
        KEEP_MIDDLE(9),
        KEEP_LEFT(10), LIGHT_LEFT(11), QUITE_LEFT(12), HEAVY_LEFT(13),
        ENTER_HIGHWAY_RIGHT(14), ENTER_HIGHWAY_LEFT(15),
        LEAVE_HIGHWAY_RIGHT(16), LEAVE_HIGHWAY_LEFT(17),
        HIGHWAY_KEEP_RIGHT(18), HIGHWAY_KEEP_LEFT(19),
        START(20), END(21), FERRY(22), PASS_STATION(23), HEAD_TO(24), CHANGE_LINE(25),
        // Right-hand traffic roundabouts (India / continental Europe)
        RAB_SECT_1_RH(26),  RAB_SECT_2_RH(27),  RAB_SECT_3_RH(28),  RAB_SECT_4_RH(29),
        RAB_SECT_5_RH(30),  RAB_SECT_6_RH(31),  RAB_SECT_7_RH(32),  RAB_SECT_8_RH(33),
        RAB_SECT_9_RH(34),  RAB_SECT_10_RH(35), RAB_SECT_11_RH(36), RAB_SECT_12_RH(37),
        RAB_SECT_13_RH(38), RAB_SECT_14_RH(39), RAB_SECT_15_RH(40), RAB_SECT_16_RH(41),
        // Left-hand traffic roundabouts (UK / Australia)
        RAB_SECT_1_LH(42),  RAB_SECT_2_LH(43),  RAB_SECT_3_LH(44),  RAB_SECT_4_LH(45),
        RAB_SECT_5_LH(46),  RAB_SECT_6_LH(47),  RAB_SECT_7_LH(48),  RAB_SECT_8_LH(49),
        RAB_SECT_9_LH(50),  RAB_SECT_10_LH(51), RAB_SECT_11_LH(52), RAB_SECT_12_LH(53),
        RAB_SECT_13_LH(54), RAB_SECT_14_LH(55), RAB_SECT_15_LH(56), RAB_SECT_16_LH(57);

        companion object {
            fun fromValue(v: Int): TurnIcon = entries.firstOrNull { it.value == v } ?: UNKNOWN
        }
    }

    // ── Handshake command codes ──────────────────────────────────────────────
    // Byte[2] of a decrypted 16-byte control message from the bike.
    // Note: our outgoing buildControlPacket() puts cmd at byte[4] — the bike
    // and app use different byte positions. Confirmed by Phase 1 BleManager.ts.
    const val CMD_HELLO         = 0x00
    const val CMD_GENERATE_KEYS = 0x01
    const val CMD_KEY_ACK_BASE  = 0x10  // 0x10 | keyIndex (0x10..0x1F)

    // ── Truncation ───────────────────────────────────────────────────────────
    // Confirmed from com.ktm.mob.utils.StrElipsed.elipse()
    private fun elipse(text: String, maxLen: Int): String = when {
        text.length <= maxLen -> text
        maxLen <= 3           -> text.substring(0, maxLen)
        else                  -> text.substring(0, maxLen - 3) + "..."
    }

    // ── Shared label payload: [visibility][UTF-8 text] ──────────────────────
    private fun buildLabelPayload(text: String, maxLen: Int, vis: Visibility): ByteArray {
        val textBytes = elipse(text, maxLen).toByteArray(Charsets.UTF_8)
        return byteArrayOf(vis.value.toByte()) + textBytes
    }

    // ── Public payload builders ──────────────────────────────────────────────

    /** TURN_ICON (0704): [visibility][iconByte] */
    fun buildTurnIconPayload(icon: TurnIcon, vis: Visibility = Visibility.FULL): ByteArray =
        byteArrayOf(vis.value.toByte(), icon.value.toByte())

    /** TURN_DISTANCE (0705): distance to next turn, e.g. "300 m". Max 8 chars. */
    fun buildTurnDistancePayload(text: String, vis: Visibility = Visibility.FULL): ByteArray =
        buildLabelPayload(text, 8, vis)

    /** TURN_INFO (0706): secondary maneuver / time remaining, e.g. "28 min". Max 16 chars. */
    fun buildTurnInfoPayload(text: String, vis: Visibility = Visibility.FULL): ByteArray =
        buildLabelPayload(text, 16, vis)

    /** TURN_ROAD (0707): road name, e.g. "Navkar Residency Rd". Max 32 chars. */
    fun buildTurnRoadPayload(text: String, vis: Visibility = Visibility.FULL): ByteArray =
        buildLabelPayload(text, 32, vis)

    /** ETA (0708): arrival time, e.g. "10:42am". Max 8 chars. */
    fun buildEtaPayload(text: String, vis: Visibility = Visibility.FULL): ByteArray =
        buildLabelPayload(text, 8, vis)

    /** REMAINING_DISTANCE (0709): total remaining distance, e.g. "8.1 km". Max 8 chars. */
    fun buildRemainingDistancePayload(text: String, vis: Visibility = Visibility.FULL): ByteArray =
        buildLabelPayload(text, 8, vis)

    /**
     * NOTIFICATION (070a): bottom banner.
     * [visibility][iconByte][UTF-8 text, max 16 chars]
     */
    fun buildNotificationPayload(
        text: String,
        icon: NotificationIcon = NotificationIcon.INFORMATION,
        vis: Visibility = Visibility.FULL
    ): ByteArray {
        val truncated = if (text.length > 16) text.substring(0, 16) else text
        val textBytes = truncated.toByteArray(Charsets.UTF_8)
        return byteArrayOf(vis.value.toByte(), icon.value.toByte()) + textBytes
    }

    /**
     * NAVIGATION_STATE (0703): enables the dash's guidance view.
     * MUST be written guidanceOn=true before any TURN_ICON/TURN_ROAD content renders.
     * [byte0: bit0=guidanceOn bit1=gpsIconOn][byte1: volume, 255=unset]
     */
    fun buildNavigationStatePayload(
        guidanceOn: Boolean,
        gpsIconOn: Boolean,
        volume: Int = 255
    ): ByteArray {
        val flags = (if (guidanceOn) 1 else 0) or ((if (gpsIconOn) 1 else 0) shl 1)
        return byteArrayOf(flags.toByte(), (volume and 0xFF).toByte())
    }
}
