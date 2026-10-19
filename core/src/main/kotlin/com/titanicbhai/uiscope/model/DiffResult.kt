package com.titanicbhai.uiscope.model

data class DiffResult(
    val id: String,
    val sessionAId: String,
    val sessionBId: String,
    val addedNodes: List<ElementNode>,
    val removedNodes: List<ElementNode>,
    val modifiedNodes: List<NodeChange>,
    val createdAt: Long
)

data class NodeChange(
    val nodeId: String,
    val changedProperties: Map<String, Pair<String?, String?>>
)
