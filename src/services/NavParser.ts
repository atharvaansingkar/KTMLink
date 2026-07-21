/**
 * NavParser.ts
 *
 * Parses Google Maps navigation notification text into structured fields
 * for the KTM dash display. Google Maps notifications have a predictable
 * structure that we exploit:
 *
 *   title:   "MG Road"                    (road/street name)
 *   text:    "In 300 m, turn left"        (distance + maneuver)
 *   subText: "12:45 PM - 23 km"           (ETA + remaining distance)
 *   bigText: "In 300 m, turn left onto…"  (expanded, sometimes has ETA)
 *
 * This parser extracts: distance, road, maneuver, eta, remainingDistance.
 */

import { mapTextToTurnIcon, TurnIcon, turnIconName } from './TurnIconMapper';

// ─── Parsed Result ────────────────────────────────────────────────────────────
export interface ParsedNavData {
  /** Distance to next turn, e.g. "300 m", "1.2 km" — sent to TURN_DISTANCE (0705) */
  distance: string;

  /** Road/street name — sent to TURN_ROAD (0707) */
  road: string;

  /** Turn icon byte for the dash — sent to TURN_ICON (0704) */
  turnIcon: TurnIcon;

  /** Turn icon name for debug logging */
  turnIconName: string;

  /** Raw maneuver text, e.g. "Turn left" — useful for TURN_INFO (0706) */
  maneuver: string;

  /** Arrival time, e.g. "12:45" — sent to ETA (0708) */
  eta: string;

  /** Total remaining distance, e.g. "23 km" — sent to REMAINING_DISTANCE (0709) */
  remainingDistance: string;
}

// ─── Junk Filter ──────────────────────────────────────────────────────────────
// These notification texts are status updates, not navigation instructions.
// Discard them to avoid flickering the dash with non-actionable info.
const JUNK_PHRASES = [
  'traffic', 'heavier than usual', 'speed trap', 'finding best route',
  'searching for route', 'faster route', 'route overview', 'heavy congestion',
  'moderate traffic', 'rerouting', 'finding gps', 'no gps signal',
  'preparing route', 'loading',
];

export const isUsefulNavData = (title: string, text: string): boolean =>
  !JUNK_PHRASES.some(p => `${title} ${text}`.toLowerCase().includes(p));

// ─── Distance Extraction ──────────────────────────────────────────────────────
// Matches patterns like: "In 300 m,", "300 m", "1.2 km", "0.5 mi", "500 ft"
const DISTANCE_PATTERN = /^(?:In\s+)?(\d[\d.,]*\s*(?:m|km|mi|ft|metres?|meters?|miles?|feet))\b/i;

// Also try to find distance embedded deeper in text (e.g. "After 300 m, turn left")
const DISTANCE_PATTERN_DEEP = /(?:in|after|for)\s+(\d[\d.,]*\s*(?:m|km|mi|ft))/i;

// ─── Maneuver Extraction ──────────────────────────────────────────────────────
// The maneuver is the action phrase: "turn left", "keep right", "take the 2nd exit"
// It typically appears after the distance prefix and before "onto/on/towards"
const MANEUVER_STRIP = /^(?:In\s+\d[\d.,]*\s*(?:m|km|mi|ft)\s*,?\s*)/i;
const ONTO_SPLIT = /\s+(?:onto|on|towards|toward|to)\s+/i;

// ─── ETA + Remaining Distance Extraction ──────────────────────────────────────
// Google Maps subText format: "12:45 PM - 23 km" or "1:30 PM (23.4 km)"
// bigText sometimes contains: "ETA 12:45 PM · 23 km remaining"
const ETA_PATTERN = /(\d{1,2}:\d{2}\s*(?:AM|PM|am|pm)?)/;
const REMAINING_DIST_PATTERN = /(\d[\d.,]*\s*(?:km|mi|m))/;

// ─── Main Parser ──────────────────────────────────────────────────────────────

/**
 * Parse a Google Maps navigation notification into structured dash data.
 *
 * @param title    - Notification title (usually the road name)
 * @param text     - Notification text (distance + maneuver)
 * @param subText  - Notification subText (ETA + remaining distance, may be empty)
 * @param bigText  - Notification bigText (expanded text, may be empty)
 */
export const parseNavNotification = (
  title: string,
  text: string,
  subText: string = '',
  bigText: string = '',
): ParsedNavData => {
  // Google Maps sometimes puts EVERYTHING in the title and leaves text empty.
  // So we combine title and text to ensure we don't miss anything.
  const combinedText = `${title} ${text}`.trim();
  
  // 1. Extract Distance
  let distance = '';
  let remainder = combinedText;
  
  const distMatch = remainder.match(/^(?:In\s+)?(\d[\d.,]*\s*(?:m|km|mi|ft|metres?|meters?|miles?|feet))\b/i);
  if (distMatch) {
    distance = distMatch[1].trim();
    remainder = remainder.substring(distMatch[0].length).trim();
  } else {
    // Try deep match
    const distMatchDeep = remainder.match(/(?:in|after|for)\s+(\d[\d.,]*\s*(?:m|km|mi|ft))/i);
    if (distMatchDeep) {
      distance = distMatchDeep[1].trim();
      remainder = remainder.replace(distMatchDeep[0], '').trim();
    }
  }

  // Strip leading separators (e.g., bullet points, dashes, commas left after removing distance)
  remainder = remainder.replace(/^[\s•\-,·.]+/, '').trim();

  // 2. Extract Maneuver and Road
  let maneuver = '';
  let road = '';
  
  const ontoMatch = remainder.match(/(?:^|\s+)(?:onto|on|towards|toward|to)\s+/i);
  if (ontoMatch && ontoMatch.index !== undefined) {
    // Split into maneuver and road
    maneuver = remainder.substring(0, ontoMatch.index).trim();
    road = remainder.substring(ontoMatch.index + ontoMatch[0].length).trim();
  } else {
    // No "onto" found. Is it an action verb or just a road name?
    if (/^(?:Turn|Keep|Take|Head|Continue|Merge|Exit|Arrive)\b/i.test(remainder)) {
      maneuver = remainder;
    } else {
      road = remainder;
    }
  }

  // 3. Turn Icon
  // Must use combinedText to catch "Turn left" if it was in the title!
  const fullTextForIcon = `${combinedText} ${bigText}`;
  const icon = mapTextToTurnIcon(fullTextForIcon);

  // 4. ETA, remaining distance, and time remaining — from subText or bigText
  let eta = '';
  let remainingDistance = '';
  let timeRemaining = '';

  // Combine everything so we don't miss anything that was secretly scraped via reflection
  const allText = `${subText} | ${bigText} | ${combinedText}`;
  
  if (allText) {
    const etaMatch = allText.match(ETA_PATTERN);
    if (etaMatch) {
      eta = etaMatch[1].trim();
      // Compress ETA: "10:26 pm" -> "10:26pm" to prevent visual cutoff on the KTM dash
      eta = eta.replace(/\s+(am|pm)/i, '$1');
    }

    // Remaining distance: search anywhere in the combined text
    // It's safer to extract it from subText or bigText first, to avoid picking up the turn distance
    const remSource = `${subText} | ${bigText}`;
    const remMatch = remSource.match(REMAINING_DIST_PATTERN);
    if (remMatch) {
      remainingDistance = remMatch[1].trim();
    }
    
    // Time remaining: e.g. "5 min", "1 hr 5 min"
    const timeRemMatch = remSource.match(/\b(\d+\s*(?:hr|h|hrs)\s*\d*\s*(?:min|m)?|\d+\s*(?:min|m))\b/i);
    if (timeRemMatch) {
      const tr = timeRemMatch[1].trim();
      if (tr.includes('min') || tr.includes('hr') || tr.includes('h')) {
        timeRemaining = tr;
      } else if (tr.endsWith('m') && !remSource.includes(tr + 'i')) { // Not 'mi'
        timeRemaining = tr;
      }
    }
  }

  // If timeRemaining is STILL empty, try calculating it from ETA (if ETA is today)
  if (!timeRemaining && eta) {
    try {
      const now = new Date();
      let [timeStr, modifier] = eta.toLowerCase().split(/(am|pm)/);
      timeStr = timeStr.trim();
      let [hours, minutes] = timeStr.split(':').map(Number);
      
      if (modifier === 'pm' && hours < 12) hours += 12;
      if (modifier === 'am' && hours === 12) hours = 0;
      
      const etaTime = new Date();
      etaTime.setHours(hours, minutes, 0, 0);
      
      // If ETA is tomorrow
      if (etaTime.getTime() < now.getTime()) {
        etaTime.setDate(etaTime.getDate() + 1);
      }
      
      let diffMins = Math.floor((etaTime.getTime() - now.getTime()) / 60000);
      if (diffMins > 0) {
        if (diffMins > 60) {
          const h = Math.floor(diffMins / 60);
          const m = diffMins % 60;
          timeRemaining = `${h} hr ${m} min`;
        } else {
          timeRemaining = `${diffMins} min`;
        }
      }
    } catch(e) {
      // Ignored
    }
  }

  // If the user wants time remaining in TURN_INFO, let's prioritize it and REMOVE the maneuver text completely
  if (timeRemaining) {
    maneuver = timeRemaining;
  }

  return {
    distance,
    road,
    turnIcon: icon,
    turnIconName: turnIconName(icon),
    maneuver,
    eta,
    remainingDistance,
  };
};
