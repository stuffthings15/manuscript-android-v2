package com.echolink.mobile

/**
 * Owns every rewrite requested by one review.
 *
 * Requests may be queued together by the single-threaded local grammar engine.
 * They stay independently cancellable so changing tone or closing the review
 * stops the entire stale batch.
 */
class RewriteRequestBatch {
    private val active = mutableMapOf<String, LocalPolishRequest?>()

    @Synchronized
    fun begin(key: String): Boolean {
        if (active.containsKey(key)) return false
        active[key] = null
        return true
    }

    fun attach(key: String, request: LocalPolishRequest) {
        val keep = synchronized(this) {
            if (active.containsKey(key)) {
                active[key] = request
                true
            } else {
                false
            }
        }
        if (!keep) request.cancel()
    }

    @Synchronized
    fun complete(key: String) {
        active.remove(key)
    }

    fun cancelAll() {
        val requests = synchronized(this) {
            val snapshot = active.values.filterNotNull()
            active.clear()
            snapshot
        }
        requests.forEach(LocalPolishRequest::cancel)
    }
}
