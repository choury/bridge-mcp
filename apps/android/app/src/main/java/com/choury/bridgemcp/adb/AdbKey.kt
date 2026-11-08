package com.choury.bridgemcp.adb

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher

class AdbKey(
    val privateKey: PrivateKey,
    val publicKeyBytes: ByteArray
) {
    // Returns the public key payload with a null terminator for ADB_AUTH_RSAPUBLICKEY
    val publicKeyPayload: ByteArray by lazy {
        val bytes = publicKeyBytes.copyOf(publicKeyBytes.size + 1)
        bytes[publicKeyBytes.size] = 0
        bytes
    }

    /**
     * Signs the 20-byte ADB auth challenge token according to PKCS#1 v1.5 for 2048-bit RSA.
     * Total block length is 256 bytes (2048 bits):
     * 0x00, 0x01, (218 bytes of 0xFF), 0x00, (15 bytes SHA-1 DigestInfo), (20 bytes token)
     */
    fun sign(token: ByteArray): ByteArray {
        val block = ByteArray(256)
        block[0] = 0x00
        block[1] = 0x01
        for (i in 2 until 220) {
            block[i] = 0xff.toByte()
        }
        block[220] = 0x00
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        System.arraycopy(digestInfo, 0, block, 221, digestInfo.size)
        System.arraycopy(token, 0, block, 236, token.size.coerceAtMost(20))

        return try {
            val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, privateKey)
            cipher.doFinal(block)
        } catch (e: Exception) {
            // Fallback for providers that disallow ENCRYPT_MODE with PrivateKey
            signWithModPow(block, privateKey)
        }
    }

    private fun signWithModPow(block: ByteArray, key: PrivateKey): ByteArray {
        val rsa = key as RSAPrivateKey
        val m = BigInteger(1, block)
        val sigInt = m.modPow(rsa.privateExponent, rsa.modulus)
        val sigBytes = sigInt.toByteArray()
        return when {
            sigBytes.size == 256 -> sigBytes
            sigBytes.size > 256 -> sigBytes.copyOfRange(sigBytes.size - 256, sigBytes.size)
            else -> {
                val padded = ByteArray(256)
                System.arraycopy(sigBytes, 0, padded, 256 - sigBytes.size, sigBytes.size)
                padded
            }
        }
    }

    companion object {
        private const val KEY_LENGTH_BITS = 2048
        private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
        private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4

        fun loadOrGenerate(context: Context): AdbKey {
            return loadOrGenerate(context.filesDir)
        }

        fun loadOrGenerate(baseDir: File): AdbKey {
            val dotAndroid = File(baseDir, ".android").apply { mkdirs() }
            val privFile = File(dotAndroid, "adbkey")
            val pubFile = File(dotAndroid, "adbkey.pub")

            if (privFile.exists() && privFile.length() > 0) {
                try {
                    val privKey = parsePkcs8(privFile.readText())
                    val pubBytes = if (pubFile.exists() && pubFile.length() > 0) {
                        pubFile.readBytes()
                    } else {
                        val genPub = generatePublicKeyFromPrivate(privKey)
                        pubFile.writeBytes(genPub)
                        genPub
                    }
                    return AdbKey(privKey, pubBytes)
                } catch (_: Exception) {
                    // If corrupted, re-generate below
                }
            }

            return generate(privFile, pubFile)
        }

        private fun generate(privFile: File, pubFile: File): AdbKey {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(KEY_LENGTH_BITS)
            val keyPair = kpg.generateKeyPair()

            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            val privPem = buildString {
                append("-----BEGIN PRIVATE KEY-----\n")
                append(base64.encodeToString(keyPair.private.encoded))
                append("\n-----END PRIVATE KEY-----\n")
            }
            privFile.writeText(privPem)

            val adbPubBytes = convertRsaPublicKeyToAdbFormat(keyPair.public as RSAPublicKey)
            val pubB64 = Base64.getEncoder().encodeToString(adbPubBytes)
            val pubLine = "$pubB64 bridge-mcp@android\n"
            pubFile.writeText(pubLine)

            return AdbKey(keyPair.private, pubLine.toByteArray(Charsets.UTF_8))
        }

        private fun parsePkcs8(pem: String): PrivateKey {
            val cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            val decoded = Base64.getDecoder().decode(cleaned)
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePrivate(PKCS8EncodedKeySpec(decoded))
        }

        private fun generatePublicKeyFromPrivate(privKey: PrivateKey): ByteArray {
            val kf = KeyFactory.getInstance("RSA")
            val rsaPriv = privKey as? RSAPrivateCrtKey
                ?: throw IllegalArgumentException("PrivateKey must implement RSAPrivateCrtKey to derive public key")
            val pubSpec = RSAPublicKeySpec(rsaPriv.modulus, rsaPriv.publicExponent)
            val pubKey = kf.generatePublic(pubSpec) as RSAPublicKey
            val adbPubBytes = convertRsaPublicKeyToAdbFormat(pubKey)
            val pubB64 = Base64.getEncoder().encodeToString(adbPubBytes)
            return "$pubB64 bridge-mcp@android\n".toByteArray(Charsets.UTF_8)
        }

        /**
         * Converts RSAPublicKey to the 524-byte struct RSAPublicKey expected by adbd:
         * struct RSAPublicKey {
         *     int len;                  // KEY_LENGTH_WORDS (64)
         *     uint32_t n0inv;           // -1 / n[0] mod 2^32
         *     uint32_t n[64];           // modulus as little-endian array
         *     uint32_t rr[64];          // R^2 as little-endian array (R = 2^(64*32))
         *     int exponent;             // public exponent (e.g. 65537)
         * }
         */
        fun convertRsaPublicKeyToAdbFormat(pubkey: RSAPublicKey): ByteArray {
            val r32 = BigInteger.ZERO.setBit(32)
            var n = pubkey.modulus
            val r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32)
            var rr = r.modPow(BigInteger.valueOf(2), n)
            val rem = n.remainder(r32)
            val n0inv = rem.modInverse(r32)
            val myN = IntArray(KEY_LENGTH_WORDS)
            val myRr = IntArray(KEY_LENGTH_WORDS)
            for (i in 0 until KEY_LENGTH_WORDS) {
                val resRr = rr.divideAndRemainder(r32)
                rr = resRr[0]
                myRr[i] = resRr[1].toInt()
                val resN = n.divideAndRemainder(r32)
                n = resN[0]
                myN[i] = resN[1].toInt()
            }

            val bbuf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            bbuf.putInt(KEY_LENGTH_WORDS)
            bbuf.putInt(n0inv.negate().toInt())
            for (i in myN) bbuf.putInt(i)
            for (i in myRr) bbuf.putInt(i)
            bbuf.putInt(pubkey.publicExponent.toInt())
            return bbuf.array()
        }
    }
}
