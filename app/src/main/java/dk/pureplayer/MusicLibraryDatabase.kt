
package dk.pureplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

class MusicLibraryDatabase(context: Context) :
    SQLiteOpenHelper(context, "pureplayer_library.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
    }

    fun getTracks(): List<Track> {
        val result = mutableListOf<Track>()

        readableDatabase.query(
            "tracks",
            arrayOf("uri", "name"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC"
        ).use { cursor ->

            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                val name = cursor.getString(1)

                result.add(
                    Track(name, Uri.parse(uri))
                )
            }
        }

        return result
    }

    fun saveTracks(tracks: List<Track>) {
        val db = writableDatabase

        db.beginTransaction()

        try {
            db.delete("tracks", null, null)

            tracks.forEach { track ->
                val values = ContentValues().apply {
                    put("uri", track.uri.toString())
                    put("name", track.name)
                }

                db.insertWithOnConflict(
                    "tracks",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearLibrary() {
        writableDatabase.delete("tracks", null, null)
    }
}
