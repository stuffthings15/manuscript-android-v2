package com.echolink.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDownloadNetworkReaderTest {
    @Test
    fun missingNetworkAndCapabilitiesFailClosedWithoutGuessing() {
        assertEquals(
            DownloadNetwork.OFFLINE,
            AndroidDownloadNetworkClassifier.classify(
                DownloadNetworkSnapshot(activeNetworkPresent = false),
            ),
        )
        assertEquals(
            DownloadNetwork.UNKNOWN,
            AndroidDownloadNetworkClassifier.classify(
                DownloadNetworkSnapshot(
                    activeNetworkPresent = true,
                    capabilitiesPresent = false,
                ),
            ),
        )
        assertEquals(
            DownloadNetwork.OFFLINE,
            AndroidDownloadNetworkClassifier.classify(
                connectedSnapshot(hasInternetCapability = false),
            ),
        )
    }

    @Test
    fun everySupportedTransportMapsToTheExactTelemetryNetwork() {
        val expected = mapOf(
            DownloadNetworkTransport.WIFI to DownloadNetwork.WIFI,
            DownloadNetworkTransport.ETHERNET to DownloadNetwork.ETHERNET,
            DownloadNetworkTransport.MOBILE to DownloadNetwork.MOBILE,
            DownloadNetworkTransport.OTHER to DownloadNetwork.OTHER,
        )

        expected.forEach { (transport, network) ->
            assertEquals(
                network,
                AndroidDownloadNetworkClassifier.classify(
                    connectedSnapshot(transports = setOf(transport)),
                ),
            )
        }
    }

    @Test
    fun transportIdentityNeverChangesBecauseTheConnectionIsMetered() {
        listOf(false, true).forEach { metered ->
            val wifi = AndroidDownloadNetworkClassifier.classify(
                connectedSnapshot(
                    transports = setOf(DownloadNetworkTransport.WIFI),
                    metered = metered,
                ),
            )
            val mobile = AndroidDownloadNetworkClassifier.classify(
                connectedSnapshot(
                    transports = setOf(DownloadNetworkTransport.MOBILE),
                    metered = metered,
                ),
            )

            assertEquals(DownloadNetwork.WIFI, wifi)
            assertEquals(DownloadNetwork.MOBILE, mobile)
            assertEquals(
                DownloadNetworkPolicyEvaluation.Allowed,
                DownloadNetworkPolicyEvaluator.evaluate(wifi, allowMobileData = false),
            )
            assertEquals(
                DownloadNetworkPolicyEvaluation.Waiting(DownloadBlockReason.WIFI_REQUIRED),
                DownloadNetworkPolicyEvaluator.evaluate(mobile, allowMobileData = false),
            )
        }
    }

    @Test
    fun physicalTransportPrecedenceIsStableAndUnknownOnlineTransportIsOther() {
        assertEquals(
            DownloadNetwork.WIFI,
            AndroidDownloadNetworkClassifier.classify(
                connectedSnapshot(
                    transports = setOf(
                        DownloadNetworkTransport.MOBILE,
                        DownloadNetworkTransport.ETHERNET,
                        DownloadNetworkTransport.WIFI,
                    ),
                ),
            ),
        )
        assertEquals(
            DownloadNetwork.OTHER,
            AndroidDownloadNetworkClassifier.classify(connectedSnapshot()),
        )
    }

    private fun connectedSnapshot(
        hasInternetCapability: Boolean = true,
        transports: Set<DownloadNetworkTransport> = emptySet(),
        metered: Boolean = false,
    ): DownloadNetworkSnapshot = DownloadNetworkSnapshot(
        activeNetworkPresent = true,
        capabilitiesPresent = true,
        hasInternetCapability = hasInternetCapability,
        transports = transports,
        metered = metered,
    )
}
