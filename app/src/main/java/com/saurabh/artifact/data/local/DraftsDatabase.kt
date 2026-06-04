package com.saurabh.artifact.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.saurabh.artifact.model.ArtifactLifecycle

@Database(
    entities = [ArtifactDraftEntity::class, UploadTaskEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DraftsDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun uploadTaskDao(): UploadTaskDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add the new lifecycle column with a default value
                db.execSQL("ALTER TABLE artifact_drafts ADD COLUMN lifecycle TEXT NOT NULL DEFAULT 'RECORDING'")

                // 2. Try to populate the lifecycle from the status JSON
                // SQLite doesn't have robust JSON parsing by default, but we can do a best-effort using LIKE
                val lifecycles = ArtifactLifecycle.entries.map { it.name }
                for (lifecycle in lifecycles) {
                    db.execSQL(
                        "UPDATE artifact_drafts SET lifecycle = ? WHERE status LIKE ?",
                        arrayOf(lifecycle, "%\"lifecycle\":\"$lifecycle\"%")
                    )
                }
            }
        }
    }
}
