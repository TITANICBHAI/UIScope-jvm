package com.titanicbhai.uiscope.model

data class ExportRecord(
    val timestamp: Long,
    val captureId: String,
    val pkg: String,
    val nodeCount: Int
)
