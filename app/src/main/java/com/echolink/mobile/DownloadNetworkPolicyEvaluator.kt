package com.echolink.mobile

internal sealed interface DownloadNetworkPolicyEvaluation {
    data object Allowed : DownloadNetworkPolicyEvaluation

    data class Waiting(
        val reason: DownloadBlockReason,
    ) : DownloadNetworkPolicyEvaluation {
        init {
            require(reason.phase == DownloadPhase.WAITING) {
                "A network policy can only return a waiting reason."
            }
        }
    }
}

internal object DownloadNetworkPolicyEvaluator {
    fun evaluate(
        network: DownloadNetwork,
        allowMobileData: Boolean,
    ): DownloadNetworkPolicyEvaluation = when (network) {
        DownloadNetwork.WIFI,
        DownloadNetwork.ETHERNET,
        -> DownloadNetworkPolicyEvaluation.Allowed

        DownloadNetwork.MOBILE,
        DownloadNetwork.OTHER,
        -> if (allowMobileData) {
            DownloadNetworkPolicyEvaluation.Allowed
        } else {
            DownloadNetworkPolicyEvaluation.Waiting(DownloadBlockReason.WIFI_REQUIRED)
        }

        DownloadNetwork.OFFLINE,
        DownloadNetwork.UNKNOWN,
        -> DownloadNetworkPolicyEvaluation.Waiting(DownloadBlockReason.NETWORK_UNAVAILABLE)
    }
}
