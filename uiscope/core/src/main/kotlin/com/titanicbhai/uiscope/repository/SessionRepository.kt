package com.titanicbhai.uiscope.repository

import com.titanicbhai.uiscope.db.DatabaseFactory
import com.titanicbhai.uiscope.model.InspectionMode
import com.titanicbhai.uiscope.model.Session

class SessionRepository {
    private val queries get() = DatabaseFactory.getDatabase().sessionsQueries

    fun insert(session: Session) {
        queries.insertSession(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode.name,
            app_name = session.appName,
            package_name = session.packageName,
            device_name = session.deviceName,
            screenshot_path = session.screenshotPath,
            tree_json = session.treeJson
        )
    }

    fun getAll(): List<Session> =
        queries.selectAllSessions().executeAsList().map { it.toModel() }

    fun getById(id: String): Session? =
        queries.selectSessionById(id).executeAsOneOrNull()?.toModel()

    fun delete(id: String) = queries.deleteSession(id)

    fun pruneToLimit(limit: Long) = queries.deleteOldSessions(limit)

    private fun com.titanicbhai.uiscope.db.Sessions.toModel() = Session(
        id = id,
        timestamp = timestamp,
        mode = runCatching { InspectionMode.valueOf(mode) }.getOrDefault(InspectionMode.PC),
        appName = app_name,
        packageName = package_name,
        deviceName = device_name,
        screenshotPath = screenshot_path,
        treeJson = tree_json
    )
}
