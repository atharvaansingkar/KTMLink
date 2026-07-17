import { BleManager, Device } from 'react-native-ble-plx';
import { PermissionsAndroid, Platform } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

export const bleManager = new BleManager();

export const requestBluetoothPermissions = async (): Promise<boolean> => {
  if (Platform.OS === 'android') {
    if (Platform.Version >= 31) {
      const result = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      ]);
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
import { KTM_UUIDS } from './KtmProtocol';
import { buildTurnRoadPayload, buildTurnDistancePayload } from './KtmProtocol';
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
// When the bike drops the BLE link (15s auth timeout, radio recycle, etc.),
// we automatically retry up to MAX_RECONNECT_ATTEMPTS times with a brief
// cooldown between attempts.
const MAX_RECONNECT_ATTEMPTS = 5;
const RECONNECT_COOLDOWN_MS = 3000; // 3 seconds between retries
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

// ─── GATT Write Queue ─────────────────────────────────────────────────────────
// Mirrors BccuConnectionService.kt's enqueueGattOp / gattBusy / gattOpFinished
// pattern. Android's BLE stack is single-threaded: only ONE GATT operation can
// be in flight at a time. The official app enforces this with an ArrayDeque +
// a gattBusy flag. react-native-ble-plx serialises internally, but its
// Promise-based API doesn't protect against firing a write while the stack is
// still ACK'ing an incoming Indication. This queue adds explicit pacing.
//
// Key insight from tv4.java: on write SUCCESS, drain immediately (j()).
// On write FAILURE (133/201), retry after 250ms (postDelayed).
type GattOp = {
  label: string;
  run: () => Promise<void>;
};

const gattQueue: GattOp[] = [];
let gattBusy = false;

const enqueueGattOp = (label: string, op: () => Promise<void>) => {
  gattQueue.push({ label, run: op });
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
    return; // Don't drain yet — wait for the retry timer
  }
  gattBusy = false;
  runNextGattOp(); // Drain next immediately on success
};

/**
 * Write to a characteristic via the GATT queue, matching the official app's
 * WRITE_TYPE_DEFAULT behavior. All writes go through here — never direct.
 */
const queuedWrite = (
  device: Device,
  serviceUuid: string,
  charUuid: string,
  base64Data: string,
  label: string,
) => {
  enqueueGattOp(label, async () => {
    await device.writeCharacteristicWithResponseForService(
      serviceUuid,
      charUuid,
      base64Data,
    );
  });
};

// ─── Activation Sequence ──────────────────────────────────────────────────────
const activateDashboard = (device: Device) => {
  // 1. Unlock display engine: write [0x03, 0xFF] to NAVIGATION_STATE (0703)
  //    0x03 = guidanceOn + gpsIconOn
  const unlockPayload = new Uint8Array([0x03, 0xFF]);
  queuedWrite(
    device,
    KTM_UUIDS.MAIN_SERVICE,
    KTM_UUIDS.NAVIGATION_STATE,
    bytesToBase64(unlockPayload),
    'Unlock display (0703)',
  );

  // 2. Send greeting: "Hello Atharva!" via TURN_ROAD (0707)
  const greetingPayload = buildTurnRoadPayload('Hello Atharva!');
  queuedWrite(
    device,
    KTM_UUIDS.MAIN_SERVICE,
    KTM_UUIDS.TURN_ROAD,
    bytesToBase64(greetingPayload),
    'Greeting (0707)',
  );
};

// ─── Auto-Reconnect on Disconnect ─────────────────────────────────────────────
const handleBleDisconnect = (deviceId: string) => {
  console.log(`[BleManager] BLE link dropped for ${deviceId}`);
  connectedDevice = null;
  gattQueue.length = 0;
  gattBusy = false;

  // If we already authenticated, don't auto-reconnect (the session is over)
  if (activeSessionKey) {
    console.log('[BleManager] Was authenticated — resetting session state.');
    currentTempIv = null;
    currentTempSecret = null;
    activeSessionKey = null;
    handshakeCallbacks?.onDisconnected?.();
    return;
  }

  // If we were mid-handshake and haven't exhausted retries, auto-reconnect
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
        // handleBleDisconnect will fire again if the connection drops,
        // which will trigger the next retry automatically.
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

    // ── Preemptive Socket Safety Check ──
    // Remove any previous disconnect listener
    if (disconnectSubscription) {
      disconnectSubscription.remove();
      disconnectSubscription = null;
    }
    if (connectedDevice && connectedDevice.id === deviceId) {
      console.log('[BleManager] Tearing down zombie GATT connection...');
      await connectedDevice.cancelConnection().catch(() => {});
      connectedDevice = null;
    }
    // Ensure the manager drops any native lingering socket for this ID
    await bleManager.cancelDeviceConnection(deviceId).catch(() => {});

    // Reset handshake state for a clean session
    currentTempIv = null;
    currentTempSecret = null;
    activeSessionKey = null;
    gattQueue.length = 0;
    gattBusy = false;
    reconnectTargetId = deviceId;
    reconnectEnabled = true;

    stopScan();

    // 1. Connect
    const device = await bleManager.connectToDevice(deviceId);
    console.log('[BleManager] Connected to:', device.name);

    // 2. Register disconnect listener IMMEDIATELY after connection
    //    (mirrors BccuConnectionService's onConnectionStateChange DISCONNECTED handler)
    disconnectSubscription = device.onDisconnected((error) => {
      console.log('[BleManager] onDisconnected fired:', error?.message || 'clean');
      handleBleDisconnect(deviceId);
    });

    // 3. Discover services & characteristics
    await device.discoverAllServicesAndCharacteristics();
    console.log('[BleManager] Services discovered.');

    // 4. MTU expansion — KTM requires 517 for AES payloads.
    //    This is the ONE AND ONLY place requestMTU is called.
    await device.requestMTU(517);
    console.log('[BleManager] MTU expanded to 517.');

    // 5. KTM Handshake — start monitoring AUTH_REQ IMMEDIATELY.
    //    Speed is critical: the bike's firmware has a ~15s timeout for
    //    unauthenticated BLE links. Every millisecond counts.
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

          // ── CRITICAL: Detach processing from the BLE callback thread ──
          // Android's GATT stack is single-threaded. The onCharacteristicChanged
          // callback (which delivers this Indication) must RETURN before the
          // stack will process any new GATT operation (our write). If we await
          // a write inside this callback, the write is submitted while the
          // stack is still locked sending the Indication ACK → status 133/201.
          //
          // The official app (BccuConnectionService.kt) handles this naturally
          // because Kotlin coroutines + enqueueGattOp post the work to a
          // separate handler. We replicate this with setTimeout(0).
          setTimeout(() => {
            handleAuthIndication(device, rawBytes);
          }, 0);
        },
      );
    }

    // Return minimal info immediately — do NOT enumerate all services here.
    // The old loop (services → characteristics) was burning seconds during
    // the bike's 15-second auth timeout window. Log asynchronously instead.
    const results: string[] = [`Connected: ${device.name || deviceId}`];
    // Fire-and-forget service enumeration for debug (non-blocking)
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

// ─── Auth Indication Handler (runs OUTSIDE the BLE callback thread) ──────────
const handleAuthIndication = async (device: Device, rawBytes: Uint8Array) => {
  try {
    if (rawBytes.length === 16 && !currentTempIv) {
      // ── Step 1: Nonce Exchange ──
      const m1 = rawBytes;
      console.log('[BleManager] m1 received. Generating m2...');
      const m2 = getRandomBytes(16);
      const { tempIv, tempSecret } = computeTempIvAndSecret(m1, m2);
      currentTempIv = tempIv;
      currentTempSecret = tempSecret;

      // m2 is sent as plaintext (not encrypted), matching BccuConnectionService L886
      queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(m2), 'Auth: m2 nonce');
      handshakeCallbacks?.onNoncesSwapped();

    } else if (currentTempIv && currentTempSecret) {
      // ── Steps 2–4: Encrypted Control Flow ──
      const decrypted = decryptControl(rawBytes, currentTempSecret, currentTempIv);
      const cmd = decrypted[2] & 0xFF; // command lives at index 2 per protocol
      console.log(`[BleManager] Control message received. cmd=0x${cmd.toString(16)}`);

      if (cmd === 0x00) {
        // CMD_HELLO — always echo HELLO back (BccuConnectionService L900-913)
        console.log('[BleManager] CMD_HELLO — echoing back.');
        const reply = buildControlPacket(0x00);
        const enc   = encryptControl(reply, currentTempSecret, currentTempIv);
        queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(enc), 'Auth: echo HELLO');
        handshakeCallbacks?.onHelloExchanged();

      } else if (cmd === 0x01) {
        // CMD_GENERATE_KEYS (BccuConnectionService L915-927)
        console.log('[BleManager] CMD_GENERATE_KEYS — deriving SHA-512 pool...');
        const mirrored  = buildMirrored(decrypted);
        sessionKeys     = deriveSessionKeys(decrypted, mirrored, currentTempIv, currentTempSecret);

        // Persist session keys for fast-reconnection (BccuConnectionService L924-926)
        const b64Keys = sessionKeys.map(k => bytesToBase64(k));
        await AsyncStorage.setItem('ktm_session_keys', JSON.stringify(b64Keys));

        const reply = buildControlPacket(0x02); // CMD_KEYS_GENERATED
        const enc   = encryptControl(reply, currentTempSecret, currentTempIv);
        queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(enc), 'Auth: KEYS_GENERATED');
        handshakeCallbacks?.onKeysGenerated();

      } else if (cmd >= 0x10 && cmd <= 0x1F) {
        // CMD_SELECT_KEY (BccuConnectionService L929-949)
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
          reconnectAttempts = 0; // Success — reset retry counter
          reconnectEnabled = false; // No more auto-reconnect needed
          console.log('[BleManager] >>> AUTHENTICATED <<< Session key locked in.');

          const reply = buildControlPacket(0x10 | keyIndex);
          const enc   = encryptControl(reply, currentTempSecret, currentTempIv);
          queuedWrite(device, KTM_UUIDS.MAIN_SERVICE, KTM_UUIDS.AUTH_REP, bytesToBase64(enc), 'Auth: KEY_ACK');

          handshakeCallbacks?.onAuthenticated();

          // ── Activation: unlock display + send greeting ──
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

// ─── Live Navigation Pipe ─────────────────────────────────────────────────────
/**
 * Routes real-time Google Maps data into the KTM dashboard.
 * Silently discards if the crypto handshake is not yet complete.
 */
export const streamLiveNavigation = async (distance: string, road: string): Promise<void> => {
  if (!activeSessionKey || !connectedDevice) {
    // Not authenticated yet — drop silently
    return;
  }

  // 1. Write distance to TURN_DISTANCE (0705)
  if (distance.trim()) {
    const distPayload = buildTurnDistancePayload(distance);
    queuedWrite(
      connectedDevice,
      KTM_UUIDS.MAIN_SERVICE,
      KTM_UUIDS.TURN_DISTANCE,
      bytesToBase64(distPayload),
      'Nav: distance',
    );
  }

  // 2. Write road name to TURN_ROAD (0707)
  if (road.trim()) {
    const roadPayload = buildTurnRoadPayload(road);
    queuedWrite(
      connectedDevice,
      KTM_UUIDS.MAIN_SERVICE,
      KTM_UUIDS.TURN_ROAD,
      bytesToBase64(roadPayload),
      'Nav: road',
    );
  }
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
