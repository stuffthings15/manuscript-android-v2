package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadNetworkPolicyEvaluatorTest {
    @Test
    fun wifiOnlyAllowsWifiAndEthernet() {
        val expected = mapOf(
            DownloadNetwork.WIFI to DownloadNetworkPolicyEvaluation.Allowed,
            DownloadNetwork.ETHERNET to DownloadNetworkPolicyEvaluation.Allowed,
            DownloadNetwork.MOBILE to wifiRequired,
            DownloadNetwork.OTHER to wifiRequired,
            DownloadNetwork.OFFLINE to networkUnavailable,
            DownloadNetwork.UNKNOWN to networkUnavailable,
        )

        assertPolicy(expected, allowMobileData = false)
    }

    @Test
    fun allowMobileAcceptsEveryKnownOnlineNetwork() {
        val expected = mapOf(
            DownloadNetwork.WIFI to DownloadNetworkPolicyEvaluation.Allowed,
            DownloadNetwork.ETHERNET to DownloadNetworkPolicyEvaluation.Allowed,
            DownloadNetwork.MOBILE to DownloadNetworkPolicyEvaluation.Allowed,
            DownloadNetwork.OTHER to DownloadNetworkPolicyEvaluation.Allowed,
            DownloadNetwork.OFFLINE to networkUnavailable,
            DownloadNetwork.UNKNOWN to networkUnavailable,
        )

        assertPolicy(expected, allowMobileData = true)
    }

    @Test
    fun everyDownloadNetworkHasAnExplicitDecisionForBothPolicies() {
        listOf(false, true).forEach { allowMobileData ->
            val decisions = DownloadNetwork.entries.associateWith { network ->
                DownloadNetworkPolicyEvaluator.evaluate(network, allowMobileData)
            }

            assertEquals(DownloadNetwork.entries.toSet(), decisions.keys)
        }
    }

    @Test
    fun waitingDecisionRejectsReasonsThatAreNotNetworkWaitingStates() {
        listOf(
            DownloadBlockReason.USER_PAUSED,
            DownloadBlockReason.DEVICE_CONSTRAINT,
        ).forEach { reason ->
            assertThrows(IllegalArgumentException::class.java) {
                DownloadNetworkPolicyEvaluation.Waiting(reason)
            }
        }
    }

    private fun assertPolicy(
        expected: Map<DownloadNetwork, DownloadNetworkPolicyEvaluation>,
        allowMobileData: Boolean,
    ) {
        DownloadNetwork.entries.forEach { network ->
            assertEquals(
                network.name,
                expected.getValue(network),
                DownloadNetworkPolicyEvaluator.evaluate(network, allowMobileData),
            )
        }
    }

    private val wifiRequired = DownloadNetworkPolicyEvaluation.Waiting(
        DownloadBlockReason.WIFI_REQUIRED,
    )
    private val networkUnavailable = DownloadNetworkPolicyEvaluation.Waiting(
        DownloadBlockReason.NETWORK_UNAVAILABLE,
    )
}
