/**
 * KtmProtocol.ts
 *
 * GATT layout and wire-format builders for the KTM Gen3 BCCU BLE protocol.
 * UUIDs and payload shapes confirmed byte-exact from:
 *   - Decompiled stable app: ax0.java (UUID enum, 24 characteristics)
 *   - Open-source reference: BccuProtocol.kt (payload builders + limits)
 *
 * Every characteristic uses the same framing:
 *   [visibility_byte][...payload_bytes]
 * where visibility is from the Visibility enum (FULL=3, HALF=2, OFF=1).
 */

// ─── Visibility Enum ──────────────────────────────────────────────────────────
// Confirmed from BccuProtocol.kt Visibility enum + com.ktm.mob.services.etbt.Visibility
export const Visibility = {
  OFF:     1,
  HALF:    2,
  FULL:    3,
} as const;

// ─── Notification Icon Enum ───────────────────────────────────────────────────
// Confirmed from BccuProtocol.kt NotificationIcon enum
export const NotificationIconType = {
  UNKNOWN:                0,
  NOTIFICATION_REROUTING: 1,
  NOTIFICATION_WAYPOINT:  2,
  TARGET_REACHED:         3,
  GPS_LOST:               4,
  WARNING:                5,
  INFORMATION:            6,
  SPEED:                  7,
} as const;

// ─── GATT UUIDs ───────────────────────────────────────────────────────────────
// Full list from ax0.java (decompiled stable app). All share the base
// pattern: 71ced1ac-XXXX-44f5-9454-806ff70b3e02
export const KTM_UUIDS = {
  // Main navigation service
  MAIN_SERVICE:         '71ced1ac-0700-44f5-9454-806ff70b3e02',
  AUTH_REQ:             '71ced1ac-0701-44f5-9454-806ff70b3e02',  // AUTHENTICATION_REQUEST — Indicate
  AUTH_REP:             '71ced1ac-0702-44f5-9454-806ff70b3e02',  // AUTHENTICATION_REPLY   — Write
  NAVIGATION_STATE:     '71ced1ac-0703-44f5-9454-806ff70b3e02',  // guidanceOn + gpsIcon
  TURN_ICON:            '71ced1ac-0704-44f5-9454-806ff70b3e02',  // Turn arrow icon byte
  TURN_DISTANCE:        '71ced1ac-0705-44f5-9454-806ff70b3e02',  // Distance to next turn (max 8 chars)
  TURN_INFO:            '71ced1ac-0706-44f5-9454-806ff70b3e02',  // Secondary maneuver text (max 16 chars)
  TURN_ROAD:            '71ced1ac-0707-44f5-9454-806ff70b3e02',  // Road/street name (max 32 chars)
  ETA:                  '71ced1ac-0708-44f5-9454-806ff70b3e02',  // Arrival time (max 8 chars)
  REMAINING_DISTANCE:   '71ced1ac-0709-44f5-9454-806ff70b3e02',  // Total remaining distance (max 8 chars)
  NOTIFICATION:         '71ced1ac-070a-44f5-9454-806ff70b3e02',  // Bottom banner notification

  // Base service (VIN etc.)
  BASE_SERVICE:         '71ced1ac-0000-44f5-9454-806ff70b3e02',
  BASE_GET_VIN_REQ:     '71ced1ac-0001-44f5-9454-806ff70b3e02',
  BASE_VIN:             '71ced1ac-0002-44f5-9454-806ff70b3e02',

  // Remote Control Module (handlebar buttons)
  RCM_SERVICE:          '71ced1ac-0100-44f5-9454-806ff70b3e02',
  RCM_REMOTE_CONTROL:   '71ced1ac-0103-44f5-9454-806ff70b3e02',

  // TBT (turn-by-turn) navigation request/response
  TBT_NAV_REQUEST:      '71ced1ac-070b-44f5-9454-806ff70b3e02',
  TBT_NAV_RESPONSE:     '71ced1ac-070c-44f5-9454-806ff70b3e02',
  TBT_LAST_DESTINATION: '71ced1ac-070d-44f5-9454-806ff70b3e02',
  TBT_FAVORITE:         '71ced1ac-070e-44f5-9454-806ff70b3e02',

  // PRPC telemetry RPC channel
  PRPC_SERVICE:         '71ced1ac-0600-44f5-9454-806ff70b3e02',
  PRPC_REQUEST:         '71ced1ac-0601-44f5-9454-806ff70b3e02',
  PRPC_RESPONSE:        '71ced1ac-0602-44f5-9454-806ff70b3e02',
  PRPC_NOTIFICATION:    '71ced1ac-0603-44f5-9454-806ff70b3e02',
};

// ─── UTF-8 Encoder ────────────────────────────────────────────────────────────
// Pure JS — no Buffer/TextEncoder polyfill needed in React Native
const textToBytes = (text: string): Uint8Array => {
  const bytes: number[] = [];
  for (let i = 0; i < text.length; i++) {
    let c = text.charCodeAt(i);
    if (c < 0x80) {
      bytes.push(c);
    } else if (c < 0x800) {
      bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c >= 0xd800 && c < 0xdc00 && i + 1 < text.length) {
      const c2 = text.charCodeAt(++i);
      const cp = ((c - 0xd800) << 10) + (c2 - 0xdc00) + 0x10000;
      bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return new Uint8Array(bytes);
};

// ─── Ellipsis Truncation ──────────────────────────────────────────────────────
// Confirmed from com.ktm.mob.utils.StrElipsed.elipse():
// If the string fits, leave it. Otherwise cut to maxLen-3 and append "..."
const elipse = (text: string, maxLen: number): string => {
  if (text.length <= maxLen) return text;
  if (maxLen <= 3) return text.substring(0, maxLen);
  return text.substring(0, maxLen - 3) + '...';
};

// ─── Label Payload Builder ────────────────────────────────────────────────────
// Shared shape: [visibility_byte][UTF-8 text]
// Used by TURN_DISTANCE, TURN_INFO, TURN_ROAD, ETA, REMAINING_DISTANCE
const buildLabelPayload = (
  text: string,
  maxLen: number,
  visibility: number = Visibility.FULL,
): Uint8Array => {
  const textBytes = textToBytes(elipse(text, maxLen));
  const payload = new Uint8Array(1 + textBytes.length);
  payload[0] = visibility;
  payload.set(textBytes, 1);
  return payload;
};

// ─── Public Payload Builders ──────────────────────────────────────────────────

/**
 * TURN_ICON (0704): [visibility][iconByte]
 * The iconByte is from the TurnIcon enum (0-57).
 * Confirmed from BccuProtocol.kt buildTurnIconPayload()
 */
export const buildTurnIconPayload = (
  iconByte: number,
  visibility: number = Visibility.FULL,
): Uint8Array => {
  return new Uint8Array([visibility, iconByte & 0xFF]);
};

/**
 * TURN_DISTANCE (0705): distance to next turn, e.g. "300 m", "1.2 km"
 * Max 8 chars. Confirmed from TurnDistance.java.
 */
export const buildTurnDistancePayload = (
  text: string,
  visibility: number = Visibility.FULL,
): Uint8Array => buildLabelPayload(text, 8, visibility);

/**
 * TURN_INFO (0706): secondary maneuver text, e.g. "then turn left"
 * Max 16 chars. Confirmed from TurnInfo.java.
 */
export const buildTurnInfoPayload = (
  text: string,
  visibility: number = Visibility.FULL,
): Uint8Array => buildLabelPayload(text, 16, visibility);

/**
 * TURN_ROAD (0707): street/road name, e.g. "towards 1st Cross Rd"
 * Max 32 chars. Confirmed from TurnRoad.java.
 */
export const buildTurnRoadPayload = (
  text: string,
  visibility: number = Visibility.FULL,
): Uint8Array => buildLabelPayload(text, 32, visibility);

/**
 * ETA (0708): arrival time, e.g. "12:45 PM"
 * Max 8 chars. Confirmed from Eta.java.
 */
export const buildEtaPayload = (
  text: string,
  visibility: number = Visibility.FULL,
): Uint8Array => buildLabelPayload(text, 8, visibility);

/**
 * REMAINING_DISTANCE (0709): total remaining trip distance, e.g. "23 km"
 * Max 8 chars. Confirmed from Dist2Target.java.
 */
export const buildRemainingDistPayload = (
  text: string,
  visibility: number = Visibility.FULL,
): Uint8Array => buildLabelPayload(text, 8, visibility);

/**
 * NOTIFICATION (070a): bottom banner notification.
 * [visibility][iconByte][UTF-8 text, max 16 chars]
 * Confirmed from BccuProtocol.kt buildNotificationPayload()
 */
export const buildNotificationPayload = (
  text: string,
  iconByte: number = NotificationIconType.INFORMATION,
  visibility: number = Visibility.FULL,
): Uint8Array => {
  const textBytes = textToBytes(text.substring(0, 16));
  const payload = new Uint8Array(2 + textBytes.length);
  payload[0] = visibility;
  payload[1] = iconByte & 0xFF;
  payload.set(textBytes, 2);
  return payload;
};

/**
 * NAVIGATION_STATE (0703): enables/disables the dash's guidance view.
 * [byte0: bit0=guidanceOn bit1=gpsIconOn][byte1: volume 0-100 or 255=unset]
 * MUST be sent with guidanceOn=true before any TURN_ICON/TURN_ROAD content renders.
 * Confirmed from BccuProtocol.kt buildNavigationStatePayload()
 */
export const buildNavigationStatePayload = (
  guidanceOn: boolean,
  gpsIconOn: boolean,
  volume: number = 255,
): Uint8Array => {
  const flags = (guidanceOn ? 1 : 0) | ((gpsIconOn ? 1 : 0) << 1);
  return new Uint8Array([flags, volume & 0xFF]);
};
