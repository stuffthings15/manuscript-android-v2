package com.echolink.mobile

interface DelayedWorkScheduler {
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun remove(runnable: Runnable)
}

class TrackedDelayedWork(
    private val scheduler: DelayedWorkScheduler,
) {
    @JvmInline
    value class Token internal constructor(internal val generation: Long)

    private var generation = 0L
    private val pending = linkedSetOf<Runnable>()

    val pendingCount: Int
        get() = pending.size

    fun beginGeneration(): Token {
        cancelAll()
        return Token(generation)
    }

    fun isCurrent(token: Token): Boolean = token.generation == generation

    fun schedule(token: Token, delayMillis: Long, block: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        lateinit var runnable: Runnable
        runnable = Runnable {
            pending.remove(runnable)
            if (isCurrent(token)) block()
        }
        pending += runnable
        scheduler.postDelayed(runnable, delayMillis)
        return true
    }

    fun cancelAll() {
        generation += 1L
        pending.toList().forEach(scheduler::remove)
        pending.clear()
    }
}
