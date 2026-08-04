package com.echolink.mobile

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeStructuralLocator {
    fun from(
        applicationRoot: AccessibilityNodeInfo,
        focusedNode: AccessibilityNodeInfo,
    ): FieldStructuralLocator? {
        val rootPackage = applicationRoot.packageName?.toString().orEmpty()
        val focusedPackage = focusedNode.packageName?.toString().orEmpty()
        if (
            applicationRoot.windowId != focusedNode.windowId ||
            rootPackage.isBlank() ||
            rootPackage != focusedPackage ||
            !focusedNode.isFocused ||
            !focusedNode.isEditable ||
            focusedNode.isPassword
        ) return null

        val reversedAncestry = mutableListOf<StructuralNodeStep>()
        val ownedAncestors = mutableListOf<AccessibilityNodeInfo>()
        val inspectionBudget = InspectionBudget()
        var current = focusedNode
        var completePath = current == applicationRoot
        var traversalTruncated = false
        try {
            while (!completePath) {
                if (reversedAncestry.size >= MAX_DEPTH) {
                    traversalTruncated = true
                    break
                }
                if (!inspectionBudget.tryInspect()) {
                    traversalTruncated = true
                    break
                }
                val parent = current.parent ?: break
                if (
                    parent == focusedNode ||
                    parent == current ||
                    ownedAncestors.any { ancestor -> ancestor == parent }
                ) {
                    if (
                        parent !== applicationRoot &&
                        parent !== focusedNode &&
                        ownedAncestors.none { ancestor -> ancestor === parent }
                    ) recycleCopiedNode(parent)
                    break
                }
                if (parent !== applicationRoot && parent !== focusedNode) {
                    ownedAncestors += parent
                }
                val childMatch = uniqueChildIndex(parent, current, inspectionBudget)
                if (childMatch.truncated) {
                    traversalTruncated = true
                    break
                }
                val matchingChildIndex = childMatch.index ?: break
                reversedAncestry += StructuralNodeStep(
                    parentClassName = parent.className?.toString().orEmpty(),
                    childIndex = matchingChildIndex,
                )
                current = parent
                completePath = current == applicationRoot
            }

            val ancestry = reversedAncestry.asReversed()
            if (
                !completePath ||
                traversalTruncated ||
                !replaysToFocusedNode(
                    applicationRoot,
                    focusedNode,
                    ancestry,
                    inspectionBudget,
                )
            ) return null
            return FieldStructuralLocator.proven(
                windowId = applicationRoot.windowId,
                focusedWindowId = focusedNode.windowId,
                ancestry = ancestry,
                leafClassName = focusedNode.className?.toString().orEmpty(),
                completePath = true,
                traversalTruncated = false,
            )
        } finally {
            ownedAncestors.forEach(::recycleCopiedNode)
        }
    }

    private fun uniqueChildIndex(
        parent: AccessibilityNodeInfo,
        target: AccessibilityNodeInfo,
        inspectionBudget: InspectionBudget,
    ): ChildMatch {
        var match: Int? = null
        for (index in 0 until parent.childCount) {
            if (!inspectionBudget.tryInspect()) return ChildMatch(index = null, truncated = true)
            val child = parent.getChild(index) ?: continue
            try {
                if (child == target) {
                    if (match != null) return ChildMatch(index = null, truncated = false)
                    match = index
                }
            } finally {
                recycleCopiedNode(child)
            }
        }
        return ChildMatch(index = match, truncated = false)
    }

    private fun replaysToFocusedNode(
        applicationRoot: AccessibilityNodeInfo,
        focusedNode: AccessibilityNodeInfo,
        ancestry: List<StructuralNodeStep>,
        inspectionBudget: InspectionBudget,
    ): Boolean {
        var current = applicationRoot
        var ownsCurrent = false
        try {
            ancestry.forEach { step ->
                if (
                    current.className?.toString().orEmpty() != step.parentClassName ||
                    step.childIndex !in 0 until current.childCount
                ) return false
                if (!inspectionBudget.tryInspect()) return false
                val child = current.getChild(step.childIndex) ?: return false
                if (ownsCurrent) recycleCopiedNode(current)
                current = child
                ownsCurrent = child !== applicationRoot && child !== focusedNode
            }
            return current == focusedNode
        } finally {
            if (ownsCurrent) recycleCopiedNode(current)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleCopiedNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private data class ChildMatch(val index: Int?, val truncated: Boolean)

    private class InspectionBudget {
        private var inspections = 0

        fun tryInspect(): Boolean {
            if (inspections >= MAX_INSPECTIONS) return false
            inspections += 1
            return true
        }
    }

    private const val MAX_DEPTH = 32
    private const val MAX_INSPECTIONS = 512
}
