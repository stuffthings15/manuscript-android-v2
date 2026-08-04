package com.echolink.mobile

data class FieldBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

@JvmInline
value class ExternalFieldIdentity(val value: String) {
    init {
        require(value.isNotBlank())
    }

    fun asReviewTargetId(): ReviewTargetId = ReviewTargetId(value)

    companion object {
        fun from(
            packageName: String,
            windowId: Int,
            viewId: String?,
            className: String,
            bounds: FieldBounds,
        ): ExternalFieldIdentity {
            require(packageName.isNotBlank())
            val fieldKey = viewId?.takeIf(String::isNotBlank)?.let { "id:$it" }
                ?: "geometry:$className:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            return ExternalFieldIdentity("$packageName|window:$windowId|$fieldKey")
        }
    }
}
