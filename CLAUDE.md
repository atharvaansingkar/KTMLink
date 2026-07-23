# KTMLink — Project Bible

> Read this before touching any file. It contains the full architecture, protocol spec, bug list, and phase plan.

---

## 1. What This App Does

KTMLink is a React Native Android app that bridges **Google Maps navigation notifications** to the **TFT dashboard of a KTM motorcycle** over Bluetooth Low Energy.

The official KTM app uses a proprietary nav engine. KTMLink intercepts standard Google Maps notifications, parses them, formats the data into the exact BCCU protocol the bike expects, encrypts it with AES-CBC, and writes it to the bike's BLE GATT characteristics.

The user (Atharva) rides a KTM. Phone stays in pocket. Bike's TFT dash shows live turn-by-turn nav from Google Maps.

---

## 2. Core Ride Flow (What Must Work Perfectly)

1. Bike turns on → phone's OS auto-connects BT (calls + media) → our BLE GATT connection triggers → handshake completes → **Welcome screen: "Hello Atharva!" + START icon appears on KTM TFT and STAYS permanently**
2. User opens Google Maps, starts a route → within ~1 second all 6 nav lines + correct turn arrow appear on KTM TFT → update in near-realtime as Maps sends notifications
3. User cancels route / closes Maps → **immediately back to Welcome screen ("Hello Atharva!" + START icon)** — no stale data
4. Bike turns off → app shows `paired_offline` → bike turns on → auto-reconnects without user touching phone

This is the only thing that matters right now. Everything else is Phase 3.

---

## 3. Data Flow

```
Google Maps notification
  └─► MapScraperService.kt       (NotificationListenerService + reflection scraper)
        └─► MapScraperModule.kt  (RN NativeModule, emits 'onMapUpdate' / 'onMapRemoved')
              └─► App.tsx        (isUsefulNavData filter → parseNavNotification → streamLiveNavigation)
                    └─► BleManager.ts  (GATT queue → AES-CBC encrypt → write to bike)
                          └─► KTM TFT Dashboard (BCCU protocol over BLE)
```

---

## 4. BCCU Protocol — The 6 Dashboard Lines

The KTM TFT has exactly 6 data lines + 1 icon. All written to `MAIN_SERVICE = 71ced1ac-0700-44f5-9454-806ff70b3e02`.

| Line | Characteristic UUID | Field Name | Max | What it shows | Example |
|------|---------------------|------------|-----|---------------|---------|
| — | `0704` | `TURN_ICON` | enum (0–57) | Turn arrow drawn on screen | `QUITE_LEFT`, `ROUNDABOUT_2` |
| 1 | `0707` | `TURN_ROAD` | 32 chars | Name of road to turn onto | `"Navkar Residency Rd"` |
| 2 | `0705` | `TURN_DISTANCE` | 8 chars | Distance to next turn | `"450 m"` |
| 3 | `0706` | `TURN_INFO` | 16 chars | **Repurposed: Total time remaining** | `"28 min"` |
| 4 | `0708` | `ETA` | 8 chars | Arrival time (checkered flag icon on dash) | `"10:42am"` |
| 5 | `0709` | `REMAINING_DISTANCE` | 8 chars | Total distance to destination | `"8.1 km"` |

**Critical constraints:**
- `Visibility` enum: `OFF=1`, `HALF=2`, `FULL=3` — NOT a 0/1 boolean. Sending 0 breaks rendering.
- ETA: strip space before am/pm (`"10:42am"` not `"10:42 am"`) — checkered flag icon eats the space on physical TFT.
- All payloads: `[visibility_byte, ...UTF8_content]` for text fields; `[visibility_byte, icon_byte]` for TURN_ICON.
- All payloads encrypted with `frameAndEncryptData()` before writing (AES-CBC, framed data plane).
- `NAVIGATION_STATE (0703)` must be written `guidanceOn=true, gpsIconOn=true` before any nav chars render.

**Welcome screen state (persists when no nav active):**
- `TURN_ICON = START (24)`, `Visibility.FULL`
- `TURN_ROAD = "Hello Atharva!"`, `Visibility.FULL`
- All other 4 chars: empty string, `Visibility.OFF`

---

## 5. BLE Handshake Sequence

Bike initiates. App responds. Full sequence after GATT connect + services discovered + MTU(517):

```
Bike ──m1 nonce (16 bytes, plain)──────────────────────► App
App  ──m2 nonce (16 bytes, plain)──────────────────────► Bike
     [both sides derive tempIv, tempSecret from m1+m2]
Bike ──CMD_HELLO (0x00, encrypted with tempSecret/tempIv)► App
App  ──echo CMD_HELLO back──────────────────────────────► Bike
Bike ──CMD_GENERATE_KEYS (0x01)─────────────────────────► App
     [App derives 16-key SHA-512 pool, persists to AsyncStorage]
App  ──reply 0x02────────────────────────────────────────► Bike
Bike ──CMD_SELECT_KEY (0x10–0x1F)───────────────────────► App
     [App loads key[index], sets activeSessionKey]
App  ──echo CMD_SELECT_KEY back─────────────────────────► Bike
     [AUTHENTICATED — activateDashboard()]
```

**Second connection onwards:** bike sends CMD_SELECT_KEY directly (no CMD_GENERATE_KEYS). App loads persisted keys from `AsyncStorage('ktm_session_keys')`.

**Auth characteristics:** `AUTH_REQ = 0701` (bike→app indications), `AUTH_REP = 0702` (app→bike writes).

**Control packets** (`buildControlPacket`): 16 random bytes, `[2]=0xFF`, `[4]=cmd`, `[6]=0x01`. Encrypted with raw AES-CBC (no framing) using `encryptControl/decryptControl`.
**IMPORTANT — asymmetric cmd byte positions confirmed on physical bike:**
- Bike → App packets: command is at **byte `[2]`** of the decrypted 16 bytes.
- App → Bike packets (`buildControlPacket`): command is at **byte `[4]`**, `[2]=0xFF` is a marker.
- These are intentionally different. Read `[2]` when parsing bike packets. Write `[4]` when building replies.

---

## 6. Crypto Layer (`KtmCrypto.ts`)

Byte-exact match to `BccuCrypto.kt` (reference file in `src/others/`).

- **tempIv**: `m1[8..15] + m2[0..7]`
- **tempSecret**: `m2[8..15] + m1[0..7]`
- **Frame format**: `[16 random prefix bytes][data][random fill][padLen as last byte]` where `padLen = 16 - (data.length % 16)`
- **Session keys**: 4 rotations × SHA-512(64 bytes) → 4×16-byte chunks = 16 keys total
- **Data plane**: `frameAndEncryptData` / `decryptAndUnframeData` — used for all nav characteristics
- **Control plane**: `encryptControl` / `decryptControl` — raw AES-CBC, no framing — used only for auth packets

---

## 7. File Map

| File | Role |
|------|------|
| `App.tsx` | RN UI. Event listeners (onMapUpdate/onMapRemoved), handshake callbacks, connection state machine, live sync toggle |
| `src/services/BleManager.ts` | BLE connection, GATT queue with coalescing, full handshake handler, `streamLiveNavigation()`, `showWelcomeScreen()`, `clearGuidance()`, `activateDashboard()` |
| `src/services/NavParser.ts` | Regex engine. Extracts distance, road, maneuver, ETA, remaining distance, time remaining from raw notification strings |
| `src/services/TurnIconMapper.ts` | Maps Google Maps text → KTM `TurnIcon` enum (0–57). Handles roundabouts, Hindi, RH/LH traffic |
| `src/services/KtmProtocol.ts` | All GATT UUIDs, `Visibility` enum, payload builder functions, `elipse()` truncation |
| `src/services/KtmCrypto.ts` | AES-CBC crypto: framing, tempIv derivation, session key derivation, control/data plane encrypt/decrypt |
| `android/.../MapScraperService.kt` | `NotificationListenerService`. Scrapes Google Maps notifications including reflection on RemoteViews `mActions` to get hidden ETA/distance. Extracts `android.progressMax` (total distance in meters). Broadcasts `ACTION_MAPS_UPDATE` / `ACTION_MAPS_REMOVED`. |
| `android/.../MapScraperModule.kt` | RN NativeModule. Receives broadcast, emits `onMapUpdate` / `onMapRemoved` to JS |
| `android/.../BondedKtmModule.kt` | RN NativeModule. Returns OS-bonded Bluetooth devices so app can find KTM by name |
| `src/others/BccuProtocol.kt` | **Reference only.** Source from a companion navigation app. Confirms all UUIDs, enums, payload formats byte-exact. Do not modify. |
| `src/others/BccuConnectionService.kt` | **Reference only.** Production-grade BLE service from companion app. 1628 lines of timing constants, reconnect logic, handshake recovery patterns. Mine for Phase 2. Do not modify. |
| `src/others/BccuCrypto.kt` | **Reference only.** Confirms crypto operations byte-exact. |
| `src/others/ax0.java` | **Reference only.** Decompiled UUID enum from BikeConnect app. |
| `src/others/tv4.java` | **Reference only.** Decompiled GATT queue from BikeConnect app. 3-retry logic, 250ms delay. |
| `src/others/z45.java` | **Reference only.** Decompiled GATT callback. Confirms `requestConnectionPriority(HIGH=1)` called after `onMtuChanged`. |

---

## 8. GATT Queue

`BleManager.ts` has a serialized GATT write queue with **coalescing**:
- Display writes (nav data) use `coalesceKey = charUuid` — if a queued write to the same char hasn't fired yet, the old one is dropped and replaced with the fresh value. Prevents stale data stacking.
- Auth/control writes use `coalesceKey = null` — never dropped.
- On failure: retry once after 250ms. (Reference `tv4.java` does 3 retries — see Bug #7.)

---

## 9. NavParser Key Behaviors

- Combines `title + text + subText + bigText` into a single pipeline
- Strips middle-dot separators (`Â·`) that Google Maps injects between fields
- Distance regex: `^(?:In\s+)?(\d[\d.,]*\s*(?:m|km|mi|ft...))\b`
- ETA regex: `(\d{1,2}:\d{2}\s*(?:AM|PM)?)` — **BUG: uppercase only, misses Google Maps lowercase `am/pm`**
- `android.progressMax` from native layer = total route distance in meters → formatted as `"X.X km"` or `"XXX m"`
- `timeRemaining` (e.g. `"28 min"`) **replaces** `maneuver` entirely in `ParsedNavData.maneuver` → this is what goes into `TURN_INFO (0706)` on the dash
- ETA compressor: `"10:26 pm"` → `"10:26pm"` (strips space before am/pm)
- If time remaining not found in notification, calculates it from ETA minus current phone time (fallback)

---

## 10. TurnIcon Enum (58 values)

`UNKNOWN=0`, `UNDEFINED=1`, `GO_STRAIGHT=2`, `UTURN_RIGHT=3`, `UTURN_LEFT=4`, `KEEP_RIGHT=5`, `LIGHT_RIGHT=6`, `QUITE_RIGHT=7`, `HEAVY_RIGHT=8`, `KEEP_MIDDLE=9`, `KEEP_LEFT=10`, `LIGHT_LEFT=11`, `QUITE_LEFT=12`, `HEAVY_LEFT=13`, `ENTER_HIGHWAY_RIGHT=14`, `ENTER_HIGHWAY_LEFT=15`, `LEAVE_HIGHWAY_RIGHT=16`, `LEAVE_HIGHWAY_LEFT=17`, `HIGHWAY_KEEP_RIGHT=18`, `HIGHWAY_KEEP_LEFT=19`, `START=24`, `END=25`, `FERRY=22`, `PASS_STATION=23`, `HEAD_TO=20`, `CHANGE_LINE=21`, `RAB_SECT_1_RH=26` through `RAB_SECT_8_RH=33` (right-hand roundabouts), `RAB_SECT_1_LH=42` through `RAB_SECT_8_LH=49` (left-hand).

`RIGHT_HAND_TRAFFIC = true` is hardcoded in `TurnIconMapper.ts` (correct for India).

---

## 11. Phase 1 Status — COMPLETE ✓

Phase 1 core ride flow is **fully working and tested on bike**:
- Connect → auth → Welcome screen ("Hello Atharva!" + START icon) stays permanently
- Start Google Maps route → instant nav data on all 6 dash lines + correct turn arrow
- Cancel route → instant welcome back, all 4 detail fields blank, no flicker

### What was fixed

| Bug | Fix applied | Status |
|-----|-------------|--------|
| Welcome screen auto-cleared after 6s | Removed `greetingClearTimer`; `activateDashboard` calls `showWelcomeScreen()` directly | ✓ Fixed |
| No welcome back after nav ends | `onMapRemoved` calls `showWelcomeScreen()` instead of `clearGuidance()`; drains pending coalesced writes | ✓ Fixed |
| Nav updates slow / queued | `requestConnectionPriority(1)` added after `requestMTU(517)` in `BleManager.ts:364` | ✓ Fixed |
| Live sync toggle stale closure | Removed toggle entirely; `streamLiveNavigation` called unconditionally; `useEffect` deps `[]` | ✓ Fixed |
| ETA regex misses lowercase am/pm | `ETA_PATTERN` now handles both cases: `(?:AM\|PM\|am\|pm)` | ✓ Fixed |

### Known minor items (not blocking Phase 2)

- **GATT retries 1x** (`BleManager.ts:179`) — reference app (`tv4.java`) does 3x. Low priority, doesn't affect normal operation.
- **Post-auth bike power cycle** — when bike powers off while authenticated, `handleBleDisconnect` resets state and calls `onDisconnected` but App.tsx doesn't register that callback, so UI stays showing "Connected". Phase 2 Foreground Service will own the full reconnect lifecycle — not worth patching in the JS layer now.

---

## 12. Phase 2 Plan — Background Foreground Service

**Goal:** Phone locked in pocket, bike rides. Everything still works.

**Approach: Android Foreground Service** (confirmed correct, Headless JS is wrong for this use case).

Headless JS is rejected because:
- Android kills headless tasks after 30–60s on Xiaomi/Samsung/OnePlus with battery optimization
- No persistent connection — can't maintain authenticated BLE session across task restarts
- Not designed for long-running stateful work

Foreground Service is correct because:
- Android won't kill a foreground service while it shows a persistent status bar notification
- BLE session state (auth keys, `connectedDevice`) lives in the service — survives app minimize + screen lock
- `MapScraperService.kt` (notification listener) talks directly to the foreground service via local broadcast — no JS layer needed once connected
- Same pattern as Spotify, Google Maps, Waze

**Implementation plan:**
1. Create `KTMLinkForegroundService.kt` — starts on app launch, shows persistent notification `"KTMLink — Connected to KTM"` (or `"KTMLink — Searching..."`)
2. Move BLE connection + all GATT writes into this native service
3. `MapScraperService.kt` sends `ACTION_MAPS_UPDATE` → `KTMLinkForegroundService` processes it natively (no JS roundtrip)
4. RN JS layer becomes display-only — shows status but doesn't drive BLE
5. Register `BroadcastReceiver` for `ACTION_ACL_CONNECTED` — fires when bike's BT MAC connects at OS level, triggers GATT connect immediately even from background

**Key timing constants** (from `BccuConnectionService.kt` reference):
- `HANDSHAKE_TIMEOUT_KNOWN_MS = 25000` (known device)
- `HANDSHAKE_TIMEOUT_FIRST_PAIR_MS = 75000` (first pairing)
- `CONNECT_SETTLE_MS = 10000` (boot grace after bike found in scan)
- `RECONNECT_WATCHDOG_MS = 20000`
- `STUCK_CONNECTING_MS = 45000`

**Critical gotchas from reference** (`BccuConnectionService.kt`):
- `connectGatt(autoConnect=true)` can silently never fire — must use scan-first design
- After GATT close, direct reconnect reattaches to zombie ACL in 78ms — must scan first
- Bluetooth adapter OFF doesn't reliably deliver `onConnectionStateChange` — register explicit adapter state receiver
- Handshake recovery must go through SCAN, never direct connect

---

## 13. Phase 3 Plan — Auto-Launch + Nice-to-Haves

- Auto-launch app from killed state when KTM BT detected
- Handlebar button handling (triple-Up → mode overlay) — see `BccuConnectionService.kt` for button decode
- Phone notification mirroring → KTM banner (`NOTIFICATION 070a`)
- Weather overlay, ride telemetry (PRPC service)

---

## 14. Reference Files (src/others/) — DO NOT MODIFY

These are source files from companion/decompiled KTM apps. They are ground truth for protocol correctness.

- `BccuProtocol.kt` — UUID table, all enums, payload builders, `elipse()` function. Byte-exact reference.
- `BccuConnectionService.kt` — 1628-line production BLE service. Mine for Phase 2 patterns.
- `BccuCrypto.kt` — Crypto reference. All operations confirmed against our `KtmCrypto.ts`.
- `ax0.java` — Decompiled UUID enum from BikeConnect app.
- `tv4.java` — Decompiled GATT queue. Confirms 3-retry, 250ms delay, 10s no-write watchdog.
- `z45.java` — Decompiled GATT callback. Confirms `requestConnectionPriority(HIGH=1)` after MTU.

---

## 15. Testing Protocol

Atharva tests **indoors first** — verify all 6 fields appear correctly in the app's nav feed UI before going to the bike. The app's "NAVIGATION FEED" card shows all 6 parsed values in real time. Only after indoor validation, take to bike for BLE transmission test.

Use Android `adb logcat` with tag filters `[BleManager]`, `[GattQueue]`, `[NavParser]`, `[MapScraper]` for debugging.

---

## 16. Known Non-Issues

- UUID naming difference between `ax0.java` (`0706=TURN_EXTRA_INFO`, `0707=TURN_INFO`) and our code (`0706=TURN_INFO`, `0707=TURN_ROAD`) — `BccuProtocol.kt` (more authoritative source) matches our mapping. Not a bug.
- `src/others/` files showing TypeScript errors — they are Kotlin/Java, not part of the RN build. Ignore.
