package com.lyheang.pocpasskey

import android.app.Activity
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential

/** Thin adapter around Android Credential Manager. */
class PasskeyClient {
    suspend fun createPasskey(activity: Activity, registrationOptionsJson: String): String {
        val manager = CredentialManager.create(activity)
        val request = CreatePublicKeyCredentialRequest(
            requestJson = registrationOptionsJson,
            preferImmediatelyAvailableCredentials = false
        )

        val response = manager.createCredential(activity, request)
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: error("Credential provider returned an unexpected response type")
    }

    suspend fun getPasskey(activity: Activity, authenticationOptionsJson: String): String {
        val manager = CredentialManager.create(activity)
        val request = GetCredentialRequest(
            credentialOptions = listOf(
                GetPublicKeyCredentialOption(requestJson = authenticationOptionsJson)
            ),
            preferImmediatelyAvailableCredentials = false
        )

        val response = manager.getCredential(activity, request)
        return (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: error("Credential provider returned something other than a passkey")
    }
}
