package com.saurabh.artifact.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ArtifactDraftEntity::class, UploadTaskEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DraftsDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun uploadTaskDao(): UploadTaskDao
}
