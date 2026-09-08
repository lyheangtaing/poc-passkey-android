package com.lyheang.pocpasskey

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import java.util.Base64

/** Computes the native WebAuthn origin used by Android Credential Manager. */
object AppSigningOrigin {
    fun from(context: Context): String {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val certificate = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: error("No APK signing certificate found")
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())
        val base64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "android:apk-key-hash:$base64Url"
    }
}
