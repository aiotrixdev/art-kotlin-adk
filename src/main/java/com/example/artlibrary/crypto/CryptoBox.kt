package com.example.artlibrary.crypto

import android.util.Base64
import com.example.artlibrary.types.KeyPairType
import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays

/**
 * Thin wrapper around libsodium's `crypto_box` (Curve25519/XSalsa20/Poly1305).
 *
 * The wire format is `Base64(nonce || ciphertext)`. All public keys and
 * private keys are passed in/out as Base64 strings.
 *
 * Notes for callers:
 *  - The implementation reuses a single [SecureRandom] instance — creating a
 *    new one per call can block on entropy on older Android devices.
 *  - Private key byte buffers are zero-filled after each operation. Avoid
 *    holding decoded private keys in long-lived `String` instances.
 */
object CryptoBox {

    init {
        NaCl.sodium() // initialize native libsodium once
    }

    private val PUBLIC_KEY_BYTES = Sodium.crypto_box_publickeybytes()
    private val SECRET_KEY_BYTES = Sodium.crypto_box_secretkeybytes()
    private val NONCE_BYTES = Sodium.crypto_box_noncebytes()
    private val MAC_BYTES = Sodium.crypto_box_macbytes()

    private val secureRandom: SecureRandom = SecureRandom()

    /**
     * Generates a new Curve25519 key pair for use with `crypto_box`.
     * Both keys are returned Base64-encoded.
     */
    fun generateKeyPair(): KeyPairType {
        val publicKey = ByteArray(PUBLIC_KEY_BYTES)
        val privateKey = ByteArray(SECRET_KEY_BYTES)
        try {
            Sodium.crypto_box_keypair(publicKey, privateKey)
            return KeyPairType(
                publicKey = encodeBase64(publicKey),
                privateKey = encodeBase64(privateKey)
            )
        } finally {
            Arrays.fill(privateKey, 0)
        }
    }

    /**
     * Encrypts [message] for the recipient identified by [publicKey] using
     * the sender's [privateKey].
     *
     * @return Base64 string containing `nonce || ciphertext`.
     * @throws IllegalArgumentException if either key has the wrong length.
     * @throws IllegalStateException if libsodium reports an error.
     */
    fun encrypt(
        message: String,
        publicKey: String,
        privateKey: String
    ): String {
        val pub = decodeBase64(publicKey)
        val priv = decodeBase64(privateKey)
        try {
            require(pub.size == PUBLIC_KEY_BYTES) { "Invalid public key length" }
            require(priv.size == SECRET_KEY_BYTES) { "Invalid private key length" }

            val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
            val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
            val cipher = ByteArray(msgBytes.size + MAC_BYTES)

            val rc = Sodium.crypto_box_easy(cipher, msgBytes, msgBytes.size, nonce, pub, priv)
            check(rc == 0) { "crypto_box_easy failed (rc=$rc)" }

            val full = ByteArray(nonce.size + cipher.size)
            System.arraycopy(nonce, 0, full, 0, nonce.size)
            System.arraycopy(cipher, 0, full, nonce.size, cipher.size)
            return Base64.encodeToString(full, Base64.NO_WRAP)
        } finally {
            Arrays.fill(priv, 0)
        }
    }

    /**
     * Decrypts a message previously produced by [encrypt].
     *
     * @throws IllegalArgumentException if [encryptedData] is malformed.
     * @throws IllegalStateException if authentication fails.
     */
    fun decrypt(
        encryptedData: String,
        publicKey: String,
        privateKey: String
    ): String {
        val full = decodeBase64(encryptedData)
        require(full.size > NONCE_BYTES + MAC_BYTES) { "Encrypted payload too short" }

        val nonce = full.copyOfRange(0, NONCE_BYTES)
        val cipher = full.copyOfRange(NONCE_BYTES, full.size)

        val pub = decodeBase64(publicKey)
        val priv = decodeBase64(privateKey)
        try {
            require(pub.size == PUBLIC_KEY_BYTES) { "Invalid public key length" }
            require(priv.size == SECRET_KEY_BYTES) { "Invalid private key length" }

            val plain = ByteArray(cipher.size - MAC_BYTES)
            val rc = Sodium.crypto_box_open_easy(plain, cipher, cipher.size, nonce, pub, priv)
            check(rc == 0) { "Decryption failed: authentication check failed" }
            return String(plain, StandardCharsets.UTF_8)
        } finally {
            Arrays.fill(priv, 0)
        }
    }

    private fun encodeBase64(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)

    private fun decodeBase64(data: String): ByteArray =
        Base64.decode(data, Base64.NO_WRAP)
}
