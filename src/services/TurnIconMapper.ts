/**
 * TurnIconMapper.ts
 * 
 * Maps Google Maps navigation notification text to KTM TFT dash turn icon codes.
 * The KTM Gen3 dash has 58 built-in turn icon glyphs — we send a single byte
 * and the dash renders its own vector arrow. No ML required.
 *
 * Icon enum confirmed byte-exact from decompiled ax0.java (TURN_ICON UUID 0704)
 * and BccuProtocol.kt TurnIcon enum.
 */

// ─── Turn Icon Enum ───────────────────────────────────────────────────────────
// Binary values confirmed from BccuProtocol.kt line 118-138
export enum TurnIcon {
  UNKNOWN               = 0,
  UNDEFINED             = 1,
  GO_STRAIGHT           = 2,
  UTURN_RIGHT           = 3,
  UTURN_LEFT            = 4,
  KEEP_RIGHT            = 5,
  LIGHT_RIGHT           = 6,
  QUITE_RIGHT           = 7,   // "normal" right turn
  HEAVY_RIGHT           = 8,   // sharp right
  KEEP_MIDDLE           = 9,
  KEEP_LEFT             = 10,
  LIGHT_LEFT            = 11,
  QUITE_LEFT            = 12,  // "normal" left turn
  HEAVY_LEFT            = 13,  // sharp left
  ENTER_HIGHWAY_RIGHT   = 14,
  ENTER_HIGHWAY_LEFT    = 15,
  LEAVE_HIGHWAY_RIGHT   = 16,
  LEAVE_HIGHWAY_LEFT    = 17,
  HIGHWAY_KEEP_RIGHT    = 18,
  HIGHWAY_KEEP_LEFT     = 19,
  START                 = 20,
  END                   = 21,
  FERRY                 = 22,
  PASS_STATION          = 23,
  HEAD_TO               = 24,
  CHANGE_LINE           = 25,
  // Roundabout exits — Right-Hand traffic (India, continental Europe)
  RAB_SECT_1_RH  = 26,  RAB_SECT_2_RH  = 27,  RAB_SECT_3_RH  = 28,
  RAB_SECT_4_RH  = 29,  RAB_SECT_5_RH  = 30,  RAB_SECT_6_RH  = 31,
  RAB_SECT_7_RH  = 32,  RAB_SECT_8_RH  = 33,  RAB_SECT_9_RH  = 34,
  RAB_SECT_10_RH = 35,  RAB_SECT_11_RH = 36,  RAB_SECT_12_RH = 37,
  RAB_SECT_13_RH = 38,  RAB_SECT_14_RH = 39,  RAB_SECT_15_RH = 40,
  RAB_SECT_16_RH = 41,
  // Roundabout exits — Left-Hand traffic (UK, Australia)
  RAB_SECT_1_LH  = 42,  RAB_SECT_2_LH  = 43,  RAB_SECT_3_LH  = 44,
  RAB_SECT_4_LH  = 45,  RAB_SECT_5_LH  = 46,  RAB_SECT_6_LH  = 47,
  RAB_SECT_7_LH  = 48,  RAB_SECT_8_LH  = 49,  RAB_SECT_9_LH  = 50,
  RAB_SECT_10_LH = 51,  RAB_SECT_11_LH = 52,  RAB_SECT_12_LH = 53,
  RAB_SECT_13_LH = 54,  RAB_SECT_14_LH = 55,  RAB_SECT_15_LH = 56,
  RAB_SECT_16_LH = 57,
}

// ─── Mapping Rules ────────────────────────────────────────────────────────────
// Priority-ordered: first match wins. Patterns tested against the full
// notification text (title + text joined), case-insensitive.
//
// Google Maps English maneuver phrases sourced from field captures + the
// navigation notification documentation. Hindi/Marathi phrases can be
// added later as additional patterns without changing logic.

interface MappingRule {
  pattern: RegExp;
  icon: TurnIcon;
}

// Helper to extract roundabout exit number from text
const extractRoundaboutExit = (text: string): number | null => {
  // "Take the 2nd exit" / "Take exit 3" / "2nd exit at the roundabout"
  const ordinals: Record<string, number> = {
    '1st': 1, 'first': 1,
    '2nd': 2, 'second': 2,
    '3rd': 3, 'third': 3,
    '4th': 4, 'fourth': 4,
    '5th': 5, 'fifth': 5,
    '6th': 6, 'sixth': 6,
    '7th': 7, 'seventh': 7,
    '8th': 8, 'eighth': 8,
  };

  const lower = text.toLowerCase();
  // Match ordinal patterns
  for (const [word, num] of Object.entries(ordinals)) {
    if (lower.includes(word)) return num;
  }
  // Match "exit N" or "N exit"
  const numMatch = lower.match(/(\d+)\s*(?:st|nd|rd|th)?\s*exit/);
  if (numMatch) return parseInt(numMatch[1], 10);
  const exitNumMatch = lower.match(/exit\s+(\d+)/);
  if (exitNumMatch) return parseInt(exitNumMatch[1], 10);

  return null;
};

// India drives on the LEFT side of the road → right-hand traffic roundabouts (RH).
// Change to _LH for UK/Australia/Japan etc.
const RIGHT_HAND_TRAFFIC = true;

const MAPPING_RULES: MappingRule[] = [
  // ── Arrival ──
  { pattern: /(?:arrived|destination|reach(?:ed)?)/i,         icon: TurnIcon.END },

  // ── U-turns ──
  { pattern: /u.?turn.*(?:left|लेफ्ट|बाएं)/i,               icon: TurnIcon.UTURN_LEFT },
  { pattern: /u.?turn.*(?:right|राइट|दाएं)/i,               icon: TurnIcon.UTURN_RIGHT },
  { pattern: /u.?turn/i,                                     icon: TurnIcon.UTURN_RIGHT }, // India drives on left → U swings right

  // ── Roundabouts (must check before generic turns) ──
  { pattern: /roundabout|traffic\s*circle|rotary|चक्कर/i,    icon: TurnIcon.GO_STRAIGHT }, // placeholder; overridden in mapper function

  // ── Highway / motorway ──
  { pattern: /(?:enter|merge|join).*(?:highway|motorway|expressway|freeway).*(?:left)/i,   icon: TurnIcon.ENTER_HIGHWAY_LEFT },
  { pattern: /(?:enter|merge|join).*(?:highway|motorway|expressway|freeway)/i,             icon: TurnIcon.ENTER_HIGHWAY_RIGHT },
  { pattern: /(?:exit|leave|take.*ramp|off.*ramp).*(?:left)/i,                              icon: TurnIcon.LEAVE_HIGHWAY_LEFT },
  { pattern: /(?:exit|leave|take.*ramp|off.*ramp)/i,                                        icon: TurnIcon.LEAVE_HIGHWAY_RIGHT },

  // ── Keep / fork ──
  { pattern: /keep\s*(?:to\s*(?:the\s*)?)?(?:left|बाएं)/i,          icon: TurnIcon.KEEP_LEFT },
  { pattern: /keep\s*(?:to\s*(?:the\s*)?)?(?:right|दाएं)/i,         icon: TurnIcon.KEEP_RIGHT },
  { pattern: /keep\s*(?:to\s*(?:the\s*)?)?(?:middle|center|straight)/i, icon: TurnIcon.KEEP_MIDDLE },
  { pattern: /(?:stay.*left|fork.*left|bear.*left)/i,                icon: TurnIcon.KEEP_LEFT },
  { pattern: /(?:stay.*right|fork.*right|bear.*right)/i,             icon: TurnIcon.KEEP_RIGHT },

  // ── Sharp turns ──
  { pattern: /sharp(?:ly)?\s*(?:turn\s*)?(?:left|बाएं)/i,    icon: TurnIcon.HEAVY_LEFT },
  { pattern: /sharp(?:ly)?\s*(?:turn\s*)?(?:right|दाएं)/i,   icon: TurnIcon.HEAVY_RIGHT },

  // ── Slight turns ──
  { pattern: /slight(?:ly)?\s*(?:turn\s*)?(?:left|बाएं)/i,   icon: TurnIcon.LIGHT_LEFT },
  { pattern: /slight(?:ly)?\s*(?:turn\s*)?(?:right|दाएं)/i,  icon: TurnIcon.LIGHT_RIGHT },

  // ── Normal turns (most common — must come after sharp/slight) ──
  { pattern: /(?:turn|मुड़ें)\s*(?:left|बाएं)/i,              icon: TurnIcon.QUITE_LEFT },
  { pattern: /(?:turn|मुड़ें)\s*(?:right|दाएं)/i,             icon: TurnIcon.QUITE_RIGHT },
  // Also match "left turn" / "right turn" (reverse word order)
  { pattern: /(?:left|बाएं)\s*(?:turn|मुड़ें)/i,              icon: TurnIcon.QUITE_LEFT },
  { pattern: /(?:right|दाएं)\s*(?:turn|मुड़ें)/i,             icon: TurnIcon.QUITE_RIGHT },

  // ── Head / depart ──
  { pattern: /(?:head|depart|start|चलें)/i,                  icon: TurnIcon.HEAD_TO },

  // ── Continue / straight (lowest priority among directions) ──
  { pattern: /continue|straight|सीधे/i,                     icon: TurnIcon.GO_STRAIGHT },

  // ── Ferry / line change ──
  { pattern: /ferry/i,                                        icon: TurnIcon.FERRY },
  { pattern: /change\s*(?:lane|line)/i,                       icon: TurnIcon.CHANGE_LINE },
];

// ─── Main Mapper ──────────────────────────────────────────────────────────────

/**
 * Maps Google Maps notification text to a KTM TFT dash turn icon byte.
 * 
 * @param text - The full notification text (title + " " + body), e.g.
 *               "NH48 Turn left onto MG Road" or "In 300 m, turn right"
 * @returns The TurnIcon enum value (0-57), safe to send as a single byte to
 *          characteristic 0704.
 */
export const mapTextToTurnIcon = (text: string): TurnIcon => {
  const lower = text.toLowerCase();

  // ── Special case: roundabouts ──
  if (/roundabout|traffic\s*circle|rotary|चक्कर/.test(lower)) {
    const exitNum = extractRoundaboutExit(text);
    if (exitNum && exitNum >= 1 && exitNum <= 16) {
      if (RIGHT_HAND_TRAFFIC) {
        return (TurnIcon.RAB_SECT_1_RH + exitNum - 1) as TurnIcon;
      } else {
        return (TurnIcon.RAB_SECT_1_LH + exitNum - 1) as TurnIcon;
      }
    }
    // Roundabout but no exit number parsed — show a generic 1st-exit icon
    return RIGHT_HAND_TRAFFIC ? TurnIcon.RAB_SECT_1_RH : TurnIcon.RAB_SECT_1_LH;
  }

  // ── Standard rules ──
  for (const rule of MAPPING_RULES) {
    if (rule.pattern.test(text)) {
      return rule.icon;
    }
  }

  // ── Fallback ──
  // GO_STRAIGHT is the safest fallback — it renders a visible forward arrow.
  // UNKNOWN(0) renders nothing; UNDEFINED(1) also renders nothing on most firmware.
  return TurnIcon.GO_STRAIGHT;
};

/**
 * Returns a human-readable name for the icon (useful for debug logging).
 */
export const turnIconName = (icon: TurnIcon): string => {
  return TurnIcon[icon] ?? `ICON_${icon}`;
};
