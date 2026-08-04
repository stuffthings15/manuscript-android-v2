package com.echolink.mobile

data class StructuralNodeStep(
    val parentClassName: String,
    val childIndex: Int,
    val childDrawingOrder: Int,
)

data class FieldStructuralLocator(
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
            append("/order:").append(step.childDrawingOrder)
        }
        append("/leaf:")
        append(leafClassName.length).append(':').append(leafClassName)
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
            structuralLocator: FieldStructuralLocator,
        ): ExternalFieldIdentity {
            require(packageName.isNotBlank())
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
