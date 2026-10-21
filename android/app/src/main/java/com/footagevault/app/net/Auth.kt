package com.footagevault.app.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in for YouTube using the phone's Google account (Google Identity Services).
 * No client secret in the app: Google recognises it by its package name + signing key,
 * which are registered as an "Android" OAuth client in the user's own Google Cloud project.
 */
object Auth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/youtube")
    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    sealed interface Result {
        data class Token(val token: String) : Result
        data class NeedsUser(val intent: PendingIntent) : Result
        data class Error(val message: String) : Result
    }

    suspend fun authorize(ctx: Context): Result = try {
        val r: AuthorizationResult = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        val pi = r.pendingIntent
        when {
            r.hasResolution() && pi != null -> Result.NeedsUser(pi)
            r.accessToken != null -> Result.Token(r.accessToken!!)
            else -> Result.Error("No token")
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: e.toString())
    }

    /** For background work: a token only if no screen is needed. */
    suspend fun silentToken(ctx: Context): String? = (authorize(ctx) as? Result.Token)?.token

    fun tokenFromIntent(ctx: Context, data: Intent?): String? = try {
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data).accessToken
    } catch (e: Exception) { null }
}
