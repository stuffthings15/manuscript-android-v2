package com.echolink.mobile

@JvmInline
value class ExternalFieldIdentity(val value: String) {
    init {
        require(value.isNotBlank())
    }

    fun asReviewTargetId(): ReviewTargetId = ReviewTargetId(value)

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun from(
            packageName: String,
            windowId: Int,
            viewId: String?,
            className: String,
            drawingOrder: Int,
        ): ExternalFieldIdentity {
            require(packageName.isNotBlank())
            val fieldKey = viewId?.takeIf(String::isNotBlank)?.let { "id:$it" }
                ?: "order:$className:$drawingOrder"
            return ExternalFieldIdentity("$packageName|$fieldKey")
        }
    }
}
