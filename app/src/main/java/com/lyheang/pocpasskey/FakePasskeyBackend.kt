package com.lyheang.pocpasskey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

data class AccountRecord(
    val userId: String,
    val email: String,
    val displayName: String,
    val credentialId: String,
    val publicKeyX509: String,
    val signatureCount: Long
)

data class CeremonyOptions(val requestJson: String)

/**
 * In-process teaching replacement for a WebAuthn backend.
 *
 * It creates challenges, verifies Credential Manager responses cryptographically, and stores
 * account + public-key records in SharedPreferences. A production backend must run on a trusted
 * remote server; keeping verification in the APK lets a modified APK bypass it.
 */
class FakePasskeyBackend(private val context: Context) {
    private val preferences = context.getSharedPreferences("fake_passkey_backend", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val rpId = BuildConfig.PASSKEY_RP_ID
    private val expectedOrigin = AppSigningOrigin.from(context)

    private var pendingRegistration: PendingRegistration? = null
    private var pendingAuthentication: PendingAuthentication? = null

    fun startRegistration(email: String, displayName: String): CeremonyOptions {
        require(email.isNotBlank() && '@' in email) { "Enter a valid email address." }
        require(displayName.isNotBlank()) { "Enter a display name." }
        require(loadAccounts().none { it.email.equals(email, ignoreCase = true) }) {
            "An account with this email already exists."
        }

        val userId = randomBytes(32)
        val challenge = randomBytes(32)
        pendingRegistration = PendingRegistration(
            userId = Base64Url.encode(userId),
            email = email,
            displayName = displayName,
            challenge = challenge,
            createdAtMillis = System.currentTimeMillis()
        )

        val json = JSONObject()
            .put("challenge", Base64Url.encode(challenge))
            .put(
                "rp",
                JSONObject()
                    .put("id", rpId)
                    .put("name", "Passkey Android POC")
            )
            .put(
                "user",
                JSONObject()
                    .put("id", Base64Url.encode(userId))
                    .put("name", email)
                    .put("displayName", displayName)
            )
            .put(
                "pubKeyCredParams",
                JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7))
            )
            .put("timeout", CEREMONY_TIMEOUT_MILLIS)
            .put(
                "authenticatorSelection",
                JSONObject()
                    .put("residentKey", "required")
                    .put("requireResidentKey", true)
                    .put("userVerification", "required")
            )
            .put("attestation", "none")

        return CeremonyOptions(json.toString())
    }

    fun finishRegistration(credentialJson: String): AccountRecord {
        val pending = pendingRegistration ?: error("No registration ceremony is active.")
        checkNotExpired(pending.createdAtMillis)

        val verified = WebAuthnVerifier.verifyRegistration(
            credentialJson = credentialJson,
            expectedChallenge = pending.challenge,
            expectedRpId = rpId,
            expectedOrigin = expectedOrigin
        )
        require(loadAccounts().none { it.credentialId == verified.credentialId }) {
            "This passkey is already registered."
        }

        val account = AccountRecord(
            userId = pending.userId,
            email = pending.email,
            displayName = pending.displayName,
            credentialId = verified.credentialId,
            publicKeyX509 = Base64Url.encode(verified.publicKeyX509),
            signatureCount = verified.signatureCount
        )
        saveAccounts(loadAccounts() + account)
        pendingRegistration = null
        return account
    }

    fun startAuthentication(): CeremonyOptions {
        require(loadAccounts().isNotEmpty()) { "Create an account before signing in." }
        val challenge = randomBytes(32)
        pendingAuthentication = PendingAuthentication(
            challenge = challenge,
            createdAtMillis = System.currentTimeMillis()
        )

        // An empty allowCredentials list requests discoverable passkeys for this RP.
        val json = JSONObject()
            .put("challenge", Base64Url.encode(challenge))
            .put("rpId", rpId)
            .put("allowCredentials", JSONArray())
            .put("userVerification", "required")
            .put("timeout", CEREMONY_TIMEOUT_MILLIS)

        return CeremonyOptions(json.toString())
    }

    fun finishAuthentication(credentialJson: String): AccountRecord {
        val pending = pendingAuthentication ?: error("No sign-in ceremony is active.")
        checkNotExpired(pending.createdAtMillis)

        val json = JSONObject(credentialJson)
        val credentialId = Base64Url.normalize(json.optString("rawId", json.getString("id")))
        val oldAccount = loadAccounts().firstOrNull { it.credentialId == credentialId }
            ?: error("The selected passkey is not registered in this offline backend.")

        val verified = WebAuthnVerifier.verifyAuthentication(
            credentialJson = credentialJson,
            expectedChallenge = pending.challenge,
            expectedRpId = rpId,
            expectedOrigin = expectedOrigin,
            expectedUserId = Base64Url.decode(oldAccount.userId),
            publicKeyX509 = Base64Url.decode(oldAccount.publicKeyX509),
            previousSignatureCount = oldAccount.signatureCount
        )

        val updatedAccount = oldAccount.copy(signatureCount = verified.signatureCount)
        saveAccounts(loadAccounts().map { if (it.credentialId == credentialId) updatedAccount else it })
        pendingAuthentication = null
        return updatedAccount
    }

    fun accountCount(): Int = loadAccounts().size

    fun clear() {
        preferences.edit().clear().apply()
        pendingRegistration = null
        pendingAuthentication = null
    }

    private fun loadAccounts(): List<AccountRecord> {
        val array = JSONArray(preferences.getString(KEY_ACCOUNTS, "[]"))
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    AccountRecord(
                        userId = item.getString("userId"),
                        email = item.getString("email"),
                        displayName = item.getString("displayName"),
                        credentialId = item.getString("credentialId"),
                        publicKeyX509 = item.getString("publicKeyX509"),
                        signatureCount = item.getLong("signatureCount")
                    )
                )
            }
        }
    }

    private fun saveAccounts(accounts: List<AccountRecord>) {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject()
                    .put("userId", account.userId)
                    .put("email", account.email)
                    .put("displayName", account.displayName)
                    .put("credentialId", account.credentialId)
                    .put("publicKeyX509", account.publicKeyX509)
                    .put("signatureCount", account.signatureCount)
            )
        }
        preferences.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    private fun checkNotExpired(createdAtMillis: Long) {
        require(System.currentTimeMillis() - createdAtMillis <= CEREMONY_TIMEOUT_MILLIS) {
            "The passkey challenge expired. Start again."
        }
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private data class PendingRegistration(
        val userId: String,
        val email: String,
        val displayName: String,
        val challenge: ByteArray,
        val createdAtMillis: Long
    )

    private data class PendingAuthentication(
        val challenge: ByteArray,
        val createdAtMillis: Long
    )

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val CEREMONY_TIMEOUT_MILLIS = 120_000L
    }
}

object Base64Url {
    fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(pad(value))

    fun normalize(value: String): String = encode(decode(value))

    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
}
