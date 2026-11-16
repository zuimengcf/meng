/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Ported from localsync android (io.localsync.android.SqliteSyncStore), Gson -> kotlinx.serialization.json.
 */
package org.fcitx.fcitx5.android.data.clipboard.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 引擎状态的 Android SQLite 实现。独立数据库文件，不动剪贴板 Room 库（无需 Room 迁移）；
 * 删掉这个文件只会让下一次同步从 Room 重新扫描建索引，业务数据不受影响。
 */
class SqliteSyncStore(context: Context, dbName: String = "clipboard_sync_engine.db") :
    SQLiteOpenHelper(context.applicationContext, dbName, null, VERSION), SyncStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY NOT NULL, v TEXT)")
        db.execSQL("CREATE TABLE vv(origin TEXT PRIMARY KEY NOT NULL, seq INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE state(
                coll TEXT NOT NULL, k TEXT NOT NULL,
                hash TEXT NOT NULL, alive INTEGER NOT NULL, shadow INTEGER NOT NULL,
                vals TEXT NOT NULL, clocks TEXT NOT NULL, born TEXT, deleted TEXT, token TEXT,
                PRIMARY KEY(coll, k))"""
        )
        db.execSQL("CREATE INDEX idx_state_dead ON state(alive)")
        db.execSQL("CREATE INDEX idx_state_shadow ON state(coll, shadow)")
        db.execSQL("CREATE TABLE acks(peer TEXT PRIMARY KEY NOT NULL, vv TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE ops(
                origin TEXT NOT NULL, seq INTEGER NOT NULL,
                coll TEXT NOT NULL, k TEXT NOT NULL, hlc TEXT NOT NULL,
                type INTEGER NOT NULL, isCreate INTEGER NOT NULL, units TEXT,
                PRIMARY KEY(origin, seq))"""
        )
        db.execSQL("CREATE INDEX idx_ops_key ON ops(coll, k)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    private val db: SQLiteDatabase get() = writableDatabase

    override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    override fun getMeta(key: String): String? =
        db.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun setMeta(key: String, value: String?) {
        if (value == null) db.delete("meta", "k=?", arrayOf(key))
        else db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", key); put("v", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun setPeerAck(peer: String, vv: Map<String, Long>, at: Long) {
        db.insertWithOnConflict("acks", null, ContentValues().apply {
            put("peer", peer); put("vv", EngineJson.vvToJson(vv)); put("at", at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun peerAcks(): Map<String, PeerAck> =
        db.rawQuery("SELECT peer, vv, at FROM acks", null).use {
            val m = HashMap<String, PeerAck>()
            while (it.moveToNext()) m[it.getString(0)] = PeerAck(EngineJson.vvFromJson(it.getString(1)), it.getLong(2))
            m
        }

    override fun removePeerAck(peer: String) { db.delete("acks", "peer=?", arrayOf(peer)) }

    override fun removeState(collection: String, key: String) { db.delete("state", "coll=? AND k=?", arrayOf(collection, key)) }

    override fun tombstones(): List<Pair<String, String>> =
        db.rawQuery("SELECT coll, k FROM state WHERE alive=0 AND shadow=0 AND deleted IS NOT NULL", null).use {
            val l = ArrayList<Pair<String, String>>()
            while (it.moveToNext()) l += it.getString(0) to it.getString(1)
            l
        }

    override fun versionVector(): Map<String, Long> =
        db.rawQuery("SELECT origin, seq FROM vv", null).use {
            val m = HashMap<String, Long>()
            while (it.moveToNext()) m[it.getString(0)] = it.getLong(1)
            m
        }

    override fun setVersion(origin: String, seq: Long) {
        db.insertWithOnConflict("vv", null, ContentValues().apply { put("origin", origin); put("seq", seq) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun getState(collection: String, key: String): RecordState? =
        db.rawQuery("SELECT vals, clocks, born, deleted, shadow FROM state WHERE coll=? AND k=?", arrayOf(collection, key)).use {
            if (!it.moveToFirst()) return null
            RecordState(collection, key, EngineJson.unitsFromJson(it.getString(0)), EngineJson.clocksFromJson(it.getString(1)),
                it.getString(2), it.getString(3), it.getInt(4) != 0)
        }

    override fun putState(state: RecordState, alive: Boolean, token: String?) {
        db.insertWithOnConflict("state", null, ContentValues().apply {
            put("coll", state.collection); put("k", state.key); put("hash", state.hash())
            put("alive", if (alive) 1 else 0); put("shadow", if (state.shadow) 1 else 0)
            put("vals", EngineJson.unitsToJson(state.values)); put("clocks", EngineJson.clocksToJson(state.clocks))
            put("born", state.born); put("deleted", state.deleted); put("token", token)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun stateDigests(collection: String): Map<String, StateDigest> =
        db.rawQuery("SELECT k, hash, alive, shadow, token FROM state WHERE coll=?", arrayOf(collection)).use {
            val m = HashMap<String, StateDigest>()
            while (it.moveToNext()) m[it.getString(0)] = StateDigest(it.getString(1), it.getInt(2) != 0, it.getInt(3) != 0,
                if (it.isNull(4)) null else it.getString(4))
            m
        }

    override fun shadowKeys(collection: String): List<String> =
        db.rawQuery("SELECT k FROM state WHERE coll=? AND shadow=1", arrayOf(collection)).use {
            val l = ArrayList<String>()
            while (it.moveToNext()) l += it.getString(0)
            l
        }

    override fun appendOp(op: SyncOp) {
        db.insertWithOnConflict("ops", null, ContentValues().apply {
            put("origin", op.origin); put("seq", op.seq); put("coll", op.collection); put("k", op.key); put("hlc", op.hlc)
            put("type", op.type.ordinal); put("isCreate", if (op.create) 1 else 0); put("units", EngineJson.unitsToJson(op.units))
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun hasOp(origin: String, seq: Long): Boolean =
        db.rawQuery("SELECT 1 FROM ops WHERE origin=? AND seq=?", arrayOf(origin, seq.toString())).use { it.moveToFirst() }

    override fun opsForKey(collection: String, key: String): List<SyncOp> =
        queryOps("SELECT $OP_COLS FROM ops WHERE coll=? AND k=?", arrayOf(collection, key))

    override fun deleteOp(origin: String, seq: Long) { db.delete("ops", "origin=? AND seq=?", arrayOf(origin, seq.toString())) }

    override fun opsAfter(vv: Map<String, Long>, limit: Int): List<SyncOp> {
        if (vv.isEmpty()) return queryOps("SELECT $OP_COLS FROM ops ORDER BY origin, seq LIMIT $limit", emptyArray())
        val args = ArrayList<String>()
        val sb = StringBuilder("SELECT $OP_COLS FROM ops WHERE seq > CASE origin ")
        vv.forEach { (o, s) -> sb.append("WHEN ? THEN ? "); args += o; args += s.toString() }
        sb.append("ELSE 0 END ORDER BY origin, seq LIMIT ").append(limit)
        return queryOps(sb.toString(), args.toTypedArray())
    }

    override fun opCount(): Long =
        db.rawQuery("SELECT COUNT(*) FROM ops", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun queryOps(sql: String, args: Array<String>): List<SyncOp> =
        db.rawQuery(sql, args).use {
            val l = ArrayList<SyncOp>()
            while (it.moveToNext()) l += SyncOp(it.getString(0), it.getLong(1), it.getString(2), it.getString(3), it.getString(4),
                OpType.entries[it.getInt(5)], it.getInt(6) != 0, if (it.isNull(7)) null else EngineJson.unitsFromJson(it.getString(7)))
            l
        }

    companion object {
        private const val VERSION = 1
        private const val OP_COLS = "origin, seq, coll, k, hlc, type, isCreate, units"
    }
}
