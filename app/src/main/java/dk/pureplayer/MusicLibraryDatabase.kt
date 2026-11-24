
package dk.pureplayer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

data class LibraryTrack(
    val uri: Uri,
    val name: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String
)

class MusicLibraryDatabase(context: Context) :
    SQLiteOpenHelper(context, "pureplayer_library.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tracks (
                uri TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                albumArtist TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE tracks ADD COLUMN title TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE tracks ADD COLUMN artist TEXT NOT NULL DEFAULT 'Ukendt kunstner'"
            )
            db.execSQL(
                "ALTER TABLE tracks ADD COLUMN album TEXT NOT NULL DEFAULT 'Ukendt album'"
            )
            db.execSQL(
                "ALTER TABLE tracks ADD COLUMN albumArtist TEXT NOT NULL DEFAULT 'Ukendt kunstner'"
            )
        }
    }

    fun getTracks(): List<LibraryTrack> {
        val result = mutableListOf<LibraryTrack>()

        readableDatabase.query(
            "tracks",
            arrayOf(
                "uri",
                "name",
                "title",
                "artist",
                "album",
                "albumArtist"
            ),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC"
        ).use { cursor ->

            while (cursor.moveToNext()) {
                result.add(
                    LibraryTrack(
                        uri = Uri.parse(cursor.getString(0)),
                        name = cursor.getString(1),
                        title = cursor.getString(2),
                        artist = cursor.getString(3),
                        album = cursor.getString(4),
                        albumArtist = cursor.getString(5)
                    )
                )
            }
        }

        return result
    }

    fun saveTracks(tracks: List<LibraryTrack>) {
        val db = writableDatabase

        db.beginTransaction()

        try {
            tracks.forEach { track ->
                val values = ContentValues().apply {
                    put("uri", track.uri.toString())
                    put("name", track.name)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("albumArtist", track.albumArtist)
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

    fun removeMissingTracks(validUris: Set<String>) {
        val db = writableDatabase

        db.beginTransaction()

        try {
            val existing = mutableListOf<String>()

            db.rawQuery(
                "SELECT uri FROM tracks",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    existing.add(cursor.getString(0))
                }
            }

            existing.filter { it !in validUris }.forEach { uri ->
                db.delete(
                    "tracks",
                    "uri = ?",
                    arrayOf(uri)
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
