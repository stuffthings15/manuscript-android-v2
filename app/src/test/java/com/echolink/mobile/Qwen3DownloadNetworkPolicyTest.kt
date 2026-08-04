package com.echolink.mobile

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class Qwen3DownloadNetworkPolicyTest {
    @Test
    fun wifiOnlyRequiresAnUnmeteredConnection() {
        assertEquals(
            NetworkType.UNMETERED,
            Qwen3DownloadNetworkPolicy.WIFI_ONLY.requiredNetworkType,
        )
    }

    @Test
    fun mobileDataChoiceAllowsAnyConnectedNetwork() {
        assertEquals(
            NetworkType.CONNECTED,
            Qwen3DownloadNetworkPolicy.ALLOW_MOBILE_DATA.requiredNetworkType,
        )
    }
}
