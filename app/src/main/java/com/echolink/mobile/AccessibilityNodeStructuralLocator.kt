package com.echolink.mobile

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeStructuralLocator {
    fun from(node: AccessibilityNodeInfo): FieldStructuralLocator {
        val reversedAncestry = mutableListOf<StructuralNodeStep>()
        var current = node
        var ownsCurrent = false
        try {
            while (true) {
                val parent = current.parent ?: break
                reversedAncestry += StructuralNodeStep(
                    parentClassName = parent.className?.toString().orEmpty().ifBlank { "node" },
                    childIndex = childIndex(parent, current),
                    childDrawingOrder = current.drawingOrder,
                )
                if (ownsCurrent) recycleCopiedNode(current)
                current = parent
                ownsCurrent = true
            }
        } finally {
            if (ownsCurrent) recycleCopiedNode(current)
        }
        return FieldStructuralLocator(
            windowId = node.windowId,
            ancestry = reversedAncestry.asReversed(),
            leafClassName = node.className?.toString().orEmpty().ifBlank { "editable" },
        )
    }

    private fun childIndex(parent: AccessibilityNodeInfo, target: AccessibilityNodeInfo): Int {
        for (index in 0 until parent.childCount) {
            val child = parent.getChild(index) ?: continue
            try {
                if (child == target) return index
            } finally {
                recycleCopiedNode(child)
            }
        }
        return UNKNOWN_CHILD_INDEX
    }

    @Suppress("DEPRECATION")
    private fun recycleCopiedNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private const val UNKNOWN_CHILD_INDEX = -1
}
