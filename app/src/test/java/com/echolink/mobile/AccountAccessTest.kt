package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator

class AccountAccessTest {
    @Test
    fun `builds Android password login for the shared website account`() {
        val request = AccountApiContract.loginRequest(
            serviceOrigin = "https://manuscript-writing.seventhree170.chatgpt.site",
            email = " Owner@Example.com ",
            password = "correct horse battery staple",
        )

        assertEquals(
            "https://manuscript-writing.seventhree170.chatgpt.site/api/auth/login",
            request.url,
        )
        assertEquals("owner@example.com", request.body["email"]?.toString()?.trim('"'))
        assertEquals("android", request.body["platform"]?.toString()?.trim('"'))
        assertEquals("correct horse battery staple", request.body["password"]?.toString()?.trim('"'))
    }

    @Test
    fun `rejects cleartext or path-based account service origins`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountApiContract.loginRequest("http://example.com", "a@example.com", "long enough password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountApiContract.loginRequest("https://example.com/account", "a@example.com", "long enough password")
        }
    }

    @Test
    fun `parses the shared account and active Android entitlement`() {
        val session = AccountApiContract.parseLoginResponse(
            """{
              "account":{"id":"26fb8e92-c81f-4d2c-a479-796f9a7ceab1","email":"owner@example.com","displayName":"Owner"},
              "accessToken":"native-access-token-value",
              "refreshToken":"native-refresh-token-value",
              "expiresIn":3600,
              "platform":"android"
            }""".trimIndent(),
        )
        val entitlement = AccountApiContract.parseEntitlementResponse(
            """{
              "account":{"id":"26fb8e92-c81f-4d2c-a479-796f9a7ceab1","email":"owner@example.com"},
              "entitlement":{"active":true,"status":"ACTIVE","checkedAt":"2026-07-28T12:00:00.000Z","nextCheckSeconds":900},
              "platform":"android"
            }""".trimIndent(),
            expectedAccountId = session.accountId,
        )

        assertEquals("26fb8e92-c81f-4d2c-a479-796f9a7ceab1", session.accountId)
        assertTrue(entitlement.active)
        assertEquals("ACTIVE", entitlement.status)
    }

    @Test
    fun `client sends the account token only to the fixed entitlement endpoint`() {
        val requests = mutableListOf<AccountHttpRequest>()
        val transport = AccountTransport { request ->
            requests += request
            AccountHttpResponse(
                statusCode = 200,
                body = """{
                  "account":{"id":"26fb8e92-c81f-4d2c-a479-796f9a7ceab1","email":"owner@example.com"},
                  "entitlement":{"active":true,"status":"ACTIVE","checkedAt":"2026-07-28T12:00:00.000Z","nextCheckSeconds":900},
                  "platform":"android"
                }""".trimIndent(),
            )
        }
        val session = AccountSession(
            accountId = "26fb8e92-c81f-4d2c-a479-796f9a7ceab1",
            email = "owner@example.com",
            displayName = "Owner",
            accessToken = "native-access-token-value",
            refreshToken = "native-refresh-token-value",
            expiresIn = 3600,
        )

        val result = AccountApiClient(
            "https://manuscript-writing.seventhree170.chatgpt.site",
            transport,
        ).entitlement(session)

        assertTrue(result.active)
        assertEquals(1, requests.size)
        assertEquals(
            "https://manuscript-writing.seventhree170.chatgpt.site/api/account/entitlement",
            requests.single().url,
        )
        assertEquals("Bearer native-access-token-value", requests.single().headers["Authorization"])
        assertEquals("android", requests.single().headers["X-Manuscript-Platform"])
    }
    @Test
    fun `account tokens are encrypted before durable storage`() {
        val generator = KeyGenerator.getInstance("AES").apply { init(256) }
        val key = generator.generateKey()
        val cipher = AccountSessionCipher { key }
        val session = AccountSession(
            accountId = "26fb8e92-c81f-4d2c-a479-796f9a7ceab1",
            email = "owner@example.com",
            displayName = "Owner",
            accessToken = "native-access-token-value",
            refreshToken = "native-refresh-token-value",
            expiresIn = 3600,
        )

        val envelope = cipher.encrypt(session)

        assertFalse(envelope.ciphertext.contains(session.email))
        assertFalse(envelope.ciphertext.contains(session.accessToken))
        assertFalse(envelope.ciphertext.contains(session.refreshToken))
        assertEquals(session, cipher.decrypt(envelope))
    }
    @Test
    fun `expired Android access token rotates once and retries entitlement`() {
        val requests = mutableListOf<AccountHttpRequest>()
        val transport = AccountTransport { request ->
            requests += request
            when (requests.size) {
                1 -> AccountHttpResponse(401, "{\"error\":\"expired\"}")
                2 -> AccountHttpResponse(
                    200,
                    """{
                      "account":{"id":"26fb8e92-c81f-4d2c-a479-796f9a7ceab1","email":"owner@example.com","displayName":"Owner"},
                      "accessToken":"rotated-access-token-value",
                      "refreshToken":"rotated-refresh-token-value",
                      "expiresIn":3600,
                      "platform":"android"
                    }""".trimIndent(),
                )
                else -> AccountHttpResponse(
                    200,
                    """{
                      "account":{"id":"26fb8e92-c81f-4d2c-a479-796f9a7ceab1","email":"owner@example.com"},
                      "entitlement":{"active":true,"status":"ACTIVE","checkedAt":"2026-07-28T12:00:00.000Z","nextCheckSeconds":900},
                      "platform":"android"
                    }""".trimIndent(),
                )
            }
        }
        val original = AccountSession(
            accountId = "26fb8e92-c81f-4d2c-a479-796f9a7ceab1",
            email = "owner@example.com",
            displayName = "Owner",
            accessToken = "expired-access-token-value",
            refreshToken = "old-refresh-token-value",
            expiresIn = 3600,
        )

        val verification = AccountApiClient(
            "https://manuscript-writing.seventhree170.chatgpt.site",
            transport,
        ).verifyEntitlement(original)

        assertTrue(verification.entitlement.active)
        assertEquals("rotated-access-token-value", verification.session.accessToken)
        assertEquals(3, requests.size)
        assertTrue(requests[1].url.endsWith("/api/auth/refresh"))
        assertEquals("Bearer rotated-access-token-value", requests[2].headers["Authorization"])
    }
    @Test
    fun `production access requires an active entitlement while test builds stay unlocked`() {
        val active = AccountEntitlement(
            accountId = "26fb8e92-c81f-4d2c-a479-796f9a7ceab1",
            email = "owner@example.com",
            active = true,
            status = "ACTIVE",
            checkedAt = "2026-07-28T12:00:00.000Z",
            nextCheckSeconds = 900,
        )
        val inactive = active.copy(active = false, status = "CANCELLED")

        assertTrue(SubscriptionAccessPolicy.allowsAccess(active, enforcementEnabled = true))
        assertFalse(SubscriptionAccessPolicy.allowsAccess(inactive, enforcementEnabled = true))
        assertFalse(SubscriptionAccessPolicy.allowsAccess(null, enforcementEnabled = true))
        assertTrue(SubscriptionAccessPolicy.allowsAccess(null, enforcementEnabled = false))
    }
    @Test
    fun `account control links always use the validated service origin`() {
        assertEquals(
            "https://manuscript-writing.seventhree170.chatgpt.site/forgot-password",
            AccountApiContract.recoveryUrl(
                "https://manuscript-writing.seventhree170.chatgpt.site",
            ),
        )
        assertEquals(
            "https://manuscript-writing.seventhree170.chatgpt.site/delete-account",
            AccountApiContract.deletionUrl(
                "https://manuscript-writing.seventhree170.chatgpt.site",
            ),
        )
    }}