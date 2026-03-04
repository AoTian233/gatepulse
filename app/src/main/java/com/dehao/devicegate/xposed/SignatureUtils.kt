package com.dehao.devicegate.xposed

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SignatureUtils {
    private const val HMAC_ALGO = "HmacSHA256"

    fun buildPayload(deviceToken: String, packageName: String, allowed: Boolean, expiresAtMs: Long): String {
        val allowedBit = if (allowed) "1" else "0"
        return "$deviceToken|$packageName|$allowedBit|$expiresAtMs"
    }

    fun signHex(payload: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_ALGO)
        val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGO)
        mac.init(keySpec)
        val digest = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun equalsConstantTime(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(StandardCharsets.UTF_8)
        val bBytes = b.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}
