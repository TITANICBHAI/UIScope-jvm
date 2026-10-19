package com.titanicbhai.uiscope.repository

import com.titanicbhai.uiscope.db.DatabaseFactory
import com.titanicbhai.uiscope.model.Bookmark

class BookmarkRepository {
    private val queries get() = DatabaseFactory.getDatabase().bookmarksQueries

    fun insert(bookmark: Bookmark) {
        queries.insertBookmark(
            id = bookmark.id,
            label = bookmark.label,
            element_id = bookmark.elementId,
            class_name = bookmark.className,
            resource_id = bookmark.resourceId,
            session_id = bookmark.sessionId,
            created_at = bookmark.createdAt
        )
    }

    fun getAll(): List<Bookmark> =
        queries.selectAllBookmarks().executeAsList().map { it.toModel() }

    fun delete(id: String) = queries.deleteBookmark(id)

    private fun com.titanicbhai.uiscope.db.Bookmarks.toModel() = Bookmark(
        id = id,
        label = label,
        elementId = element_id,
        className = class_name,
        resourceId = resource_id,
        sessionId = session_id,
        createdAt = created_at
    )
}
