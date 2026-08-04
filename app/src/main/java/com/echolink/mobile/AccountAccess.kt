package com.echolink.mobile

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class AccountApiRequest(
    val url: String,
    val body: JsonObject,
)

internal data class AccountSession(
    val accountId: String,
    val email: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
)

internal data class AccountEntitlement(
    val accountId: String,
    val email: String,
    val active: Boolean,
    val status: String,
    val checkedAt: String,
    val nextCheckSeconds: Int,
)

internal object AccountApiContract {
    private val json = Json { ignoreUnknownKeys = true }
    private val accountIdPattern = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    fun loginRequest(serviceOrigin: String, email: String, password: String): AccountApiRequest {
        require(password.isNotEmpty() && password.length <= 128) { "Password is invalid." }
        return AccountApiRequest(
            url = endpoint(serviceOrigin, "/api/auth/login"),
            body = buildJsonObject {
                put("email", normalizeEmail(email))
                put("password", password)
                put("platform", "android")
            },
        )
    }

    fun registerRequest(serviceOrigin: String, email: String, password: String): AccountApiRequest {
        require(password.length in 15..128) { "Password must be 15 to 128 characters." }
        return AccountApiRequest(
            url = endpoint(serviceOrigin, "/api/auth/register"),
            body = buildJsonObject {
                put("email", normalizeEmail(email))
                put("password", password)
                put("platform", "android")
            },
        )
    }

    fun refreshRequest(serviceOrigin: String, refreshToken: String): AccountApiRequest {
        require(refreshToken.length in 16..8192 && refreshToken.none(Char::isWhitespace)) {
            "Invalid refresh token."
        }
        return AccountApiRequest(
            url = endpoint(serviceOrigin, "/api/auth/refresh"),
            body = buildJsonObject {
                put("refreshToken", refreshToken)
                put("platform", "android")
            },
        )
    }
    fun entitlementUrl(serviceOrigin: String): String =
        endpoint(serviceOrigin, "/api/account/entitlement")

    fun recoveryUrl(serviceOrigin: String): String =
        endpoint(serviceOrigin, "/forgot-password")

    fun deletionUrl(serviceOrigin: String): String =
        endpoint(serviceOrigin, "/delete-account")

    fun parseLoginResponse(payload: String): AccountSession {
        val root = json.parseToJsonElement(payload).jsonObject
        require(root["platform"]?.jsonPrimitive?.content == "android") {
            "Wrong account platform."
        }
        val account = requireNotNull(root["account"]).jsonObject
        val accountId = requireAccountId(account["id"]?.jsonPrimitive?.content.orEmpty())
        val email = normalizeEmail(account["email"]?.jsonPrimitive?.content.orEmpty())
        val accessToken = requireToken(root["accessToken"]?.jsonPrimitive?.content.orEmpty())
        val refreshToken = requireToken(root["refreshToken"]?.jsonPrimitive?.content.orEmpty())
        val expiresIn = root["expiresIn"]?.jsonPrimitive?.int ?: 0
        require(expiresIn in 60..86_400) { "Invalid account session lifetime." }
        return AccountSession(
            accountId = accountId,
            email = email,
            displayName = account["displayName"]?.jsonPrimitive?.content?.trim()
                ?.takeIf(String::isNotEmpty)?.take(120) ?: email,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
        )
    }

    fun parseEntitlementResponse(payload: String, expectedAccountId: String): AccountEntitlement {
        val root = json.parseToJsonElement(payload).jsonObject
        require(root["platform"]?.jsonPrimitive?.content == "android") {
            "Wrong entitlement platform."
        }
        val account = requireNotNull(root["account"]).jsonObject
        val accountId = requireAccountId(account["id"]?.jsonPrimitive?.content.orEmpty())
        require(accountId == requireAccountId(expectedAccountId)) { "Entitlement account mismatch." }
        val entitlement = requireNotNull(root["entitlement"]).jsonObject
        val status = entitlement["status"]?.jsonPrimitive?.content.orEmpty()
        require(status.length in 1..40) { "Invalid entitlement status." }
        val checkedAt = entitlement["checkedAt"]?.jsonPrimitive?.content.orEmpty()
        require(checkedAt.length in 20..40) { "Invalid entitlement timestamp." }
        val nextCheck = entitlement["nextCheckSeconds"]?.jsonPrimitive?.int ?: 0
        require(nextCheck in 60..86_400) { "Invalid entitlement refresh interval." }
        return AccountEntitlement(
            accountId = accountId,
            email = normalizeEmail(account["email"]?.jsonPrimitive?.content.orEmpty()),
            active = entitlement["active"]?.jsonPrimitive?.boolean ?: false,
            status = status,
            checkedAt = checkedAt,
            nextCheckSeconds = nextCheck,
        )
    }

    private fun endpoint(serviceOrigin: String, path: String): String {
        val origin = URI(serviceOrigin)
        require(
            origin.scheme == "https" &&
                !origin.host.isNullOrBlank() &&
                origin.userInfo == null &&
                (origin.path.isNullOrEmpty() || origin.path == "/") &&
                origin.query == null &&
                origin.fragment == null
        ) { "Account service must be a bare HTTPS origin." }
        return serviceOrigin.trimEnd('/') + path
    }

    private fun requireAccountId(value: String): String {
        val normalized = value.trim().lowercase()
        require(accountIdPattern.matches(normalized)) { "Invalid account ID." }
        return normalized
    }

    private fun requireToken(value: String): String {
        require(value.length in 16..8192 && value.none(Char::isWhitespace)) {
            "Invalid account token."
        }
        return value
    }

    private fun normalizeEmail(value: String): String {
        val normalized = value.trim().lowercase()
        require(
            normalized.length <= 254 &&
                Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(normalized)
        ) { "A valid email is required." }
        return normalized
    }
}

internal object SubscriptionAccessPolicy {
    fun allowsAccess(
        entitlement: AccountEntitlement?,
        enforcementEnabled: Boolean,
    ): Boolean = !enforcementEnabled || entitlement?.active == true
}