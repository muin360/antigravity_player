package com.tensorix.antigravityplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Song::class,
        Playlist::class,
        PlaylistSongCrossRef::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `songs_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `albumArtUri` TEXT,
                        `dateAdded` INTEGER NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `lastScanned` INTEGER NOT NULL,
                        `bitrate` INTEGER NOT NULL,
                        `sampleRate` INTEGER NOT NULL,
                        `format` TEXT,
                        `fileSize` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `songs_new` (`id`, `title`, `artist`, `album`, `durationMs`, `filePath`, `albumArtUri`, `dateAdded`, `isFavorite`, `lastScanned`, `bitrate`, `sampleRate`, `format`, `fileSize`)
                    SELECT `id`, `title`, `artist`, `album`, `durationMs`, `filePath`, `albumArtUri`, `dateAdded`, `isFavorite`, `lastScanned`, `bitrate`, `sampleRate`, `format`, `fileSize` FROM `songs`
                """.trimIndent())
                db.execSQL("DROP TABLE `songs`")
                db.execSQL("ALTER TABLE `songs_new` RENAME TO `songs`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_songs_filePath` ON `songs` (`filePath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_title` ON `songs` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_artist` ON `songs` (`artist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album` ON `songs` (`album`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_isFavorite` ON `songs` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_lastScanned` ON `songs` (`lastScanned`)")
            }
        }

        private val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_4_5
        )

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "antigravity_player.db"
                )
                    .addMigrations(*MIGRATIONS)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
