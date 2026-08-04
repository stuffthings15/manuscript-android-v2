package com.echolink.mobile

data class StructuralNodeStep(
    val parentClassName: String,
    val childIndex: Int,
)

@ConsistentCopyVisibility
data class FieldStructuralLocator private constructor(
    val windowId: Int,
    val ancestry: List<StructuralNodeStep>,
    val leafClassName: String,
) {
    fun encoded(): String = buildString {
        append("window:").append(windowId)
        ancestry.forEach { step ->
            append("/parent:")
            append(step.parentClassName.length).append(':').append(step.parentClassName)
            append("/child:").append(step.childIndex)
        }
        append("/leaf:")
        append(leafClassName.length).append(':').append(leafClassName)
    }

    companion object {
        internal fun proven(
            windowId: Int,
            focusedWindowId: Int,
            ancestry: List<StructuralNodeStep>,
            leafClassName: String,
            completePath: Boolean,
            traversalTruncated: Boolean,
        ): FieldStructuralLocator? {
            if (!completePath || traversalTruncated || windowId != focusedWindowId) return null
            if (leafClassName.isBlank()) return null
            if (ancestry.any { step ->
                    step.parentClassName.isBlank() || step.childIndex < 0
                }
            ) return null
            return FieldStructuralLocator(
                windowId = windowId,
                ancestry = ancestry.toList(),
                leafClassName = leafClassName,
            )
        }
    }
}

@JvmInline
value class ExternalFieldIdentity(val value: String) {
    init {
        require(value.isNotBlank())
    }

    fun asReviewTargetId(): ReviewTargetId = ReviewTargetId(value)

    companion object {
        fun from(
            packageName: String,
            platformUniqueId: String?,
            viewId: String?,
            structuralLocator: FieldStructuralLocator?,
        ): ExternalFieldIdentity? {
            if (packageName.isBlank() || structuralLocator == null) return null
            val fieldKey = platformUniqueId?.takeIf(String::isNotBlank)?.let { uniqueId ->
                "unique:${uniqueId.length}:$uniqueId"
            } ?: buildString {
                append("structure:").append(structuralLocator.encoded())
                viewId?.takeIf(String::isNotBlank)?.let { stableViewId ->
                    append("/view:").append(stableViewId.length).append(':').append(stableViewId)
                }
            }
            return ExternalFieldIdentity("$packageName|$fieldKey")
        }
    }
}
