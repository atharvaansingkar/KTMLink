export const KTM_UUIDS = {
  MAIN_SERVICE:      '71ced1ac-0700-44f5-9454-806ff70b3e02',
  AUTH_REQ:          '71ced1ac-0701-44f5-9454-806ff70b3e02',
  AUTH_REP:          '71ced1ac-0702-44f5-9454-806ff70b3e02',
  NAVIGATION_STATE:  '71ced1ac-0703-44f5-9454-806ff70b3e02',
  TURN_DISTANCE:     '71ced1ac-0705-44f5-9454-806ff70b3e02',
  TURN_ROAD:         '71ced1ac-0707-44f5-9454-806ff70b3e02',
};

// Pure JS UTF-8 encoder — no Buffer/TextEncoder polyfill needed in React Native
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

export const buildTurnRoadPayload = (text: string): Uint8Array => {
  // 0x03 is FULL visibility according to reverse engineering
  const textBytes = textToBytes(text);
  const payload = new Uint8Array(1 + textBytes.length);
  payload[0] = 0x03;
  payload.set(textBytes, 1);
  return payload;
};

export const buildTurnDistancePayload = (distanceText: string): Uint8Array => {
  // The distance from Google Maps arrives as a pre-formatted string e.g. "300 m", "1.2 km"
  // We encode it with the same 0x03 FULL visibility prefix as TURN_ROAD.
  const textBytes = textToBytes(distanceText);
  const payload = new Uint8Array(1 + textBytes.length);
  payload[0] = 0x03;
  payload.set(textBytes, 1);
  return payload;
};
