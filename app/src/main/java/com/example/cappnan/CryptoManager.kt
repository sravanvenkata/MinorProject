package com.example.cappnan

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    // 1. Generate the ECC Key Pair (secp256r1 is the industry standard)
    fun generateECCKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        keyPairGenerator.initialize(ecSpec)
        return keyPairGenerator.generateKeyPair()
    }

    // 2. Convert a Key to a String (To save in SharedPreferences or QR Code)
    fun encodeKeyToBase64(key: java.security.Key): String {
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    // 3. Convert a String back to a Public Key (When you scan a friend's QR)
    fun decodeBase64ToPublicKey(base64Key: String): PublicKey {
        val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        val spec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(spec)
    }

    // 4. Convert a String back to a Private Key (When loading your own key from storage)
    fun decodeBase64ToPrivateKey(base64Key: String): PrivateKey {
        val keyBytes = Base64.decode(base64Key, Base64.NO_WRAP)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(spec)
    }

    fun generateSharedSecret(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(myPrivateKey)
        keyAgreement.doPhase(theirPublicKey, true)

        // This generates a secure byte array that both phones will independently calculate
        return keyAgreement.generateSecret()
    }

    // 2. Lock the Message (AES-GCM)
    fun encryptMessage(plainText: String, sharedSecret: ByteArray): ByteArray {
        // AES-256 requires exactly a 32-byte key. Our ECDH shared secret is already 32 bytes.
        val secretKey = SecretKeySpec(sharedSecret, 0, 32, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // AES-GCM requires a random 12-byte Initialization Vector (IV) for every single message
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val spec = GCMParameterSpec(128, iv) // 128-bit authentication tag to prevent tampering

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val encryptedPayload = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // We attach the 12-byte IV to the front of the message so the receiver can unlock it.
        // Total Overhead: 12 bytes (IV) + 16 bytes (Auth Tag) = 28 bytes.
        return iv + encryptedPayload
    }

    // 3. Unlock the Message (AES-GCM)
    fun decryptMessage(encryptedData: ByteArray, sharedSecret: ByteArray): String {
        val secretKey = SecretKeySpec(sharedSecret, 0, 32, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        // Strip the 12-byte IV off the front
        val iv = encryptedData.copyOfRange(0, 12)
        val actualCiphertext = encryptedData.copyOfRange(12, encryptedData.size)
        val spec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        // Decrypt the rest
        val decryptedBytes = cipher.doFinal(actualCiphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}