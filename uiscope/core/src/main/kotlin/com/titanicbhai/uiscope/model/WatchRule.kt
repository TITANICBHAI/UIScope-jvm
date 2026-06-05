package com.titanicbhai.uiscope.model

enum class WatchConditionType {
    ELEMENT_APPEARS,
    ELEMENT_DISAPPEARS,
    TEXT_MATCHES
}

data class WatchRule(
    val id: String,
    val label: String,
    val conditionType: WatchConditionType,
    val targetText: String? = null,
    val targetResourceId: String? = null,
    val targetClassName: String? = null,
    val pollIntervalMs: Long = 2000L,
    val isActive: Boolean = false,
    val createdAt: Long
)
