package com.lyheang.pocpasskey

import android.app.Activity
import android.app.Application
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PasskeyUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val signedInAccount: AccountRecord? = null,
    val accountCount: Int = 0,
    val rpId: String = BuildConfig.PASSKEY_RP_ID,
    val packageName: String = BuildConfig.APPLICATION_ID,
    val expectedOrigin: String = "",
    val rpConfigured: Boolean = !BuildConfig.PASSKEY_RP_ID.endsWith("example.com")
)

class PasskeyViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = FakePasskeyBackend(application)
    private val passkeyClient = PasskeyClient()
    private val _state = MutableStateFlow(
        PasskeyUiState(
            accountCount = backend.accountCount(),
            expectedOrigin = AppSigningOrigin.from(application)
        )
    )
    val state: StateFlow<PasskeyUiState> = _state.asStateFlow()

    fun signUp(activity: Activity, email: String, displayName: String) {
        viewModelScope.launch {
            runOperation {
                val options = backend.startRegistration(email.trim(), displayName.trim())
                val credentialJson = passkeyClient.createPasskey(activity, options.requestJson)
                val account = backend.finishRegistration(credentialJson)
                _state.update {
                    it.copy(
                        signedInAccount = account,
                        accountCount = backend.accountCount(),
                        message = "Account and passkey created."
                    )
                }
            }
        }
    }

    fun signIn(activity: Activity) {
        viewModelScope.launch {
            runOperation {
                val options = backend.startAuthentication()
                val credentialJson = passkeyClient.getPasskey(activity, options.requestJson)
                val account = backend.finishAuthentication(credentialJson)
                _state.update {
                    it.copy(
                        signedInAccount = account,
                        message = "Passkey signature verified by the offline backend."
                    )
                }
            }
        }
    }

    fun signOut() {
        _state.update { it.copy(signedInAccount = null, message = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearFakeBackend() {
        backend.clear()
        _state.update {
            it.copy(
                accountCount = 0,
                signedInAccount = null,
                message = "Local account records cleared. The credential provider may still keep its passkeys."
            )
        }
    }

    private suspend fun runOperation(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, message = null) }
        try {
            block()
        } catch (_: CreateCredentialCancellationException) {
            _state.update { it.copy(message = "Passkey creation was cancelled.") }
        } catch (_: GetCredentialCancellationException) {
            _state.update { it.copy(message = "Passkey sign-in was cancelled.") }
        } catch (error: CreateCredentialException) {
            _state.update { it.copy(message = "Credential Manager could not create the passkey: ${error.message}") }
        } catch (error: GetCredentialException) {
            _state.update { it.copy(message = "Credential Manager could not get a passkey: ${error.message}") }
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: "Unexpected passkey error") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }
}
