package com.echolink.mobile

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AccountHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

internal data class AccountHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface AccountTransport {
    fun execute(request: AccountHttpRequest): AccountHttpResponse
}

internal data class AccountVerification(
    val session: AccountSession,
    val entitlement: AccountEntitlement,
)

internal class AccountServiceException(
    val statusCode: Int,
) : IllegalStateException("Account service rejected the request.")
internal sealed interface AccountRegistrationResult {
    data class Active(val session: AccountSession) : AccountRegistrationResult
    data class VerificationRequired(val email: String) : AccountRegistrationResult
}

internal class AccountApiClient(
    private val serviceOrigin: String,
    private val transport: AccountTransport = HttpUrlConnectionAccountTransport(),
) {
    fun signIn(email: String, password: String): AccountSession {
        val request = AccountApiContract.loginRequest(serviceOrigin, email, password)
        val response = transport.execute(
            AccountHttpRequest(
                method = "POST",
                url = request.url,
                headers = jsonHeaders(),
                body = request.body.toString(),
            ),
        )
        requireSuccess(response)
        return AccountApiContract.parseLoginResponse(response.body)
    }

    fun register(email: String, password: String): AccountRegistrationResult {
        val request = AccountApiContract.registerRequest(serviceOrigin, email, password)
        val response = transport.execute(
            AccountHttpRequest(
                method = "POST",
                url = request.url,
                headers = jsonHeaders(),
                body = request.body.toString(),
            ),
        )
        if (response.statusCode == HttpURLConnection.HTTP_ACCEPTED) {
            val root = Json.parseToJsonElement(response.body).jsonObject
            require(root["status"]?.jsonPrimitive?.content == "verification_required") {
                "Invalid registration response."
            }
            val account = requireNotNull(root["account"]).jsonObject
            return AccountRegistrationResult.VerificationRequired(
                email = account["email"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
        require(response.statusCode == HttpURLConnection.HTTP_CREATED) {
            "Account could not be created."
        }
        return AccountRegistrationResult.Active(
            AccountApiContract.parseLoginResponse(response.body),
        )
    }

    fun refresh(refreshToken: String): AccountSession {
        val request = AccountApiContract.refreshRequest(serviceOrigin, refreshToken)
        val response = transport.execute(
            AccountHttpRequest(
                method = "POST",
                url = request.url,
                headers = jsonHeaders(),
                body = request.body.toString(),
            ),
        )
        requireSuccess(response)
        return AccountApiContract.parseLoginResponse(response.body)
    }

    fun verifyEntitlement(session: AccountSession): AccountVerification {
        return try {
            AccountVerification(session, entitlement(session))
        } catch (error: AccountServiceException) {
            if (error.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED) throw error
            val rotated = refresh(session.refreshToken)
            AccountVerification(rotated, entitlement(rotated))
        }
    }
    fun entitlement(session: AccountSession): AccountEntitlement {
        val response = transport.execute(
            AccountHttpRequest(
                method = "GET",
                url = AccountApiContract.entitlementUrl(serviceOrigin),
                headers = mapOf(
                    "Accept" to "application/json",
                    "Authorization" to "Bearer ${session.accessToken}",
                    "X-Manuscript-Platform" to "android",
                ),
            ),
        )
        requireSuccess(response)
        return AccountApiContract.parseEntitlementResponse(
            response.body,
            expectedAccountId = session.accountId,
        )
    }

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "X-Manuscript-Platform" to "android",
    )

    private fun requireSuccess(response: AccountHttpResponse) {
        if (response.statusCode !in 200..299) {
            throw AccountServiceException(response.statusCode)
        }
    }
}

internal class HttpUrlConnectionAccountTransport : AccountTransport {
    override fun execute(request: AccountHttpRequest): AccountHttpResponse {
        val url = URL(request.url)
        require(url.protocol == "https") { "Account transport requires HTTPS." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            request.headers.forEach(::setRequestProperty)
        }
        try {
            request.body?.let { body ->
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES) { "Account request is too large." }
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            require(status !in 300..399) { "Account service redirect was rejected." }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = if (stream == null) "" else stream.use(::readBounded)
            return AccountHttpResponse(statusCode = status, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_RESPONSE_BYTES) { "Account response is too large." }
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_REQUEST_BYTES = 8_192
        const val MAX_RESPONSE_BYTES = 65_536
    }
}