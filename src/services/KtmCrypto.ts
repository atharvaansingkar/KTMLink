import 'react-native-get-random-values';
import aesjs from 'aes-js';
import { sha512 } from 'js-sha512';

declare const crypto: any;
export const computeTempIvAndSecret = (m1: Uint8Array, m2: Uint8Array) => {
  if (m1.length !== 16 || m2.length !== 16) {
    throw new Error('Nonces must be 16 bytes');
  }
  const tempIv = new Uint8Array(16);
  tempIv.set(m1.slice(8, 16), 0);
  tempIv.set(m2.slice(0, 8), 8);
  const tempSecret = new Uint8Array(16);
  tempSecret.set(m2.slice(8, 16), 0);
  tempSecret.set(m1.slice(0, 8), 8);
  return { tempIv, tempSecret };
};

export const getRandomBytes = (length: number): Uint8Array => {
  const arr = new Uint8Array(length);
  crypto.getRandomValues(arr);
  return arr;
};

/**
 * Builds a 16-byte control packet matching the KTM protocol spec:
 *   [0..15] = random noise
 *   [2]     = 0xFF  (control marker)
 *   [4]     = cmd   (command integer)
 *   [6]     = 0x01  (session flag)
 */
export const buildControlPacket = (cmd: number): Uint8Array => {
  const packet = getRandomBytes(16);
  packet[2] = 0xFF;
  packet[4] = cmd & 0xFF;
  packet[6] = 0x01;
  return packet;
};

export const encryptControl = (payload16: Uint8Array, secret: Uint8Array, iv: Uint8Array): Uint8Array => {
  const aesCbc = new aesjs.ModeOfOperation.cbc(secret, iv);
  return aesCbc.encrypt(payload16);
};

export const decryptControl = (encryptedData: Uint8Array, secret: Uint8Array, iv: Uint8Array): Uint8Array => {
  const aesCbc = new aesjs.ModeOfOperation.cbc(secret, iv);
  return aesCbc.decrypt(encryptedData);
};

export const frameAndEncryptData = (data: Uint8Array, secret: Uint8Array, iv: Uint8Array): Uint8Array => {
  const padLen = 16 - (data.length % 16);
  const total = data.length + 16 + padLen;
  const framedData = new Uint8Array(total);

  const prefix = getRandomBytes(16);
  framedData.set(prefix, 0);
  framedData.set(data, 16);

  const fillStart = 16 + data.length;
  const fillLen = total - 1 - fillStart;
  if (fillLen > 0) {
    const fill = getRandomBytes(fillLen);
    framedData.set(fill, fillStart);
  }
  framedData[total - 1] = padLen;

  const aesCbc = new aesjs.ModeOfOperation.cbc(secret, iv);
  return aesCbc.encrypt(framedData);
};

export const decryptAndUnframeData = (encryptedData: Uint8Array, secret: Uint8Array, iv: Uint8Array): Uint8Array => {
  const aesCbc = new aesjs.ModeOfOperation.cbc(secret, iv);
  const decryptedBytes = aesCbc.decrypt(encryptedData);
  if (decryptedBytes.length < 17) throw new Error('Decrypted data too short');

  const withoutPrefix = decryptedBytes.slice(16);
  const padLen = withoutPrefix[withoutPrefix.length - 1];

  if (padLen > 16 || padLen < 1) {
    console.warn('[KtmCrypto] Invalid padding length:', padLen);
    return withoutPrefix;
  }
  return withoutPrefix.slice(0, withoutPrefix.length - padLen);
};

export const buildMirrored = (decryptedChallenge: Uint8Array): Uint8Array => {
  const tail = decryptedChallenge.slice(8, 16);
  const out = new Uint8Array(16);
  for (let i = 0; i < 8; i++) {
    out[i] = tail[i];
    out[15 - i] = tail[i];
  }
  return out;
};

export const deriveSessionKeys = (
  decryptedChallenge: Uint8Array,
  mirrored: Uint8Array,
  tempIv: Uint8Array,
  tempSecret: Uint8Array,
): Uint8Array[] => {
  const base = [decryptedChallenge, mirrored, tempIv, tempSecret];
  const keys: Uint8Array[] = [];

  for (let rot = 0; rot < 4; rot++) {
    const buf = new Uint8Array(64);
    for (let slot = 0; slot < 4; slot++) {
      const src = base[(slot + rot) % 4];
      buf.set(src, slot * 16);
    }
    const digest = new Uint8Array(sha512.array(buf));
    for (let chunk = 0; chunk < 4; chunk++) {
      keys.push(digest.slice(chunk * 16, chunk * 16 + 16));
    }
  }

  return keys;
};
