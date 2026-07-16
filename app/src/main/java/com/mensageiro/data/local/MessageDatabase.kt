package com.mensageiro.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class MessageRow(
    val id: String,
    val peerId: String,
    val timestamp: Long,
    val payload: String
)

internal class MessageDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "messages.db", null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                message_id TEXT PRIMARY KEY NOT NULL,
                peer_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                payload TEXT NOT NULL
            )"""
        )
        db.execSQL(
            "CREATE INDEX index_messages_conversation_order " +
                "ON messages(peer_id, timestamp DESC, message_id DESC)"
        )
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun importLegacy(rows: () -> List<MessageRow>) {
        writableDatabase.beginTransaction()
        try {
            if (!hasMetadata(writableDatabase, LegacyImported)) {
                rows().forEach { upsert(writableDatabase, it) }
                writableDatabase.insertOrThrow(
                    "metadata",
                    null,
                    ContentValues().apply {
                        put("key", LegacyImported)
                        put("value", "1")
                    }
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun loadAll(): List<MessageRow> = query(
        "SELECT message_id, peer_id, timestamp, payload FROM messages " +
            "ORDER BY timestamp, message_id"
    )

    fun get(id: String): MessageRow? = query(
        "SELECT message_id, peer_id, timestamp, payload FROM messages " +
            "WHERE message_id = ? LIMIT 1",
        arrayOf(id)
    ).firstOrNull()

    fun page(peerId: String, beforeTimestamp: Long?, beforeId: String?, limit: Int): List<MessageRow> {
        require(limit in 1..200)
        val cursorTimestamp = beforeTimestamp
        val cursorId = beforeId
        val cursorFilter = if (cursorTimestamp == null || cursorId == null) "" else
            "AND (timestamp < ? OR (timestamp = ? AND message_id < ?))"
        val args = buildList {
            add(peerId)
            if (cursorFilter.isNotEmpty()) {
                add(requireNotNull(cursorTimestamp).toString())
                add(cursorTimestamp.toString())
                add(requireNotNull(cursorId))
            }
            add(limit.toString())
        }.toTypedArray()
        return query(
            "SELECT message_id, peer_id, timestamp, payload FROM messages " +
                "WHERE peer_id = ? $cursorFilter " +
                "ORDER BY timestamp DESC, message_id DESC LIMIT ?",
            args
        )
    }

    fun from(peerId: String, timestamp: Long, id: String): List<MessageRow> = query(
        "SELECT message_id, peer_id, timestamp, payload FROM messages " +
            "WHERE peer_id = ? AND (timestamp > ? OR (timestamp = ? AND message_id >= ?)) " +
            "ORDER BY timestamp, message_id",
        arrayOf(peerId, timestamp.toString(), timestamp.toString(), id)
    )

    fun upsert(row: MessageRow) = upsert(writableDatabase, row)

    fun upsert(rows: List<MessageRow>) {
        writableDatabase.beginTransaction()
        try {
            rows.forEach { upsert(writableDatabase, it) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun delete(id: String) {
        writableDatabase.delete("messages", "message_id = ?", arrayOf(id))
    }

    fun delete(ids: Set<String>) {
        writableDatabase.beginTransaction()
        try {
            ids.forEach { writableDatabase.delete("messages", "message_id = ?", arrayOf(it)) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun deleteConversation(peerId: String) {
        writableDatabase.delete("messages", "peer_id = ?", arrayOf(peerId))
    }

    fun replaceAll(rows: List<MessageRow>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", null, null)
            rows.forEach { upsert(writableDatabase, it) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun query(sql: String, args: Array<String> = emptyArray()): List<MessageRow> =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MessageRow(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getLong(2),
                            cursor.getString(3)
                        )
                    )
                }
            }
        }

    private fun upsert(db: SQLiteDatabase, row: MessageRow) {
        db.insertWithOnConflict(
            "messages",
            null,
            ContentValues().apply {
                put("message_id", row.id)
                put("peer_id", row.peerId)
                put("timestamp", row.timestamp)
                put("payload", row.payload)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun hasMetadata(db: SQLiteDatabase, key: String): Boolean =
        db.rawQuery("SELECT 1 FROM metadata WHERE key = ? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }

    private companion object {
        const val LegacyImported = "legacy_imported"
    }
}
