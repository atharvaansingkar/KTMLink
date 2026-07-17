package com.navigator.app.ble

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-exact reimplementation of the BCCU BLE protocol crypto, derived from
 * decompiled source of both the third-party "BikeConnect" app and the
 * official KTM Connect app (com.ktm.mobsdk.bccu.EncryptionUtilsKt / c8.k /
 * a8.q / a8.p / eb.b). See BCCU_BLE_PROTOCOL.md in the project root.
 */
object BccuCrypto {

    private val random = SecureRandom()

    // --- Raw AES-CBC/NoPadding, used directly by the data-plane frame()/unframe() wrapper. ---
    fun aes(input: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(input)
    }

    // --- Data-plane framing (EncryptionUtilsKt.frame/unframe, confirmed byte-exact) ---
    fun frame(data: ByteArray): ByteArray {
        val padLen = 16 - (data.size % 16)
        val total = data.size + 16 + padLen
        val out = ByteArray(total)
        val prefix = ByteArray(16)
        random.nextBytes(prefix)
        System.arraycopy(prefix, 0, out, 0, 16)
        System.arraycopy(data, 0, out, 16, data.size)
        val fillStart = 16 + data.size
        val fillLen = total - 1 - fillStart
        if (fillLen > 0) {
            val fill = ByteArray(fillLen)
            random.nextBytes(fill)
            System.arraycopy(fill, 0, out, fillStart, fillLen)
        }
        out[total - 1] = padLen.toByte()
        return out
    }

    fun unframe(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padLen = data[data.size - 1].toInt() and 0xFF
        val body = data.copyOfRange(16, data.size)
        val end = (body.size - padLen).coerceAtLeast(0)
        return body.copyOfRange(0, end)
    }

    /** Encrypt a data-plane payload (TURN_ICON, NOTIFICATION, etc.) with the active session key. */
    fun encryptData(payload: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes(frame(payload), key, iv, true)

    fun decryptData(message: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        unframe(aes(message, key, iv, false))

    // --- Handshake control messages: raw single-block AES, NO frame()/unframe() wrapper. ---
    fun encryptControl(payload16: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes(payload16, key, iv, true)

    fun decryptControl(message: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes(message, key, iv, false)

    /** Build a 16-byte handshake control message: byte[2]=0xFF marker, byte[4]=command, byte[6]=0x01 version. */
    fun buildControlMessage(command: Int): ByteArray {
        val msg = ByteArray(16)
        random.nextBytes(msg)
        msg[2] = 0xFF.toByte()
        msg[4] = command.toByte()
        msg[6] = 0x01
        return msg
    }

    /** Step 1 of the handshake: mix the bike's nonce m1 and our nonce m2 into a temporary IV + secret. */
    fun computeTempIvAndSecret(m1: ByteArray, m2: ByteArray): Pair<ByteArray, ByteArray> {
        val tempIv = ByteArray(16)
        val tempSecret = ByteArray(16)
        for (i in 0 until 8) {
            tempIv[i] = m1[8 + i]
            tempIv[8 + i] = m2[i]
            tempSecret[i] = m2[8 + i]
            tempSecret[8 + i] = m1[i]
        }
        return tempIv to tempSecret
    }

    /** Mirror bytes [8:16) of a decrypted challenge into a 16-byte palindrome, per the key-gen step. */
    fun buildMirrored(decryptedChallenge: ByteArray): ByteArray {
        val tail = decryptedChallenge.copyOfRange(8, 16)
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = tail[i]
            out[15 - i] = tail[i]
        }
        return out
    }

    /**
     * Derive the 16 rotating session keys. Inputs are (in this exact order):
     * decryptedChallenge, mirrored, tempIv, tempSecret.
     * Each of the 4 cyclic rotations of these 4 values is concatenated (64 bytes),
     * SHA-512'd, and split into 4x16-byte chunks -> 16 keys total, in order.
     */
    fun deriveSessionKeys(
        decryptedChallenge: ByteArray,
        mirrored: ByteArray,
        tempIv: ByteArray,
        tempSecret: ByteArray
    ): List<ByteArray> {
        val base = listOf(decryptedChallenge, mirrored, tempIv, tempSecret)
        val keys = mutableListOf<ByteArray>()
        val sha512 = MessageDigest.getInstance("SHA-512")
        for (rot in 0 until 4) {
            val buf = ByteArray(64)
            for (slot in 0 until 4) {
                val src = base[(slot + rot) % 4]
                System.arraycopy(src, 0, buf, slot * 16, 16)
            }
            val digest = sha512.digest(buf)
            for (chunk in 0 until 4) {
                keys.add(digest.copyOfRange(chunk * 16, chunk * 16 + 16))
            }
        }
        return keys
    }

    fun randomNonce(): ByteArray {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }
}
