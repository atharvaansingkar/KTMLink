package com.ktmlink

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-exact port of KtmCrypto.ts / BccuCrypto.kt for use in the native
 * foreground service (KTMLinkForegroundService). All operations confirmed
 * against BccuCrypto.kt in src/others/.
 */
object KtmNativeCrypto {

    private val random = SecureRandom()

    // ── Raw AES-CBC/NoPadding ────────────────────────────────────────────────
    private fun aes(input: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(input)
    }

    // ── Data-plane: frame → encrypt / decrypt → unframe ──────────────────────
    // Frame format: [16 random prefix][data][random fill][padLen as last byte]
    // padLen = 16 - (data.size % 16), minimum 1, maximum 16.
    private fun frame(data: ByteArray): ByteArray {
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

    private fun unframe(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padLen = data[data.size - 1].toInt() and 0xFF
        val body = data.copyOfRange(16, data.size)
        val end = (body.size - padLen).coerceAtLeast(0)
        return body.copyOfRange(0, end)
    }

    fun frameAndEncryptData(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes(frame(data), key, iv, true)

    fun decryptAndUnframeData(message: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        unframe(aes(message, key, iv, false))

    // ── Control-plane: raw AES, no framing ──────────────────────────────────
    // Used only for handshake AUTH_REQ / AUTH_REP packets.
    fun encryptControl(payload16: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes(payload16, key, iv, true)

    fun decryptControl(message: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        aes(message, key, iv, false)

    // ── Control packet builder ───────────────────────────────────────────────
    // 16 random bytes, then: [2]=0xFF marker, [4]=command, [6]=0x01 version.
    fun buildControlPacket(command: Int): ByteArray {
        val msg = ByteArray(16)
        random.nextBytes(msg)
        msg[2] = 0xFF.toByte()
        msg[4] = command.toByte()
        msg[6] = 0x01
        return msg
    }

    // ── Nonce derivation ─────────────────────────────────────────────────────
    // tempIv:     m1[8..15] + m2[0..7]
    // tempSecret: m2[8..15] + m1[0..7]
    fun computeTempIvAndSecret(m1: ByteArray, m2: ByteArray): Pair<ByteArray, ByteArray> {
        val tempIv = ByteArray(16)
        val tempSecret = ByteArray(16)
        for (i in 0 until 8) {
            tempIv[i]     = m1[8 + i]
            tempIv[8 + i] = m2[i]
            tempSecret[i]     = m2[8 + i]
            tempSecret[8 + i] = m1[i]
        }
        return tempIv to tempSecret
    }

    // ── Mirrored challenge ───────────────────────────────────────────────────
    // Copies bytes [8..15] of decryptedChallenge into a 16-byte palindrome.
    fun buildMirrored(decryptedChallenge: ByteArray): ByteArray {
        val tail = decryptedChallenge.copyOfRange(8, 16)
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i]      = tail[i]
            out[15 - i] = tail[i]
        }
        return out
    }

    // ── Session key derivation ───────────────────────────────────────────────
    // 4 cyclic rotations of [decryptedChallenge, mirrored, tempIv, tempSecret]
    // each SHA-512'd → 4×16-byte chunks → 16 keys total.
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
