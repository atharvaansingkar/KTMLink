import { BleManager, Device } from 'react-native-ble-plx';
import { PermissionsAndroid, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

export const bleManager = new BleManager();

export const requestBluetoothPermissions = async (): Promise<boolean> => {
  if (Platform.OS === 'android') {
    if (Platform.Version >= 31) {
      const perms: any[] = [
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      ];
      // POST_NOTIFICATIONS required on Android 13+ (API 33) to show the service notification
      if (Platform.Version >= 33) {
        perms.push('android.permission.POST_NOTIFICATIONS');
      }
      const result = await PermissionsAndroid.requestMultiple(perms);
      return (
        result['android.permission.BLUETOOTH_CONNECT'] === PermissionsAndroid.RESULTS.GRANTED &&
        result['android.permission.BLUETOOTH_SCAN'] === PermissionsAndroid.RESULTS.GRANTED &&
        result['android.permission.ACCESS_FINE_LOCATION'] === PermissionsAndroid.RESULTS.GRANTED
      );
    } else {
      const result = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      );
      return result === PermissionsAndroid.RESULTS.GRANTED;
    }
  }
  return true;
};

export const stopScan = () => {
  bleManager.stopDeviceScan();
};

// ─── Imports ──────────────────────────────────────────────────────────────────
import {
  KTM_UUIDS,
  Visibility,
  buildTurnIconPayload,
  buildTurnDistancePayload,
  buildTurnInfoPayload,
  buildTurnRoadPayload,
  buildEtaPayload,
  buildRemainingDistPayload,
  buildNotificationPayload,
  buildNavigationStatePayload,
  NotificationIconType,
} from './KtmProtocol';
import { TurnIcon } from './TurnIconMapper';
import {
  computeTempIvAndSecret,
  getRandomBytes,
  decryptAndUnframeData,
  frameAndEncryptData,
  buildMirrored,
  deriveSessionKeys,
  buildControlPacket,
  decryptControl,
  encryptControl,
} from './KtmCrypto';

// ─── Base64 Helpers (Pure JS — no Buffer polyfill needed) ─────────────────────
const bytesToBase64 = (bytes: Uint8Array): string => {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  let str = '';
  for (let i = 0; i < bytes.length; i += 3) {
    let b1 = bytes[i], b2 = bytes[i+1] || 0, b3 = bytes[i+2] || 0;
    str += chars[b1 >> 2];
    str += chars[((b1 & 3) << 4) | (b2 >> 4)];
    str += i + 1 < bytes.length ? chars[((b2 & 15) << 2) | (b3 >> 6)] : '=';
    str += i + 2 < bytes.length ? chars[b3 & 63] : '=';
  }
  return str;
};

const base64ToBytes = (base64: string): Uint8Array => {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const lookup = new Uint8Array(256);
  for (let i = 0; i < chars.length; i++) {
    lookup[chars.charCodeAt(i)] = i;
  }
  let bufferLength = base64.length * 0.75,
  len = base64.length, i, p = 0,
  encoded1, encoded2, encoded3, encoded4;
  if (base64[base64.length - 1] === "=") {
    bufferLength--;
    if (base64[base64.length - 2] === "=") {
      bufferLength--;
    }
  }
  const bytes = new Uint8Array(bufferLength);
  for (i = 0; i < len; i+=4) {
    encoded1 = lookup[base64.charCodeAt(i)];
    encoded2 = lookup[base64.charCodeAt(i+1)];
    encoded3 = lookup[base64.charCodeAt(i+2)];
    encoded4 = lookup[base64.charCodeAt(i+3)];
    bytes[p++] = (encoded1 << 2) | (encoded2 >> 4);
    bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2);
    bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63);
  }
  return bytes;
};

// ─── State ────────────────────────────────────────────────────────────────────
let sessionKeys: Uint8Array[] = [];
let activeSessionKey: Uint8Array | null = null;
let currentTempIv: Uint8Array | null = null;
let currentTempSecret: Uint8Array | null = null;
let connectedDevice: Device | null = null;

// ─── Reconnect State ──────────────────────────────────────────────────────────
// Mirrors BccuConnectionService.kt's handshake recovery + reconnect watchdog.
const MAX_RECONNECT_ATTEMPTS = 5;
const RECONNECT_COOLDOWN_MS = 3000;
let reconnectAttempts = 0;
let reconnectTargetId: string | null = null;
let reconnectEnabled = false;
let disconnectSubscription: { remove: () => void } | null = null;

// ─── Handshake Callbacks ──────────────────────────────────────────────────────
export type HandshakeCallbacks = {
  onDeviceFound:    (name: string) => void;
  onNoncesSwapped:  () => void;
  onHelloExchanged: () => void;
  onKeysGenerated:  () => void;
  onAuthenticated:  () => void;
  onDisconnected?:  () => void;
  onReconnecting?:  (attempt: number, max: number) => void;
};

let handshakeCallbacks: HandshakeCallbacks | null = null;

export const setHandshakeCallbacks = (callbacks: HandshakeCallbacks) => {
  handshakeCallbacks = callbacks;
};

// ─── GATT Write Queue with Coalescing ─────────────────────────────────────────
// Mirrors BccuConnectionService.kt's enqueueGattOp / gattBusy / gattOpFinished.
//
// KEY ADDITION: coalesceKey support. For "latest value wins" display writes
// (guidance labels, marquee frames), a still-queued older write to the same
// characteristic is dropped — keeping the queue bounded and frames fresh on
// a slow BLE link. Control-plane ops (auth replies) use null coalesceKey.
type GattOp = {
  label: string;
  coalesceKey: string | null;
  run: () => Promise<void>;
};

const gattQueue: GattOp[] = [];
let gattBusy = false;

const enqueueGattOp = (label: string, op: () => Promise<void>, coalesceKey: string | null = null) => {
  // If coalescing, remove any queued op with the same key
  if (coalesceKey) {
    for (let i = gattQueue.length - 1; i >= 0; i--) {
      if (gattQueue[i].coalesceKey === coalesceKey) {
        console.log(`[GattQueue] Coalescing: dropped stale ${gattQueue[i].label}`);
        gattQueue.splice(i, 1);
      }
    }
  }
  gattQueue.push({ label, coalesceKey, run: op });
  console.log(`[GattQueue] Enqueued: ${label} (depth=${gattQueue.length})`);
  runNextGattOp();
};

const runNextGattOp = async () => {
  if (gattBusy) return;
  const next = gattQueue.shift();
  if (!next) return;
  gattBusy = true;
  console.log(`[GattQueue] Executing: ${next.label}`);
  try {
    await next.run();
    console.log(`[GattQueue] Success: ${next.label}`);
  } catch (err: any) {
    console.error(`[GattQueue] FAILED: ${next.label}`, err?.message || err);
    // On failure, retry once after 250ms (matching tv4.java postDelayed(250))
    const retryOp = next;
    setTimeout(() => {
      console.log(`[GattQueue] Retrying: ${retryOp.label}`);
      gattQueue.unshift(retryOp);
      gattBusy = false;
      runNextGattOp();
    }, 250);
    return;
  }
  gattBusy = false;
  runNextGattOp();
};

/**
 * Write to a characteristic via the GATT queue.
 * @param coalesce - If true, drops any older queued write to the same char UUID.
 *                   Use for display writes. Never for auth/control writes.
 */
const queuedWrite = (
  device: Device,
  serviceUuid: string,
  charUuid: string,
  base64Data: string,
  label: string,
  coalesce: boolean = false,
) => {
  enqueueGattOp(label, async () => {
    await device.writeCharacteristicWithResponseForService(
      serviceUuid,
      charUuid,
      base64Data,
    );
  }, coalesce ? charUuid : null);
};

// ─── Welcome Screen ───────────────────────────────────────────────────────────
/**
 * Show the permanent welcome state on the KTM dash.
 * Clears the 4 stale detail fields first (space + OFF prevents the dash from
 * keeping old rendered values), then sets the greeting and START icon.
 * All writes bypass coalescing so they can never be dropped by incoming nav data.
 */
export const showWelcomeScreen = (): void => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) return;

  stopMarquee();
  console.log('[BleManager] Showing welcome screen');

  // Drain any pending coalesced display writes — prevents a late nav update from
  // overwriting the welcome state immediately after this call.
  for (let i = gattQueue.length - 1; i >= 0; i--) {
    if (gattQueue[i].coalesceKey !== null) {
      console.log(`[GattQueue] Flushing stale display write: ${gattQueue[i].label}`);
      gattQueue.splice(i, 1);
    }
  }

  const off = Visibility.OFF;
  const d = connectedDevice;
  const key = activeSessionKey;
  const iv = currentTempIv;

  // Clear the 4 detail fields — space + OFF forces the dash to blank them
  const distPayload = buildTurnDistancePayload(' ', off);
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_DISTANCE,
    bytesToBase64(frameAndEncryptData(distPayload, key, iv)), 'Welcome: clear distance');

  const infoPayload = buildTurnInfoPayload(' ', off);
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_INFO,
    bytesToBase64(frameAndEncryptData(infoPayload, key, iv)), 'Welcome: clear info');

  const etaPayload = buildEtaPayload(' ', off);
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.ETA,
    bytesToBase64(frameAndEncryptData(etaPayload, key, iv)), 'Welcome: clear ETA');

  const remPayload = buildRemainingDistPayload(' ', off);
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.REMAINING_DISTANCE,
    bytesToBase64(frameAndEncryptData(remPayload, key, iv)), 'Welcome: clear remaining');

  // Road name first — panel stays visible while icon swaps
  const roadPayload = buildTurnRoadPayload('Hello Atharva!');
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ROAD,
    bytesToBase64(frameAndEncryptData(roadPayload, key, iv)), 'Welcome: greeting');

  // Icon last — single visible transition, no blank frame
  const iconPayload = buildTurnIconPayload(TurnIcon.START, Visibility.FULL);
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ICON,
    bytesToBase64(frameAndEncryptData(iconPayload, key, iv)), 'Welcome: START icon');
};

// ─── Remaining Distance Alternating Display ───────────────────────────────────
// Alternates between remaining distance and remaining time every 2 seconds.
// Each value fits cleanly in 8 chars on its own — no truncation, fully readable.
// If only one value is available, writes it once as a static value.

const ALT_INTERVAL_MS = 2000;

let altTimer: ReturnType<typeof setInterval> | null = null;
let altDistText = '';
let altTimeText = '';

const writeRemainingSlot = (text: string) => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) return;
  const vis = text ? Visibility.FULL : Visibility.OFF;
  const payload = buildRemainingDistPayload(text, vis);
  const enc = frameAndEncryptData(payload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.REMAINING_DISTANCE,
    bytesToBase64(enc), 'Slot6: remaining', true);
};

const stopMarquee = () => {
  if (altTimer) {
    clearInterval(altTimer);
    altTimer = null;
  }
  altDistText = '';
  altTimeText = '';
};

export const startRemainingMarquee = (distText: string, timeText: string): void => {
  if (!distText && !timeText) { stopMarquee(); return; }

  // Only one value — static write, no alternation needed
  if (!distText || !timeText) {
    // Still restart if the single value changed
    if (distText !== altDistText || timeText !== altTimeText) {
      stopMarquee();
      altDistText = distText;
      altTimeText = timeText;
      writeRemainingSlot(distText || timeText);
    }
    return;
  }

  // Both values present — only (re)start the timer if the values changed.
  // Google Maps fires notifications every ~1s; without this guard the timer
  // is reset on every notification and never reaches the 2s mark.
  if (distText === altDistText && timeText === altTimeText && altTimer !== null) {
    return; // values unchanged, timer already running — leave it alone
  }

  stopMarquee();
  altDistText = distText;
  altTimeText = timeText;

  let showingDist = true;
  writeRemainingSlot(distText);

  altTimer = setInterval(() => {
    if (!activeSessionKey) { stopMarquee(); return; }
    showingDist = !showingDist;
    writeRemainingSlot(showingDist ? altDistText : altTimeText);
  }, ALT_INTERVAL_MS);
};

// ─── Activation Sequence ──────────────────────────────────────────────────────
const activateDashboard = (device: Device) => {
  if (!activeSessionKey || !currentTempIv) {
    console.error('[BleManager] Cannot activate dashboard: keys missing.');
    return;
  }

  // Unlock display engine: guidanceOn + gpsIconOn
  // The dash requires this before it will render TURN_ICON/TURN_ROAD content.
  // Confirmed from BccuConnectionService.kt line 967.
  const navStatePayload = buildNavigationStatePayload(true, true);
  const encNavState = frameAndEncryptData(navStatePayload, activeSessionKey, currentTempIv);
  queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.NAVIGATION_STATE,
    bytesToBase64(encNavState), 'NavState: guidance ON');

  // Show welcome screen — stays permanently until real nav arrives
  showWelcomeScreen();
};

// ─── Auto-Reconnect on Disconnect ─────────────────────────────────────────────
const handleBleDisconnect = (deviceId: string) => {
  console.log(`[BleManager] BLE link dropped for ${deviceId}`);
  connectedDevice = null;
  gattQueue.length = 0;
  gattBusy = false;

  if (activeSessionKey) {
    console.log('[BleManager] Was authenticated — resetting session state.');
    currentTempIv = null;
    currentTempSecret = null;
    activeSessionKey = null;
    handshakeCallbacks?.onDisconnected?.();
    return;
  }

  if (reconnectEnabled && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
    reconnectAttempts++;
    const attempt = reconnectAttempts;
    console.log(`[BleManager] Auto-reconnect attempt ${attempt}/${MAX_RECONNECT_ATTEMPTS} in ${RECONNECT_COOLDOWN_MS}ms...`);
    handshakeCallbacks?.onReconnecting?.(attempt, MAX_RECONNECT_ATTEMPTS);

    setTimeout(async () => {
      try {
        await connectToDevice(deviceId);
      } catch (e) {
        console.error(`[BleManager] Reconnect attempt ${attempt} failed:`, e);
      }
    }, RECONNECT_COOLDOWN_MS);
  } else {
    console.log('[BleManager] Max reconnect attempts reached or reconnect disabled.');
    handshakeCallbacks?.onDisconnected?.();
  }
};

// ─── Core Connection ──────────────────────────────────────────────────────────
export const connectToDevice = async (deviceId: string): Promise<string[]> => {
  try {
    console.log('[BleManager] Connecting directly to:', deviceId);

    if (disconnectSubscription) {
      disconnectSubscription.remove();
      disconnectSubscription = null;
    }
    if (connectedDevice && connectedDevice.id === deviceId) {
      console.log('[BleManager] Tearing down zombie GATT connection...');
      await connectedDevice.cancelConnection().catch(() => {});
      connectedDevice = null;
    }
    await bleManager.cancelDeviceConnection(deviceId).catch(() => {});

    // Reset handshake state
    currentTempIv = null;
    currentTempSecret = null;
    activeSessionKey = null;
    gattQueue.length = 0;
    gattBusy = false;
    reconnectTargetId = deviceId;
    reconnectEnabled = true;
    stopScan();

    const device = await bleManager.connectToDevice(deviceId);
    console.log('[BleManager] Connected to:', device.name);

    disconnectSubscription = device.onDisconnected((error) => {
      console.log('[BleManager] onDisconnected fired:', error?.message || 'clean');
      handleBleDisconnect(deviceId);
    });

    await device.discoverAllServicesAndCharacteristics();
    console.log('[BleManager] Services discovered.');

    await device.requestMTU(517);
    console.log('[BleManager] MTU expanded to 517.');

    // Shorten BLE connection interval to HIGH (7.5ms) — confirmed from z45.java.
    // Without this, 6 sequential GATT writes at the default ~30ms interval = ~180ms per nav update.
    await device.requestConnectionPriority(1).catch(() => {});
    console.log('[BleManager] Connection priority set to HIGH.');

    if (device.name?.includes('KTM')) {
      connectedDevice = device;
      console.log('[BleManager] Starting KTM AES handshake...');
      handshakeCallbacks?.onDeviceFound(device.name ?? deviceId);

      device.monitorCharacteristicForService(
        KTM_UUIDS.MAIN_SERVICE,
        KTM_UUIDS.AUTH_REQ,
        (error, characteristic) => {
          if (error) {
            console.error('[BleManager] AUTH_REQ monitor error:', error);
            return;
          }
          if (!characteristic?.value) return;
          const rawBytes = base64ToBytes(characteristic.value);

          // Detach from BLE callback thread — same pattern as BccuConnectionService.kt
          setTimeout(() => {
            handleAuthIndication(device, rawBytes);
          }, 0);
        },
      );
    }

    const results: string[] = [`Connected: ${device.name || deviceId}`];
    device.services().then(async svcs => {
      for (const svc of svcs) {
        console.log(`[BleManager] Service: ${svc.uuid}`);
      }
    }).catch(() => {});

    return results;

  } catch (error) {
    console.error('[BleManager] Connection error:', error);
    throw error;
  }
};

// ─── Auth Indication Handler ──────────────────────────────────────────────────
const handleAuthIndication = async (device: Device, rawBytes: Uint8Array) => {
  try {
    if (rawBytes.length === 16 && !currentTempIv) {
      const m1 = rawBytes;
      console.log('[BleManager] m1 received. Generating m2...');
      const m2 = getRandomBytes(16);
      const { tempIv, tempSecret } = computeTempIvAndSecret(m1, m2);
      currentTempIv = tempIv;
      currentTempSecret = tempSecret;

      queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(m2), 'Auth: m2 nonce');
      handshakeCallbacks?.onNoncesSwapped();

    } else if (currentTempIv && currentTempSecret) {
      const decrypted = decryptControl(rawBytes, currentTempSecret, currentTempIv);
      const cmd = decrypted[2] & 0xFF;
      console.log(`[BleManager] Control message received. cmd=0x${cmd.toString(16)}`);

      if (cmd === 0x00) {
        console.log('[BleManager] CMD_HELLO — echoing back.');
        const reply = buildControlPacket(0x00);
        const enc   = encryptControl(reply, currentTempSecret, currentTempIv);
        queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(enc), 'Auth: echo HELLO');
        handshakeCallbacks?.onHelloExchanged();

      } else if (cmd === 0x01) {
        console.log('[BleManager] CMD_GENERATE_KEYS — deriving SHA-512 pool...');
        const mirrored  = buildMirrored(decrypted);
        sessionKeys     = deriveSessionKeys(decrypted, mirrored, currentTempIv, currentTempSecret);

        const b64Keys = sessionKeys.map(k => bytesToBase64(k));
        await AsyncStorage.setItem('ktm_session_keys', JSON.stringify(b64Keys));

        const reply = buildControlPacket(0x02);
        const enc   = encryptControl(reply, currentTempSecret, currentTempIv);
        queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(enc), 'Auth: KEYS_GENERATED');
        handshakeCallbacks?.onKeysGenerated();

      } else if (cmd >= 0x10 && cmd <= 0x1F) {
        const keyIndex = cmd & 0x0F;
        console.log(`[BleManager] CMD_SELECT_KEY — index ${keyIndex}.`);

        if (sessionKeys.length === 0) {
          const stored = await AsyncStorage.getItem('ktm_session_keys');
          if (stored) {
            try {
              const b64Keys = JSON.parse(stored);
              sessionKeys = b64Keys.map((str: string) => base64ToBytes(str));
              console.log('[BleManager] Restored session keys from local storage.');
            } catch (e) {
              console.error('[BleManager] Failed to parse stored keys', e);
            }
          }
        }

        if (sessionKeys.length > keyIndex) {
          activeSessionKey = sessionKeys[keyIndex];
          reconnectAttempts = 0;
          reconnectEnabled = false;
          console.log('[BleManager] >>> AUTHENTICATED <<< Session key locked in.');

          const reply = buildControlPacket(0x10 | keyIndex);
          const enc   = encryptControl(reply, currentTempSecret, currentTempIv);
          queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(enc), 'Auth: KEY_ACK');

          handshakeCallbacks?.onAuthenticated();

          // Activate dashboard: navState ON + greeting
          activateDashboard(device);
        } else {
          console.error('[BleManager] Session key index out of range:', keyIndex);
        }
      }
    }
  } catch (e) {
    console.error('[BleManager] Failed to handle control message:', e);
  }
};

// ─── Full Navigation Pipeline ─────────────────────────────────────────────────
/**
 * Routes real-time Google Maps data into ALL KTM dash characteristics.
 * This is the complete pipeline — TURN_ICON, TURN_DISTANCE, TURN_ROAD,
 * TURN_INFO, ETA, and REMAINING_DISTANCE.
 *
 * @param data.distance           - "300 m", "1.2 km"    → TURN_DISTANCE (0705)
 * @param data.road               - "MG Road"             → TURN_ROAD (0707)
 * @param data.turnIcon           - TurnIcon enum value   → TURN_ICON (0704)
 * @param data.maneuver           - "Turn left"           → TURN_INFO (0706)
 * @param data.eta                - "12:45pm"             → ETA (0708)
 * @param data.remainingDistance  - "23 km"               → REMAINING_DISTANCE (0709) marquee
 * @param data.timeRemaining      - "28 min"              → combined into REMAINING_DISTANCE marquee
 */
export const streamLiveNavigation = async (data: {
  distance: string;
  road: string;
  turnIcon: number;
  maneuver: string;
  eta: string;
  remainingDistance: string;
  timeRemaining: string;
}): Promise<void> => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) {
    return; // Not authenticated — drop silently
  }

  console.log('\n=========================================');
  console.log('[KTM DASHBOARD MAPPING]');
  console.log(`1. TURN_ICON         (0704) : ${data.turnIcon}`);
  console.log(`2. TURN_ROAD         (0707) : "${data.road}"`);
  console.log(`3. TURN_INFO         (0706) : "${data.maneuver}"`);
  console.log(`4. TURN_DISTANCE     (0705) : "${data.distance}"`);
  console.log(`5. ETA               (0708) : "${data.eta}"`);
  console.log(`6. REMAINING_DIST    (0709) : "${data.remainingDistance}" / "${data.timeRemaining}" (alternating)`);
  console.log('=========================================\n');

  // 1. TURN_ICON (0704)
  const iconPayload = buildTurnIconPayload(data.turnIcon, Visibility.FULL);
  const encIcon = frameAndEncryptData(iconPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ICON,
    bytesToBase64(encIcon), 'Nav: icon', true);

  // 2. TURN_DISTANCE (0705)
  const distText = data.distance.trim();
  const distPayload = buildTurnDistancePayload(distText, distText ? Visibility.FULL : Visibility.OFF);
  const encDist = frameAndEncryptData(distPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_DISTANCE,
    bytesToBase64(encDist), 'Nav: distance', true);

  // 3. TURN_INFO (0706) — "· Turn left" format matching official KTM Connect app
  const infoRaw = data.maneuver.trim();
  const infoText = infoRaw ? `· ${infoRaw}` : '';
  const infoPayload = buildTurnInfoPayload(infoText, infoText ? Visibility.FULL : Visibility.OFF);
  const encInfo = frameAndEncryptData(infoPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_INFO,
    bytesToBase64(encInfo), 'Nav: maneuver', true);

  // 4. TURN_ROAD (0707)
  const roadText = data.road.trim();
  const roadPayload = buildTurnRoadPayload(roadText, roadText ? Visibility.FULL : Visibility.OFF);
  const encRoad = frameAndEncryptData(roadPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ROAD,
    bytesToBase64(encRoad), 'Nav: road', true);

  // 5. ETA (0708)
  const etaText = data.eta.trim();
  const etaPayload = buildEtaPayload(etaText, etaText ? Visibility.FULL : Visibility.OFF);
  const encEta = frameAndEncryptData(etaPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.ETA,
    bytesToBase64(encEta), 'Nav: ETA', true);

  // 6. REMAINING_DISTANCE (0709) — alternates distance and time every 2s
  startRemainingMarquee(data.remainingDistance.trim(), data.timeRemaining.trim());
};

// ─── Guidance Clear ───────────────────────────────────────────────────────────
/**
 * Blank the dash's center guidance view — all 6 nav characteristics set to
 * empty text + visibility OFF, turn icon set to UNDEFINED + OFF.
 * Matches BikeConnect's disableGuidanceAndNotificationWidgets().
 * BccuConnectionService.kt line 1376-1396.
 *
 * Call this when Google Maps navigation ends (notification removed).
 */
export const clearGuidance = (): void => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) return;

  console.log('[BleManager] Clearing center guidance (navigation ended)');

  const off = Visibility.OFF;

  // TURN_ICON → UNDEFINED + OFF
  const iconPayload = buildTurnIconPayload(TurnIcon.UNDEFINED, off);
  const encIcon = frameAndEncryptData(iconPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ICON,
    bytesToBase64(encIcon), 'Clear: icon', true);

  // TURN_DISTANCE → empty + OFF
  const distPayload = buildTurnDistancePayload('', off);
  const encDist = frameAndEncryptData(distPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_DISTANCE,
    bytesToBase64(encDist), 'Clear: distance', true);

  // TURN_INFO → empty + OFF
  const infoPayload = buildTurnInfoPayload('', off);
  const encInfo = frameAndEncryptData(infoPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_INFO,
    bytesToBase64(encInfo), 'Clear: info', true);

  // TURN_ROAD → empty + OFF
  const roadPayload = buildTurnRoadPayload('', off);
  const encRoad = frameAndEncryptData(roadPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ROAD,
    bytesToBase64(encRoad), 'Clear: road', true);

  // ETA → empty + OFF
  const etaPayload = buildEtaPayload('', off);
  const encEta = frameAndEncryptData(etaPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.ETA,
    bytesToBase64(encEta), 'Clear: ETA', true);

  // REMAINING_DISTANCE → empty + OFF
  const remPayload = buildRemainingDistPayload('', off);
  const encRem = frameAndEncryptData(remPayload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.REMAINING_DISTANCE,
    bytesToBase64(encRem), 'Clear: remaining', true);
};

// ─── Dashboard Notifications ──────────────────────────────────────────────────
export const sendDashboardNotification = async (
  text: string,
  iconByte: number = NotificationIconType.INFORMATION,
): Promise<void> => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) {
    console.error('[BleManager] Cannot send notification: not authenticated.');
    return;
  }

  if (text.trim()) {
    const notifPayload = buildNotificationPayload(text, iconByte);
    const encNotif = frameAndEncryptData(notifPayload, activeSessionKey, currentTempIv);
    queuedWrite(
      connectedDevice,
      KTM_UUIDS.MAIN_SERVICE,
      KTM_UUIDS.NOTIFICATION,
      bytesToBase64(encNotif),
      'Notification (070a)',
      true,
    );
  }
};

/**
 * Clear the notification banner.
 * Confirmed from BccuConnectionService.kt clearNotificationDisplay():
 * keep the last icon but flip visibility to OFF.
 */
export const clearNotificationBanner = (): void => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) return;

  const payload = buildNotificationPayload('', NotificationIconType.INFORMATION, Visibility.OFF);
  const enc = frameAndEncryptData(payload, activeSessionKey, currentTempIv);
  queuedWrite(connectedDevice, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.NOTIFICATION,
    bytesToBase64(enc), 'Clear: notification', true);
};

// ─── Icon Test Helper ─────────────────────────────────────────────────────────
/**
 * Send a specific turn icon + label to the dash for testing.
 * Replaces the icon and TURN_ROAD text; leaves all 4 detail fields blank.
 * Call showWelcomeScreen() to return to the normal welcome state.
 */
export const sendTestIcon = (iconValue: number, iconLabel: string): void => {
  if (!activeSessionKey || !currentTempIv || !connectedDevice) return;

  const key = activeSessionKey;
  const iv = currentTempIv;
  const d = connectedDevice;

  const off = Visibility.OFF;

  // Blank the 4 detail fields
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_DISTANCE,
    bytesToBase64(frameAndEncryptData(buildTurnDistancePayload(' ', off), key, iv)), 'Test: clear distance');
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_INFO,
    bytesToBase64(frameAndEncryptData(buildTurnInfoPayload(' ', off), key, iv)), 'Test: clear info');
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.ETA,
    bytesToBase64(frameAndEncryptData(buildEtaPayload(' ', off), key, iv)), 'Test: clear ETA');
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.REMAINING_DISTANCE,
    bytesToBase64(frameAndEncryptData(buildRemainingDistPayload(' ', off), key, iv)), 'Test: clear remaining');

  // Road line = icon name
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ROAD,
    bytesToBase64(frameAndEncryptData(buildTurnRoadPayload(iconLabel), key, iv)), 'Test: icon label');

  // Icon
  queuedWrite(d, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.TURN_ICON,
    bytesToBase64(frameAndEncryptData(buildTurnIconPayload(iconValue, Visibility.FULL), key, iv)), 'Test: icon');
};

// ─── Manual Write Helper ──────────────────────────────────────────────────────
const encodeToBase64 = (input: string): string => {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  let str = input, output = '';
  for (
    let block = 0, charCode, i = 0, map = chars;
    str.charAt(i | 0) || ((map = '='), i % 1);
    output += map.charAt(63 & (block >> (8 - (i % 1) * 8)))
  ) {
    charCode = str.charCodeAt((i += 3 / 4));
    if (charCode > 0xff) console.warn('encodeToBase64: chars outside latin1');
    block = (block << 8) | charCode;
  }
  return output;
};

export const KTM_SERVICE_UUID = '71ced1ac-0700-44f5-9454-806ff70b3e02';

export const shootData = async (
  deviceId: string,
  characteristicUuid: string,
  payload: string,
): Promise<{success: boolean; message: string}> => {
  try {
    const b64 = encodeToBase64(payload);
    await bleManager.writeCharacteristicWithResponseForDevice(
      deviceId,
      KTM_SERVICE_UUID,
      characteristicUuid,
      b64,
    );
    return {success: true, message: 'Write Success'};
  } catch (error: any) {
    console.error('[BleManager] Write failed:', error);
    return {success: false, message: `Write Failed (${error?.message || 'Error'})`};
  }
};
