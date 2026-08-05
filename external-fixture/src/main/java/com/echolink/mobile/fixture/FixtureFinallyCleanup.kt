package com.echolink.mobile.fixture

object FixtureFinallyCleanup {
    inline fun <T> run(body: () -> T, cleanup: () -> Unit): T = try {
        body()
    } finally {
        cleanup()
    }
}
