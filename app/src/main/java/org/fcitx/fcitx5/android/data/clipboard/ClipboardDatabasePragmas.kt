package org.fcitx.fcitx5.android.data.clipboard

import androidx.sqlite.db.SupportSQLiteDatabase

internal object ClipboardDatabasePragmas {
    fun enableSecureDelete(db: SupportSQLiteDatabase) {
        // This assignment returns a row. Android's execSQL rejects statements
        // with results; moving the query cursor executes the PRAGMA lazily.
        db.query("PRAGMA secure_delete=ON").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 1) {
                "Could not enable SQLite secure_delete"
            }
        }
    }
}
