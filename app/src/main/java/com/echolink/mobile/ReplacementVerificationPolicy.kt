package com.echolink.mobile

enum class ReplacementVerificationAction {
    COMPLETE,
    TRY_FRESH_CONNECTION,
    RETRY,
    FAIL,
}

enum class ReplacementInitialRoute {
    ACCESSIBILITY_NODE,
    INPUT_CONNECTION,
    REJECT,
}

object ReplacementRequestPolicy {
    fun initialRoute(
        nodeMatchesSource: Boolean,
        connectionMatchesSource: Boolean,
    ): ReplacementInitialRoute = when {
        nodeMatchesSource -> ReplacementInitialRoute.ACCESSIBILITY_NODE
        connectionMatchesSource -> ReplacementInitialRoute.INPUT_CONNECTION
        else -> ReplacementInitialRoute.REJECT
    }
}

/**
 * Pure decision policy for asynchronous editor replacement verification.
 * The input connection is allowed one fallback attempt, and only while a fresh
 * read still contains the exact source text that Manuscript reviewed.
 */
object ReplacementVerificationPolicy {
    const val CONNECTION_FALLBACK_ATTEMPT = 1

    fun nextAction(
        nodeVerified: Boolean,
        connectionVerified: Boolean,
        attempt: Int,
        maxAttempts: Int,
        connectionFallbackAttempted: Boolean,
        freshConnectionMatchesSource: Boolean,
    ): ReplacementVerificationAction {
        require(attempt >= 0)
        require(maxAttempts > 0)

        if (nodeVerified || connectionVerified) return ReplacementVerificationAction.COMPLETE
        if (!connectionFallbackAttempted &&
            attempt >= CONNECTION_FALLBACK_ATTEMPT &&
            freshConnectionMatchesSource
        ) {
            return ReplacementVerificationAction.TRY_FRESH_CONNECTION
        }
        if (attempt + 1 < maxAttempts) return ReplacementVerificationAction.RETRY
        return ReplacementVerificationAction.FAIL
    }
}

/** Stable ranking for duplicate editable-field IDs. */
object EditorTargetSelectionPolicy {
    fun score(
        textMatches: Boolean,
        focused: Boolean,
        horizontalDistancePx: Int,
        verticalDistancePx: Int,
    ): Long {
        require(horizontalDistancePx >= 0)
        require(verticalDistancePx >= 0)
        val textPenalty = if (textMatches) 0L else 10_000_000L
        val focusPenalty = if (focused) 0L else 1_000_000L
        return textPenalty + focusPenalty + horizontalDistancePx + verticalDistancePx
    }
}
