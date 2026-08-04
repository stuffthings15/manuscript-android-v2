package com.echolink.mobile

class AccessibilityTraversalBudget(
    private val maxDepth: Int = MAX_DEPTH,
    private val maxNodes: Int = MAX_NODES,
) {
    private var visitedNodes = 0

    init {
        require(maxDepth >= 0)
        require(maxNodes > 0)
    }

    fun tryVisit(depth: Int): Boolean {
        if (depth !in 0..maxDepth || visitedNodes >= maxNodes) return false
        visitedNodes += 1
        return true
    }

    fun canDescendFrom(depth: Int): Boolean =
        depth < maxDepth && visitedNodes < maxNodes

    companion object {
        const val MAX_DEPTH = 32
        const val MAX_NODES = 512
    }
}
