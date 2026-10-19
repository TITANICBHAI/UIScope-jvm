package com.titanicbhai.uiscope.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

object DatabaseFactory {
    private var _database: UiScopeDatabase? = null

    fun getDatabase(): UiScopeDatabase {
        return _database ?: createDatabase().also { _database = it }
    }

    private fun createDatabase(): UiScopeDatabase {
        val dbDir = File(System.getProperty("user.home"), ".uiscope")
        dbDir.mkdirs()
        val dbFile = File(dbDir, "uiscope.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        UiScopeDatabase.Schema.create(driver)
        return UiScopeDatabase(driver)
    }

    fun close() {
        _database = null
    }
}
