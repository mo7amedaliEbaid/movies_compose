package com.example.myapplication.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * INTERVIEW | Caching Q1: How does Room validate SQL at compile time?
 * Room uses annotation processing (KSP/KAPT) during the build phase. It reads @Query annotations,
 * parses the SQL string against the generated schema, and verifies that tables/columns exist and
 * that return types match DAO function signatures. A SQL typo or wrong column name becomes a
 * BUILD ERROR, not a runtime crash.
 *
 * INTERVIEW | Caching Q2: How does Room handle Flow queries? What thread do they run on?
 * When a DAO returns Flow<T>, Room registers an observer on the underlying SQLite tables. On
 * first collection the query runs on Room's internal background thread pool. On every INSERT /
 * UPDATE / DELETE to an observed table, Room re-runs the query and emits the new list.
 * You do NOT need flowOn(Dispatchers.IO) — Room handles off-threading internally.
 */
@Dao
interface MovieDao {

    @Query("SELECT * FROM movies")
    fun getPopularMovies(): Flow<List<MovieEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<MovieEntity>)

    @Query("SELECT * FROM movies WHERE id = :id")
    suspend fun getMovieById(id: Int): MovieEntity?

    @Query("DELETE FROM movies")
    suspend fun clearAllMovies()
}
