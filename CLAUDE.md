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

---

## 3. Data Flow

```
Google Maps notification
  └─► MapScraperService.kt       (NotificationListenerService + reflection scraper)
        └─► MapScraperModule.kt  (RN NativeModule, emits 'onMapUpdate' / 'onMapRemoved')
              └─► App.tsx        (UI display only — no BLE in JS layer since Phase 2)
                    └─► KTMLinkForegroundService.kt  (native BLE service, GATT queue, handshake)
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
Bike ──CMD_GENERATE_KEYS (0x01)─────────────────────────► App   ← first pairing only
     [App derives 16-key SHA-512 pool, persists to SharedPreferences]
App  ──reply 0x02────────────────────────────────────────► Bike
Bike ──CMD_SELECT_KEY (0x10–0x1F)───────────────────────► App
     [App loads key[index], sets activeSessionKey]
App  ──echo CMD_SELECT_KEY back─────────────────────────► Bike
     [AUTHENTICATED — activateDashboard()]
```

**Second connection onwards:** bike sends `HELLO → SELECT_KEY` directly (skips GENERATE_KEYS). App loads persisted keys from `SharedPreferences("KTMLinkPrefs", "ktm_session_keys")`.

**Auth characteristics:** `AUTH_REQ = 0701` (bike→app indications), `AUTH_REP = 0702` (app→bike writes).

**Control packets** (`buildControlPacket`): 16 random bytes, `[2]=0xFF`, `[4]=cmd`, `[6]=0x01`. Encrypted with raw AES-CBC (no framing) using `encryptControl/decryptControl`.
**IMPORTANT — asymmetric cmd byte positions confirmed on physical bike:**
- Bike → App packets: command is at **byte `[2]`** of the decrypted 16 bytes.
- App → Bike packets (`buildControlPacket`): command is at **byte `[4]`**, `[2]=0xFF` is a marker.
- These are intentionally different. Read `[2]` when parsing bike packets. Write `[4]` when building replies.

---

## 6. Crypto Layer

Byte-exact match to `BccuCrypto.kt` (reference file in `src/others/`). Implemented in `KtmNativeCrypto.kt`.

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
| `App.tsx` | RN UI only. Listens to `KTMLinkService` events for handshake state display. Listens to `onMapUpdate`/`onMapRemoved` for nav feed card. Does NOT call any BLE functions. |
| `android/.../KTMLinkForegroundService.kt` | **The heart of Phase 2.** Android foreground service. BLE scan, GATT lifecycle, handshake state machine, write queue with coalescing, slot6 alternation, Maps broadcast receiver, adapter state receiver. |
| `android/.../KTMLinkServiceModule.kt` | Thin RN NativeModule bridge. `startService()`, `stopService()`, `connectDirectly()`. Emits `onKtmEvent` to JS. |
| `android/.../KTMLinkServicePackage.kt` | Standard RN package registration for KTMLinkServiceModule. |
| `android/.../KtmAclReceiver.kt` | BroadcastReceiver for ACL_CONNECTED, BT_STATE_ON, BOOT_COMPLETED → starts foreground service. |
| `android/.../KtmBleScanReceiver.kt` | **Phase 2.5.** BroadcastReceiver for background BLE scan PendingIntent results. Wakes service even when app process is fully killed. Armed on service onDestroy. |
| `android/.../KtmNativeCrypto.kt` | Kotlin port of KtmCrypto.ts / BccuCrypto.kt. All AES-CBC ops. |
| `android/.../KtmNativeProtocol.kt` | All GATT UUIDs, Visibility enum, payload builders. |
| `android/.../KtmNativeNavParser.kt` | Kotlin port of NavParser.ts. Full regex pipeline. |
| `android/.../KtmNativeTurnIconMapper.kt` | Kotlin port of TurnIconMapper.ts. 26 rules + roundabout. |
| `android/.../MapScraperService.kt` | NotificationListenerService. Scrapes Google Maps notifications. Broadcasts ACTION_MAPS_UPDATE / ACTION_MAPS_REMOVED. |
| `android/.../MapScraperModule.kt` | RN NativeModule. Receives broadcast, emits `onMapUpdate` / `onMapRemoved` to JS. |
| `android/.../BondedKtmModule.kt` | RN NativeModule. Returns OS-bonded Bluetooth devices. |
| `src/services/BleManager.ts` | **Phase 1 only — no longer called.** Keep for reference, delete in Phase 3 cleanup. |
| `src/services/NavParser.ts` | Keep as reference for KtmNativeNavParser.kt. |
| `src/services/TurnIconMapper.ts` | Keep as reference for KtmNativeTurnIconMapper.kt. |
| `src/services/KtmProtocol.ts` | Keep as reference. |
| `src/services/KtmCrypto.ts` | Keep as reference. |
| `src/others/BccuProtocol.kt` | **Reference only.** Ground truth for UUIDs, enums, payload formats. Do not modify. |
| `src/others/BccuConnectionService.kt` | **Reference only.** Production-grade BLE service from KTM-Nav-GEN3 open-source app. Mine for reconnect patterns. Do not modify. |
| `src/others/BccuCrypto.kt` | **Reference only.** Confirms crypto ops byte-exact. |
| `src/others/ax0.java` | **Reference only.** Decompiled UUID enum from BikeConnect app. |
| `src/others/tv4.java` | **Reference only.** Decompiled GATT queue. Confirms 3-retry, 250ms delay. |
| `src/others/z45.java` | **Reference only.** Decompiled GATT callback. |
| `KTM-Nav-GEN3-reference/` | Full clone of open-source reference app. Ground truth for reconnect logic. Do not modify. |

---

## 8. GATT Queue (KTMLinkForegroundService.kt)

Serialized write queue on a dedicated `HandlerThread("GattWorker")`:
- Display writes (nav data) use `coalesceKey = charUuid` — if a queued write to the same char hasn't fired yet, the old one is dropped and replaced. Prevents stale data stacking.
- Auth/control writes use `coalesceKey = null` — never dropped.
- All CCCD writes (including AUTH_REQ CCCD) go through the queue via `enqueueCccd()`.
- Queue is cleared (`clearWriteQueue()`) on every disconnect via `closeGatt()`.
- `closeGatt()` nulls `bluetoothGatt` BEFORE calling `old.close()` — stale GATT callbacks detect `gatt !== bluetoothGatt` and self-discard.

---

## 9. NavParser Key Behaviors

- Combines `title + text + subText + bigText` into a single pipeline
- Strips middle-dot separators (`Â·`) that Google Maps injects between fields
- Distance regex: `^(?:In\s+)?(\d[\d.,]*\s*(?:m|km|mi|ft...))\b`
- ETA regex handles both uppercase and lowercase: `(\d{1,2}:\d{2}\s*(?:AM|PM|am|pm))`
- `android.progressMax` from native layer = total route distance in meters → formatted as `"X.X km"` or `"XXX m"`
- `timeRemaining` (e.g. `"28 min"`) goes into `TURN_INFO (0706)` on the dash
- ETA compressor: `"10:26 pm"` → `"10:26pm"` (strips space before am/pm)
- If time remaining not found in notification, calculates it from ETA minus current phone time (fallback)

---

## 10. TurnIcon Enum (58 values)

`UNKNOWN=0`, `UNDEFINED=1`, `GO_STRAIGHT=2`, `UTURN_RIGHT=3`, `UTURN_LEFT=4`, `KEEP_RIGHT=5`, `LIGHT_RIGHT=6`, `QUITE_RIGHT=7`, `HEAVY_RIGHT=8`, `KEEP_MIDDLE=9`, `KEEP_LEFT=10`, `LIGHT_LEFT=11`, `QUITE_LEFT=12`, `HEAVY_LEFT=13`, `ENTER_HIGHWAY_RIGHT=14`, `ENTER_HIGHWAY_LEFT=15`, `LEAVE_HIGHWAY_RIGHT=16`, `LEAVE_HIGHWAY_LEFT=17`, `HIGHWAY_KEEP_RIGHT=18`, `HIGHWAY_KEEP_LEFT=19`, `START=24`, `END=25`, `FERRY=22`, `PASS_STATION=23`, `HEAD_TO=20`, `CHANGE_LINE=21`, `RAB_SECT_1_RH=26` through `RAB_SECT_8_RH=33` (right-hand roundabouts), `RAB_SECT_1_LH=42` through `RAB_SECT_8_LH=49` (left-hand).

`RIGHT_HAND_TRAFFIC = true` is hardcoded in `KtmNativeTurnIconMapper.kt` (correct for India).

---

## 11. Phase 1 Status — COMPLETE ✓

Phase 1 core ride flow is **fully working and tested on bike**:
- Connect → auth → Welcome screen ("Hello Atharva!" + START icon) stays permanently
- Start Google Maps route → instant nav data on all 6 dash lines + correct turn arrow
- Cancel route → instant welcome back, all 4 detail fields blank, no flicker

---

## 12. Phase 2 Status — COMPLETE ✓ (with one open bug)

All 8 native Kotlin files created and wired. App.tsx migrated to service-driven model — no BLE logic in JS layer. Foreground service runs with persistent notification. Nav pipeline fully native.

**Phase 2 steps completed:**

| Step | Description | Status |
|------|-------------|--------|
| 1 | KtmNativeCrypto.kt — byte-exact port of BccuCrypto.kt | ✓ |
| 2 | KtmNativeProtocol.kt — all UUIDs, enums, payload builders | ✓ |
| 3 | KtmNativeNavParser.kt — full regex port | ✓ |
| 4 | KtmNativeTurnIconMapper.kt — 26 rules + roundabout | ✓ |
| 5 | KTMLinkForegroundService.kt — BLE lifecycle, handshake, write queue, slot6 | ✓ |
| 6 | KTMLinkServiceModule.kt + KTMLinkServicePackage.kt | ✓ |
| 7 | KtmAclReceiver.kt — ACL_CONNECTED, BT_ON, BOOT_COMPLETED | ✓ |
| 8 | AndroidManifest.xml, MainApplication.kt updated | ✓ |
| 9 | App.tsx migrated — no BLE calls, service events only | ✓ |

**Bug fixes applied during Phase 2:**
- `saveSessionKeys()` / `PREFS_HAS_PAIRED` / `PREFS_DEVICE_MAC` saves: `.apply()` → `.commit()` (synchronous)
- `closeGatt()`: nulls `bluetoothGatt` before `old.close()` — stale callback guard
- All GATT callbacks: `if (gatt !== bluetoothGatt) return` stale guard added
- `enableAuthReqIndications()`: now routes through `enqueueCccd()` instead of raw `writeDescriptor()`
- `onDescriptorWrite()`: unified to single ack path (all CCCD writes go through queue)
- App.tsx: `'offline'` → `'paired_offline'` TypeScript fix
- `ACTION_CONNECT_DIRECTLY`: explicit branch in `onStartCommand()`

---

## 13. OPEN BUG — Silent Reconnect Fails After Bike Power Cycle

**Status: UNRESOLVED as of Phase 2. Must be fixed before Phase 3.**

### Symptom

First authentication works correctly (user accepts pairing prompt on bike TFT). After that, turning the bike off and back on should silently reconnect — no user interaction, no prompt, no GENERATE_KEYS. Instead, the handshake stalls at HELLO_EXCHANGED, logs `WRITE FAILED uuid=0b3e02 status=133`, disconnects, and immediately retries. This loop repeats indefinitely.

In logs: after AUTHENTICATED at timestamp T, then DISCONNECTED, then reconnect attempt shows `CMD_SELECT_KEY retry idx=N — echo only` spam continuing at 1Hz even on the new connection, suggesting the bike is not receiving or accepting the echo.

### What Was Tried (and Did Not Fix It)

1. **`.apply()` → `.commit()` on all SharedPreferences saves** — keys are confirmed persisted, `loadSessionKeys()` returns 16 keys on 2nd connect. Not the root cause.
2. **Stale GATT callback guard** (`if (gatt !== bluetoothGatt) return` on all callbacks) — reduces noise but does not fix the reconnect stall.
3. **`closeGatt()` null ordering** — `bluetoothGatt = null` before `old.close()`. Structural improvement, did not fix reconnect.
4. **`enableAuthReqIndications()` through write queue** — prevents double-write corruption. Structural improvement, did not fix reconnect.

### Current Theory (Unverified)

The reference app (`KTM-Nav-GEN3-reference`, `BccuConnectionService.kt`) uses **`LifecycleService`** with a coroutine scope (`lifecycleScope.launch`) for the post-disconnect scan delay. Our service uses `Handler.postDelayed` on `mainHandler`. The reference also calls `g.close()` while our `closeGatt()` may have a race between the handler-posted `clearWriteQueue()` and the actual `old.close()` call — the queue clear is async (posted to `gattHandler`) but `old.close()` is synchronous immediately after. This means `onCharacteristicWrite` can still fire on the old client after `close()`, land on `gattHandler`, see a clean queue, and call `drainQueue()` on stale state.

A deeper issue may be that the bike's BCCU firmware on known-device reconnect sends HELLO and immediately (< 100ms) follows with SELECT_KEY. If our M2 write and HELLO echo are delayed in the queue behind the AUTH_REQ CCCD write, the bike may time out and close the GATT before we finish — hence `status=133` on AUTH_REP write.

### Next Debugging Steps

1. Add timestamp logging to every GATT callback entry to measure M1→M2, M2→HELLO, HELLO→echo, echo-ack→SELECT_KEY timing.
2. Compare against reference app timing (reference uses `lifecycleScope` coroutines, not Handlers — timing may differ).
3. Check if `CONNECT_SETTLE_MS = 10_000L` is too long — known-device reconnect may not need 10 seconds of settle.
4. Consider porting the service to extend `LifecycleService` (add `androidx.lifecycle:lifecycle-service` dependency) to match the reference app's coroutine architecture exactly.
5. Check if our `enableAuthReqIndications` → `enqueueCccd()` path is delaying M2 write: the AUTH_REQ CCCD must be written BEFORE M1 can be received. On reconnect, if CCCD needs to be re-written after MTU, it must complete before M1 arrives.

### Reference App Comparison

The open-source app `KTM-Nav-GEN3-reference` (cloned at `D:\KTMLink\KTM-Nav-GEN3-reference`) successfully silently reconnects. Key differences in their implementation:
- Extends `LifecycleService`, uses `lifecycleScope.launch { delay(1500); startBleScan() }` for post-disconnect reconnect
- Uses a single `synchronized(gattQueueLock)` with `gattBusy: Boolean` rather than a HandlerThread queue
- Session keys stored per-MAC: key = `"session_keys_" + deviceAddress.uppercase()`
- `commit()` (synchronous) for all persistence writes
- Handshake timeout armed at `STATE_CONNECTED` (not at M1 arrival)

---

## 14. Phase 2.5 Status — Auto-Launch from Killed State ✓

**Implemented via background BLE scan PendingIntent.**

`KtmBleScanReceiver.kt` is a `BroadcastReceiver` declared in the manifest. When `KTMLinkForegroundService.onDestroy()` runs (service killed by OS), it calls `KtmBleScanReceiver.scheduleBackgroundBleScan(savedMac)` which starts a `SCAN_MODE_LOW_POWER` + `CALLBACK_TYPE_FIRST_MATCH` BLE scan that delivers results via `PendingIntent` — Android fires `KtmBleScanReceiver` directly even with the app process dead.

Coverage matrix:

| Scenario | How handled |
|----------|-------------|
| Phone boots with bike already on | `KtmAclReceiver.BOOT_COMPLETED` |
| Bike BT connects at OS level | `KtmAclReceiver.ACL_CONNECTED` |
| BT adapter toggled on | `KtmAclReceiver.STATE_ON` + internal adapter receiver in service |
| Service killed by OS mid-ride | `KtmBleScanReceiver` background scan PendingIntent |
| Service killed, bike off, bike comes back | `KtmBleScanReceiver` sees advertisement → starts service |

---

## 15. Phase 3 Plan — Polish + Nice-to-Have Features

**Prerequisites:** Resolve Section 13 (silent reconnect bug) first.

### 3a. Production Frontend
- Full production-grade React Native UI
- Connection state display, nav feed card, settings screen
- Replace current debug handshake log with clean status indicators

### 3b. Phone Notification Mirroring
- Intercept incoming call/WhatsApp/SMS notifications
- Write to `NOTIFICATION (070a)` characteristic on KTM dash
- Shows notification banner on TFT display

### 3c. Handlebar Button Handling
- Decode triple-Up button press from RCM_REMOTE_CONTROL (0103) characteristic
- See `BccuConnectionService.kt` reference for button decode logic
- Use for mode overlay, nav dismiss, etc.

### 3d. Weather / Telemetry (PRPC Service)
- PRPC service (`0600`) already stubbed in protocol
- Weather overlay on dash, ride telemetry logging

### 3e. Phase 1 JS Cleanup
- Delete `src/services/BleManager.ts` (no longer called)
- Delete `src/services/NavParser.ts`, `TurnIconMapper.ts`, `KtmProtocol.ts`, `KtmCrypto.ts` (replaced by native Kotlin ports)

---

## 16. Reference Files (src/others/) — DO NOT MODIFY

These are source files from companion/decompiled KTM apps. They are ground truth for protocol correctness.

- `BccuProtocol.kt` — UUID table, all enums, payload builders, `elipse()` function. Byte-exact reference.
- `BccuConnectionService.kt` — 1628-line production BLE service. Mine for reconnect patterns.
- `BccuCrypto.kt` — Crypto reference. All operations confirmed against our `KtmNativeCrypto.kt`.
- `ax0.java` — Decompiled UUID enum from BikeConnect app.
- `tv4.java` — Decompiled GATT queue. Confirms 3-retry, 250ms delay, 10s no-write watchdog.
- `z45.java` — Decompiled GATT callback. Confirms `requestConnectionPriority(HIGH=1)` after MTU.

---

## 17. Testing Protocol

Atharva tests **indoors first** — verify all 6 fields appear correctly in the app's nav feed UI before going to the bike. The app's "NAVIGATION FEED" card shows all 6 parsed values in real time. Only after indoor validation, take to bike for BLE transmission test.

```bash
# Filter logcat to KTMLink tags only
adb logcat -s KTMLinkService GattQueue KtmHandshake KtmCrypto NavParser MapScraper KtmAclReceiver KtmBleScanReceiver
```

---

## 18. Known Non-Issues

- UUID naming difference between `ax0.java` (`0706=TURN_EXTRA_INFO`, `0707=TURN_INFO`) and our code (`0706=TURN_INFO`, `0707=TURN_ROAD`) — `BccuProtocol.kt` (more authoritative source) matches our mapping. Not a bug.
- `src/others/` files showing TypeScript errors — they are Kotlin/Java, not part of the RN build. Ignore.
- `SELECT_KEY retry idx=N — echo only` spam in logs is expected during the first post-auth period when the bike retransmits before receiving our echo. It becomes a bug only if it continues indefinitely without AUTHENTICATED appearing (see Section 13).
