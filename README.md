# POC with Passkey Android

A small Android project demonstrating passkey sign-up and sign-in with Android Credential Manager.

The project has no separate API server. `FakePasskeyBackend` runs inside the app and simulates the important WebAuthn backend responsibilities: generating challenges, storing public keys, and verifying authentication signatures.

> This is a learning POC. It is not production-secure because the backend and its storage are inside the APK.

## What is implemented

- Discoverable passkey registration and authentication.
- Random, expiring WebAuthn challenges.
- RP ID, Android origin and authenticator-flag validation.
- ES256/P-256 public-key extraction and signature verification.
- Local account persistence with `SharedPreferences`.

The credential provider, such as Google Password Manager or Samsung Pass, owns the private key. The app never receives or stores it.

## Requirements

- Android Studio and JDK 17.
- Android SDK 36.
- Android 9/API 28 or newer.
- A configured device screen lock.
- A passkey-capable credential provider.
- An HTTPS domain or subdomain you control.

## One-time RP configuration

The placeholder below allows the project to build but cannot create a real passkey:

```properties
PASSKEY_RP_ID=passkey-poc.example.com
```

### 1. Set your RP ID

In `gradle.properties`, replace it with a hostname you control:

```properties
PASSKEY_RP_ID=passkeys.your-domain.com
```

Use only the hostname—no `https://`, path, or trailing slash. After this change, you can use the normal Android Studio **Run** action; no custom command is required.

### 2. Get the debug signing fingerprint

Windows:

```powershell
.\gradlew.bat signingReport
```

macOS/Linux:

```bash
./gradlew signingReport
```

Copy the `SHA-256` value for the `debug` variant.

### 3. Publish Digital Asset Links

Put the fingerprint in `assetlinks/assetlinks.json`:

```json
[
  {
    "relation": [
      "delegate_permission/common.handle_all_urls",
      "delegate_permission/common.get_login_creds"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "com.lyheang.pocpasskey",
      "sha256_cert_fingerprints": [
        "YOUR:DEBUG:SHA256:FINGERPRINT"
      ]
    }
  }
]
```

Publish it on the same host as the RP ID:

```text
https://passkeys.your-domain.com/.well-known/assetlinks.json
```

The URL must return HTTP `200`, `Content-Type: application/json`, and no redirect. For release builds, also add the release or Google Play App Signing certificate fingerprint.

## Run

1. Open the project root in Android Studio.
2. Wait for Gradle sync.
3. Run `app` on a compatible device.
4. Enter a display name and email, then create a passkey.
5. Sign out and use **Sign in** to authenticate with it.

## Project structure

| File | Responsibility |
| --- | --- |
| `PasskeyViewModel.kt` | Coordinates registration and authentication. |
| `PasskeyClient.kt` | Calls Android Credential Manager. |
| `FakePasskeyBackend.kt` | Generates options/challenges and stores accounts. |
| `WebAuthnVerifier.kt` | Parses WebAuthn data and verifies responses. |
| `AppSigningOrigin.kt` | Calculates the Android origin from the APK signing certificate. |
| `MainActivity.kt` | Provides the small Compose demonstration UI. |

## Registration flow

```text
FakePasskeyBackend.startRegistration()
    Generate user ID, challenge and registration options
                         ↓
PasskeyClient.createPasskey()
    Credential provider creates private/public key pair
                         ↓
FakePasskeyBackend.finishRegistration()
    Verify response and store credential ID + public key
```

## Authentication flow

```text
FakePasskeyBackend.startAuthentication()
    Generate challenge and authentication options
                         ↓
PasskeyClient.getPasskey()
    Credential provider signs with the private key
                         ↓
FakePasskeyBackend.finishAuthentication()
    Find account and verify signature with stored public key
```

The authentication signature covers:

```text
authenticatorData || SHA-256(clientDataJSON)
```

An empty `allowCredentials` list requests discoverable passkeys for the RP ID, so the user does not enter an email before sign-in.

## Local backend data

`SharedPreferences` stores:

```text
userId, email, displayName, credentialId, publicKeyX509, signatureCount
```

Pending challenges remain in memory and expire after two minutes. Clearing the local backend removes its accounts and public keys but does not delete passkeys from the credential provider.

## Limitations

- Supports ES256 (`alg = -7`) on P-256 only.
- Accepts `attestation: none` only.
- Supports one pending registration and authentication ceremony.
- Keeps the signed-in session in memory with no access/refresh token.
- Uninstalling the app removes backend records but may leave synced passkeys.

## Production migration

Keep `PasskeyClient` on Android and replace `FakePasskeyBackend` with HTTPS endpoints:

```text
POST /passkeys/register/options
POST /passkeys/register/verify
POST /passkeys/authenticate/options
POST /passkeys/authenticate/verify
```

The production server must own challenges, accepted origins, public keys, signature counters, sessions, replay protection, rate limits and account recovery. Use a maintained server-side WebAuthn library instead of this POC's custom CBOR parser.

## References

- [Create a passkey on Android](https://developer.android.com/identity/passkeys/create-passkeys)
- [Sign in with a passkey on Android](https://developer.android.com/identity/passkeys/sign-in-with-passkeys)
- [Credential Manager prerequisites](https://developer.android.com/identity/credential-manager/prerequisites)
- [Server-side passkey registration](https://developers.google.com/identity/passkeys/developer-guides/server-registration)
- [Server-side passkey authentication](https://developers.google.com/identity/passkeys/developer-guides/server-authentication)
