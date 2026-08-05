package com.echolink.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal enum class DownloadNetworkTransport {
    WIFI,
    ETHERNET,
    MOBILE,
    OTHER,
}

internal data class DownloadNetworkSnapshot(
    val activeNetworkPresent: Boolean,
    val capabilitiesPresent: Boolean = false,
    val hasInternetCapability: Boolean = false,
    val transports: Set<DownloadNetworkTransport> = emptySet(),
    val metered: Boolean = false,
)

internal object AndroidDownloadNetworkClassifier {
    fun classify(snapshot: DownloadNetworkSnapshot): DownloadNetwork {
        if (!snapshot.activeNetworkPresent) return DownloadNetwork.OFFLINE
        if (!snapshot.capabilitiesPresent) return DownloadNetwork.UNKNOWN
        if (!snapshot.hasInternetCapability) return DownloadNetwork.OFFLINE
        return when {
            DownloadNetworkTransport.WIFI in snapshot.transports -> DownloadNetwork.WIFI
            DownloadNetworkTransport.ETHERNET in snapshot.transports -> DownloadNetwork.ETHERNET
            DownloadNetworkTransport.MOBILE in snapshot.transports -> DownloadNetwork.MOBILE
            else -> DownloadNetwork.OTHER
        }
    }
}

internal fun interface DownloadNetworkReader {
    fun current(): DownloadNetwork
}

internal class AndroidDownloadNetworkReader(context: Context) : DownloadNetworkReader {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun current(): DownloadNetwork {
        val active = connectivityManager.activeNetwork
            ?: return DownloadNetwork.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(active)
            ?: return DownloadNetwork.UNKNOWN
        val transports = buildSet {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                add(DownloadNetworkTransport.WIFI)
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                add(DownloadNetworkTransport.ETHERNET)
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                add(DownloadNetworkTransport.MOBILE)
            }
            if (isEmpty()) add(DownloadNetworkTransport.OTHER)
        }
        return AndroidDownloadNetworkClassifier.classify(
            DownloadNetworkSnapshot(
                activeNetworkPresent = true,
                capabilitiesPresent = true,
                hasInternetCapability = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET,
                ),
                transports = transports,
                metered = connectivityManager.isActiveNetworkMetered,
            ),
        )
    }
}
