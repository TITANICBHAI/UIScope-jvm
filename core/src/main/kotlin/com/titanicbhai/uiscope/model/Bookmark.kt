package com.titanicbhai.uiscope.model

data class Bookmark(
    val id: String,
    val label: String,
    val elementId: String,
    val className: String,
    val resourceId: String? = null,
    val sessionId: String? = null,
    val createdAt: Long
)
