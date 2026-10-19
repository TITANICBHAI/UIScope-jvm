package com.titanicbhai.uiscope.repository

import com.titanicbhai.uiscope.db.DatabaseFactory
import com.titanicbhai.uiscope.model.WatchConditionType
import com.titanicbhai.uiscope.model.WatchRule

class WatchRuleRepository {
    private val queries get() = DatabaseFactory.getDatabase().watchRulesQueries

    fun insert(rule: WatchRule) {
        queries.insertWatchRule(
            id = rule.id,
            label = rule.label,
            condition_type = rule.conditionType.name,
            target_resource_id = rule.targetResourceId,
            target_class_name = rule.targetClassName,
            target_text = rule.targetText,
            poll_interval_ms = rule.pollIntervalMs,
            created_at = rule.createdAt
        )
    }

    fun getAll(): List<WatchRule> =
        queries.selectAllWatchRules().executeAsList().map { it.toModel() }

    fun delete(id: String) = queries.deleteWatchRule(id)

    private fun com.titanicbhai.uiscope.db.Watch_rules.toModel() = WatchRule(
        id = id,
        label = label,
        conditionType = runCatching { WatchConditionType.valueOf(condition_type) }
            .getOrDefault(WatchConditionType.ELEMENT_APPEARS),
        targetResourceId = target_resource_id,
        targetClassName = target_class_name,
        targetText = target_text,
        pollIntervalMs = poll_interval_ms,
        isActive = false,
        createdAt = created_at
    )
}
