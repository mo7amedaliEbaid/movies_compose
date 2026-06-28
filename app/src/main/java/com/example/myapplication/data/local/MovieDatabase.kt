package com.example.myapplication.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/*
 * INTERVIEW | Caching Q4: Room Database Migrations
 * Every time you add/rename a column or change a table, increment `version`. If no migration
 * is provided, Room throws IllegalStateException at runtime.
 * Options:
 *   - fallbackToDestructiveMigration(): drops and recreates all tables (dev only — data loss).
 *   - Migration(oldVersion, newVersion): write explicit SQL to ALTER/CREATE tables in place.
 *     Register via Room.databaseBuilder(...).addMigrations(MIGRATION_1_2).build()
 */
@Database(entities = [MovieEntity::class], version = 1, exportSchema = false)
abstract class MovieDatabase : RoomDatabase() {
    abstract val movieDao: MovieDao
}
