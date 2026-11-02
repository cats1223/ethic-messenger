package com.ethic.messenger

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ethic OS: signing in from the system Ethic ID account, without a password. On other phones none of this is present. */
object SystemAccount {
    private const val PACKAGE = "com.ethic.id"
    private const val TOKEN_TYPE = "messenger"
    // A fake app named com.ethic.id on an ordinary phone must not be trusted: it has to be a system app with our certificate.
    private const val CERT_SHA256 = "13ed7d11193493d1613c6a5afbcb3140b71856997faf842bf74fd4dfe3d32354"

    private fun trusted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return runCatching {
            val info = ctx.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            val system = info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
            val signers = info.signingInfo?.apkContentsSigners.orEmpty()
            system && signers.size == 1 && MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
                .joinToString("") { "%02x".format(it) } == CERT_SHA256
        }.getOrDefault(false)
    }

    private fun account(ctx: Context): Account? =
        if (trusted(ctx)) AccountManager.get(ctx).getAccountsByType(PACKAGE).firstOrNull() else null

    /** The username of the system account, when there is one to sign in with. */
    fun username(ctx: Context): String? = account(ctx)?.name

    /** Asks Ethic ID for this app's session. Null when there is no account, no network or it refused. */
    suspend fun token(ctx: Context): String? = withContext(Dispatchers.IO) {
        val acc = account(ctx) ?: return@withContext null
        runCatching {
            AccountManager.get(ctx).getAuthToken(acc, TOKEN_TYPE, null, false, null, null).result.getString(AccountManager.KEY_AUTHTOKEN)
        }.getOrNull()
    }
}
