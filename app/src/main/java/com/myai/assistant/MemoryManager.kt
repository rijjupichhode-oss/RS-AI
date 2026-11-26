package com.myai.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryManager(context: Context) : SQLiteOpenHelper(context, "rs_ai_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT, ts LONG)")
        db.execSQL("CREATE TABLE contacts (name TEXT PRIMARY KEY, phone TEXT, insta_id TEXT)")
        db.execSQL("CREATE TABLE chat_history (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, msg TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun saveFact(fact: String) {
        if (fact.isBlank() || fact == "null") return
        val cv = ContentValues().apply {
            put("fact", fact.trim())
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("memories", null, cv)
    }

    fun getAllFacts(): String {
        val list = mutableListOf<String>()
        val c = readableDatabase.rawQuery("SELECT fact FROM memories ORDER BY id DESC LIMIT 40", null)
        while (c.moveToNext()) list.add("- " + c.getString(0))
        c.close()
        return if (list.isEmpty()) "Koi purani memory abhi save nahi hai." else list.joinToString("\n")
    }

    fun saveContact(name: String, phone: String, instaId: String) {
        val cleanName = name.lowercase().trim()
        if (cleanName.isBlank()) return
        val old = getContact(cleanName)
        val cv = ContentValues().apply {
            put("name", cleanName)
            put("phone", if (phone.isNotBlank()) phone.trim() else (old.first ?: ""))
            put("insta_id", if (instaId.isNotBlank()) instaId.replace("@", "").trim() else (old.second ?: ""))
        }
        writableDatabase.insertWithOnConflict("contacts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getContact(name: String): Pair<String?, String?> {
        val c = readableDatabase.rawQuery(
            "SELECT phone, insta_id FROM contacts WHERE name = ?",
            arrayOf(name.lowercase().trim())
        )
        var res: Pair<String?, String?> = Pair(null, null)
        if (c.moveToFirst()) res = Pair(c.getString(0), c.getString(1))
        c.close()
        return res
    }

    fun getAllContactsText(): String {
        val list = mutableListOf<String>()
        val c = readableDatabase.rawQuery("SELECT name, phone, insta_id FROM contacts", null)
        while (c.moveToNext()) {
            list.add("${c.getString(0)} -> Phone: ${c.getString(1)}, Insta: @${c.getString(2)}")
        }
        c.close()
        return if (list.isEmpty()) "Koi contact save nahi hai." else list.joinToString("\n")
    }

    fun addChatTurn(role: String, msg: String) {
        val cv = ContentValues().apply {
            put("role", role)
            put("msg", msg)
        }
        writableDatabase.insert("chat_history", null, cv)
    }

    fun getRecentChat(): String {
        val list = mutableListOf<String>()
        val c = readableDatabase.rawQuery("SELECT role, msg FROM chat_history ORDER BY id DESC LIMIT 10", null)
        while (c.moveToNext()) list.add("${c.getString(0)}: ${c.getString(1)}")
        c.close()
        return list.reversed().joinToString("\n")
    }
}
