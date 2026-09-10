# KTMLink

An Android app that bridges **Google Maps navigation notifications** to the **TFT dashboard of a KTM motorcycle** over Bluetooth Low Energy.

The official KTM app uses a proprietary nav engine. KTMLink intercepts standard Google Maps notifications, parses them, formats the data into the exact BCCU protocol the bike expects, encrypts it with AES-CBC, and writes it to the bike's BLE GATT characteristics — all natively in Kotlin, with zero BLE logic in JavaScript.

**Phone stays in your pocket. Bike's TFT dash shows live turn-by-turn nav from Google Maps.**

---

## How It Works

```
Google Maps notification
  └─► MapScraperService.kt       (NotificationListenerService + reflection scraper)
        └─► MapScraperModule.kt  (RN NativeModule, emits onMapUpdate / onMapRemoved)
              └─► App.tsx        (UI display only)
                    └─► KTMLinkForegroundService.kt  (native BLE service, GATT queue, handshake)
                          └─► KTM TFT Dashboard (BCCU protocol over BLE)
```

The entire BLE stack — scanning, GATT lifecycle, AES-CBC encryption, handshake state machine, write queue — lives in a native Android foreground service. React Native handles only the UI.

---

## Features

- **Live turn-by-turn navigation** on the KTM TFT dash (turn arrow, road name, distance, ETA, time remaining, total distance)
- **Welcome screen** that persists when no navigation is active ("Hello Atharva!" + START icon)
- **Silent auto-reconnect** — bike turns on, app reconnects without touching the phone
- **Auto-launch from killed state** — background BLE scan PendingIntent wakes the service even when the app process is fully dead
- **Phone notification mirroring** — WhatsApp, SMS, incoming calls displayed on the dash via word-chunked ticker
- Survives phone reboot (BOOT_COMPLETED receiver), BT adapter toggle, and OS process kills

---

## The Dashboard (BCCU Protocol)

The KTM TFT has 6 data lines written to the `MAIN_SERVICE` GATT characteristic (`71ced1ac-0700-44f5-9454-806ff70b3e02`):

| Line | Field | Max | Example |
|------|-------|-----|---------|
| — | Turn icon (enum 0–57) | — | `QUITE_LEFT`, `ROUNDABOUT_2` |
| 1 | Road name to turn onto | 32 chars | `"Navkar Residency Rd"` |
| 2 | Distance to next turn | 8 chars | `"450 m"` |
| 3 | Time remaining (total) | 16 chars | `"28 min"` |
| 4 | ETA (checkered flag icon) | 8 chars | `"10:42am"` |
| 5 | Total distance to destination | 8 chars | `"8.1 km"` |

All payloads are AES-CBC encrypted before writing. A `Visibility` enum (`OFF=1`, `HALF=2`, `FULL=3`) precedes each text payload.

---

## BLE Handshake

The bike initiates a challenge-response handshake on every connection:

1. Bike sends a 16-byte nonce → App sends its own 16-byte nonce
2. Both sides derive `tempIv` and `tempSecret` from the two nonces
3. Bike sends `CMD_HELLO (0x00)` encrypted with temp keys
4. First pairing: bike sends `CMD_GENERATE_KEYS (0x01)` → app derives a 16-key SHA-512 pool, persisted to `SharedPreferences`
5. Subsequent connections: bike sends `CMD_SELECT_KEY (0x10–0x1F)` → app loads the persisted key → **authenticated**

After authentication, the app calls `activateDashboard()` and begins writing nav data.

---

## Architecture

| File | Role |
|------|------|
| `App.tsx` | RN UI only. No BLE code. |
| `KTMLinkForegroundService.kt` | BLE scan, GATT lifecycle, handshake state machine, write queue, nav broadcast receiver |
| `KTMLinkServiceModule.kt` | Thin RN bridge. Emits `onKtmEvent` to JS. |
| `KtmAclReceiver.kt` | BroadcastReceiver for ACL_CONNECTED, BT_ON, BOOT_COMPLETED |
| `KtmBleScanReceiver.kt` | Background BLE scan PendingIntent — wakes service from killed state |
| `KtmNativeCrypto.kt` | AES-CBC crypto (byte-exact port of reference `BccuCrypto.kt`) |
| `KtmNativeProtocol.kt` | All GATT UUIDs, Visibility enum, payload builders |
| `KtmNativeNavParser.kt` | Notification text → 6 structured nav fields |
| `KtmNativeTurnIconMapper.kt` | Instruction string → TFT icon enum (26 rules + roundabout) |
| `MapScraperService.kt` | NotificationListenerService — scrapes Google Maps notifications |

---

## Notification Mirroring

WhatsApp (1:1 and group), SMS, and incoming calls are mirrored to the `NOTIFICATION (070a)` characteristic using a word-chunked ticker:

- Direct message: `"Anushka:"` → `"Where are"` → `"you?"` at 1.5 s/frame
- Group message: `"[SASA]"` → `"Anushka:"` → body chunks
- Welcome screen is restored 10 s after the notification ends
- If navigation is active, a `notifTakeover` flag pauses nav writes for 5 s

---

## Build & Run

This is a standard React Native Android project. iOS is not supported (BLE + notification listener require Android).

```bash
# Install JS dependencies
npm install

# Start Metro bundler
npm start

# Build and install on connected device
npm run android
```

**Requirements:**
- Android device (API 26+, Bluetooth LE)
- Google Maps installed (navigation source)
- KTM motorcycle with Gen-3 TFT dashboard (BCCU BLE protocol)
- Notification listener permission granted in Android settings

```bash
# Filter logcat to KTMLink tags only
adb logcat -s KTMLinkService GattQueue KtmHandshake KtmCrypto NavParser MapScraper KtmAclReceiver KtmBleScanReceiver
```

---

## Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Core ride flow: connect → auth → nav on dash → welcome on cancel | Complete |
| 2 | Full native Kotlin BLE stack, foreground service, silent reconnect | Complete |
| 2.5 | Auto-launch from killed state via background BLE scan PendingIntent | Complete |
| 3a | Production-grade React Native UI | Planned |
| 3b | Phone notification mirroring | Complete |
| 3c | Saved parking spot (GPS on disconnect) | Planned |
| 3d | Overspeed alerts | Planned |
| 3e | Handlebar button handling | Planned |

---

## Protocol Reference

The `src/others/` directory contains reference files from the KTM companion ecosystem — do not modify:

- `BccuProtocol.kt` — UUID table, all enums, payload builders (ground truth)
- `BccuCrypto.kt` — Crypto reference, confirms byte-exact match with `KtmNativeCrypto.kt`
- `BccuConnectionService.kt` — Production BLE service from KTM-Nav-GEN3 open-source app
- `ax0.java` — Decompiled UUID enum from the BikeConnect app
- `tv4.java` / `z45.java` — Decompiled GATT queue and callback from BikeConnect

The `KTM-Nav-GEN3-reference/` directory is a full clone of the open-source reference app used to validate reconnect logic.
