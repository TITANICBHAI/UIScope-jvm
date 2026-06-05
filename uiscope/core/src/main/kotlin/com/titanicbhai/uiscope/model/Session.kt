package com.titanicbhai.uiscope.model

enum class InspectionMode { PC, ANDROID }

data class Session(
    val id: String,
    val timestamp: Long,
    val mode: InspectionMode,
    val appName: String? = null,
    val packageName: String? = null,
    val deviceName: String? = null,
    val screenshotPath: String? = null,
    val treeJson: String
)
