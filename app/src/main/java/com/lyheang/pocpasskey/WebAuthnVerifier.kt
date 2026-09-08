package com.lyheang.pocpasskey

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec

data class VerifiedRegistration(
    val credentialId: String,
    val publicKeyX509: ByteArray,
    val signatureCount: Long
)

data class VerifiedAuthentication(val signatureCount: Long)

/** Minimal WebAuthn verifier for ES256 passkeys, intentionally written out for learning. */
object WebAuthnVerifier {
    private const val FLAG_USER_PRESENT = 0x01
    private const val FLAG_USER_VERIFIED = 0x04
    private const val FLAG_ATTESTED_CREDENTIAL_DATA = 0x40

    fun verifyRegistration(
        credentialJson: String,
        expectedChallenge: ByteArray,
        expectedRpId: String,
        expectedOrigin: String
    ): VerifiedRegistration {
        val credential = JSONObject(credentialJson)
        require(credential.getString("type") == "public-key") { "Unexpected credential type." }
        val response = credential.getJSONObject("response")
        val clientDataBytes = Base64Url.decode(response.getString("clientDataJSON"))
        verifyClientData(clientDataBytes, "webauthn.create", expectedChallenge, expectedOrigin)

        val attestationObject = Base64Url.decode(response.getString("attestationObject"))
        val decoded = CborDecoder(attestationObject).readValue() as? Map<*, *>
            ?: error("Invalid CBOR attestation object.")
        require(decoded["fmt"] == "none") {
            "This POC requested no attestation and does not accept another attestation format."
        }
        val authData = decoded["authData"] as? ByteArray
            ?: error("Attestation object has no authenticator data.")
        val parsed = parseRegistrationAuthenticatorData(authData, expectedRpId)

        val returnedCredentialId = Base64Url.decode(
            credential.optString("rawId", credential.getString("id"))
        )
        require(MessageDigest.isEqual(returnedCredentialId, parsed.credentialId)) {
            "Credential ID does not match authenticator data."
        }

        return VerifiedRegistration(
            credentialId = Base64Url.encode(parsed.credentialId),
            publicKeyX509 = coseEs256ToX509(parsed.cosePublicKey),
            signatureCount = parsed.signatureCount
        )
    }

    fun verifyAuthentication(
        credentialJson: String,
        expectedChallenge: ByteArray,
        expectedRpId: String,
        expectedOrigin: String,
        expectedUserId: ByteArray,
        publicKeyX509: ByteArray,
        previousSignatureCount: Long
    ): VerifiedAuthentication {
        val credential = JSONObject(credentialJson)
        require(credential.getString("type") == "public-key") { "Unexpected credential type." }
        val response = credential.getJSONObject("response")
        val clientDataBytes = Base64Url.decode(response.getString("clientDataJSON"))
        verifyClientData(clientDataBytes, "webauthn.get", expectedChallenge, expectedOrigin)

        if (!response.isNull("userHandle")) {
            val userHandle = response.optString("userHandle")
            if (userHandle.isNotEmpty()) {
                require(MessageDigest.isEqual(Base64Url.decode(userHandle), expectedUserId)) {
                    "Passkey user handle does not match the account."
                }
            }
        }

        val authenticatorData = Base64Url.decode(response.getString("authenticatorData"))
        val signatureBytes = Base64Url.decode(response.getString("signature"))
        val parsed = parseBasicAuthenticatorData(authenticatorData, expectedRpId)

        val signedBytes = ByteArrayOutputStream().apply {
            write(authenticatorData)
            write(sha256(clientDataBytes))
        }.toByteArray()
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyX509))
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(signedBytes)
            verify(signatureBytes)
        }
        require(valid) { "Passkey signature is invalid." }

        // A zero counter means this authenticator does not implement a signature counter.
        if (previousSignatureCount != 0L || parsed.signatureCount != 0L) {
            require(parsed.signatureCount > previousSignatureCount) {
                "Signature counter did not increase; the authenticator may be cloned."
            }
        }
        return VerifiedAuthentication(parsed.signatureCount)
    }

    private fun verifyClientData(
        clientDataBytes: ByteArray,
        expectedType: String,
        expectedChallenge: ByteArray,
        expectedOrigin: String
    ) {
        val clientData = JSONObject(clientDataBytes.toString(Charsets.UTF_8))
        require(clientData.getString("type") == expectedType) { "Wrong WebAuthn ceremony type." }
        require(
            MessageDigest.isEqual(
                Base64Url.decode(clientData.getString("challenge")),
                expectedChallenge
            )
        ) { "Challenge does not match or was replayed." }
        require(clientData.getString("origin") == expectedOrigin) {
            "Origin does not match this signed Android app."
        }
    }

    private fun parseRegistrationAuthenticatorData(
        authData: ByteArray,
        expectedRpId: String
    ): RegistrationAuthenticatorData {
        require(authData.size >= 55) { "Authenticator data is too short." }
        val buffer = ByteBuffer.wrap(authData).order(ByteOrder.BIG_ENDIAN)
        val rpIdHash = ByteArray(32).also(buffer::get)
        require(MessageDigest.isEqual(rpIdHash, sha256(expectedRpId.toByteArray()))) {
            "RP ID hash does not match."
        }
        val flags = buffer.get().toInt() and 0xff
        require(flags and FLAG_USER_PRESENT != 0) { "User presence flag is missing." }
        require(flags and FLAG_USER_VERIFIED != 0) { "User verification flag is missing." }
        require(flags and FLAG_ATTESTED_CREDENTIAL_DATA != 0) {
            "Attested credential data is missing."
        }
        val signatureCount = buffer.int.toLong() and 0xffff_ffffL
        buffer.position(buffer.position() + 16) // AAGUID
        val credentialIdLength = buffer.short.toInt() and 0xffff
        require(buffer.remaining() >= credentialIdLength) { "Credential ID is truncated." }
        val credentialId = ByteArray(credentialIdLength).also(buffer::get)
        val cosePublicKey = CborDecoder(authData, buffer.position()).readValue() as? Map<*, *>
            ?: error("Credential public key is not a CBOR map.")
        return RegistrationAuthenticatorData(credentialId, cosePublicKey, signatureCount)
    }

    private fun parseBasicAuthenticatorData(
        authData: ByteArray,
        expectedRpId: String
    ): BasicAuthenticatorData {
        require(authData.size >= 37) { "Authenticator data is too short." }
        val buffer = ByteBuffer.wrap(authData).order(ByteOrder.BIG_ENDIAN)
        val rpIdHash = ByteArray(32).also(buffer::get)
        require(MessageDigest.isEqual(rpIdHash, sha256(expectedRpId.toByteArray()))) {
            "RP ID hash does not match."
        }
        val flags = buffer.get().toInt() and 0xff
        require(flags and FLAG_USER_PRESENT != 0) { "User presence flag is missing." }
        require(flags and FLAG_USER_VERIFIED != 0) { "User verification flag is missing." }
        return BasicAuthenticatorData(buffer.int.toLong() and 0xffff_ffffL)
    }

    private fun coseEs256ToX509(cose: Map<*, *>): ByteArray {
        require((cose[1L] as Number).toInt() == 2) { "Only EC2 public keys are supported." }
        require((cose[3L] as Number).toInt() == -7) { "Only ES256 passkeys are supported." }
        require((cose[-1L] as Number).toInt() == 1) { "Only the P-256 curve is supported." }
        val x = cose[-2L] as? ByteArray ?: error("COSE key has no X coordinate.")
        val y = cose[-3L] as? ByteArray ?: error("COSE key has no Y coordinate.")

        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val keySpec = ECPublicKeySpec(
            ECPoint(BigInteger(1, x), BigInteger(1, y)),
            parameters
        )
        return KeyFactory.getInstance("EC").generatePublic(keySpec).encoded
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private data class RegistrationAuthenticatorData(
        val credentialId: ByteArray,
        val cosePublicKey: Map<*, *>,
        val signatureCount: Long
    )

    private data class BasicAuthenticatorData(val signatureCount: Long)
}

/** Small definite-length CBOR reader covering the structures used by WebAuthn/COSE. */
private class CborDecoder(
    private val bytes: ByteArray,
    startOffset: Int = 0
) {
    private var position = startOffset

    fun readValue(): Any? {
        require(position < bytes.size) { "Unexpected end of CBOR data." }
        val first = readByte()
        val majorType = first ushr 5
        val additional = first and 0x1f
        return when (majorType) {
            0 -> readLength(additional)
            1 -> -1L - readLength(additional)
            2 -> readBytes(readLengthAsInt(additional))
            3 -> readBytes(readLengthAsInt(additional)).toString(Charsets.UTF_8)
            4 -> List(readLengthAsInt(additional)) { readValue() }
            5 -> buildMap {
                repeat(readLengthAsInt(additional)) { put(readValue(), readValue()) }
            }
            7 -> when (additional) {
                20 -> false
                21 -> true
                22, 23 -> null
                else -> error("Unsupported CBOR simple value: $additional")
            }
            else -> error("Unsupported CBOR major type: $majorType")
        }
    }

    private fun readLengthAsInt(additional: Int): Int {
        val length = readLength(additional)
        require(length in 0..Int.MAX_VALUE) { "CBOR item is too large." }
        return length.toInt()
    }

    private fun readLength(additional: Int): Long = when (additional) {
        in 0..23 -> additional.toLong()
        24 -> readByte().toLong()
        25 -> ((readByte() shl 8) or readByte()).toLong()
        26 -> (0 until 4).fold(0L) { value, _ -> (value shl 8) or readByte().toLong() }
        27 -> (0 until 8).fold(0L) { value, _ -> (value shl 8) or readByte().toLong() }
        else -> error("Indefinite-length CBOR is not supported.")
    }

    private fun readBytes(length: Int): ByteArray {
        require(position + length <= bytes.size) { "Truncated CBOR data." }
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    private fun readByte(): Int = bytes[position++].toInt() and 0xff
}
