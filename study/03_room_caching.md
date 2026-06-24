# Study Guide 03: Room Caching & Flow Streams (Offline-First)

This guide covers the implementation details, design patterns, and benefits of building an **Offline-First** architecture using **Room Database**, Kotlin **Flow**, and the **Single Source of Truth (SSOT)** pattern.

---

## 1. Single Source of Truth (SSOT) Architecture

### The Problem
If the UI fetches data directly from the network and displays it, several issues occur:
1. The app is completely useless offline.
2. Every configuration change (like screen rotation) re-fetches network data (unless complex ViewModel caching is written).
3. If multiple screens display the same movie, updates made in one screen aren't automatically reflected on the other screen, leading to inconsistent UI states.

### The Solution: Room Database as the SSOT
In an offline-first architecture, the UI **never** reads directly from the network. It always reads from the local database. The network is only used to update the local database.

```
                  ┌──────────────────────────────┐
                  │          UI SCREEN           │
                  └──────────────▲───────────────┘
                                 │ (Observe Flow)
                                 │
                  ┌──────────────┴───────────────┐
                  │      LOCAL ROOM CACHE        │
                  └──────────────▲───────────────┘
                                 │ (Insert / Transaction)
                                 │
                  ┌──────────────┴───────────────┐
                  │     REMOTE API CLIENT        │
                  └──────────────────────────────┘
```

### Benefits of SSOT
- **Offline Usability**: The user sees the last cached data instantly, even if they have no internet.
- **Fast Launches**: App renders immediately from disk rather than waiting for network roundtrips.
- **Reactive UI**: Because Room queries return a `Flow`, any update to the database (e.g., adding to bookmarks, refreshing list) automatically re-emits the new data and updates the UI without manual refresh logic.

---

## 2. Room Database Setup

Room is a Jetpack library providing an abstraction layer over SQLite. It consists of three main components:

### A. Entity (`data.local.MovieEntity`)
Represents a row in an SQLite table.
```kotlin
@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val overview: String,
    ...
)
```

### B. DAO (Data Access Object - `data.local.MovieDao`)
Defines the database operations (queries, inserts, deletes).
```kotlin
@Dao
interface MovieDao {
    @Query("SELECT * FROM movies")
    fun getPopularMovies(): Flow<List<MovieEntity>> // Emits updates automatically

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<MovieEntity>)
}
```
> **Rule of Thumb**: Queries that return a `Flow` should **not** be marked as `suspend`. Room handles execution asynchronously under the hood. However, standard write/delete operations (`@Insert`, `@Update`, `@Delete`) should be `suspend` functions because they run as one-shot operations.

### C. Database (`data.local.MovieDatabase`)
The main database holder class.
```kotlin
@Database(entities = [MovieEntity::class], version = 1, exportSchema = false)
abstract class MovieDatabase : RoomDatabase() {
    abstract val movieDao: MovieDao
}
```

---

## 3. Room Database Transactions

A **Transaction** groups multiple database queries into a single atomic operation. Either all queries succeed, or the entire transaction is rolled back.

### Why use transactions?
1. **Consistency**: If we clear the database and then insert new movies, we don't want a query to run in between and return an empty screen.
2. **Performance**: Every database operation has disk I/O overhead. Running multiple inserts inside a transaction is significantly faster than executing them individually because SQLite only commits to disk once at the end of the transaction.

### Suspending Transactions with Room KTX
Using `room-ktx`, we can execute transactions asynchronously using `db.withTransaction { ... }`:
```kotlin
override suspend fun refreshPopularMovies(page: Int) {
    val remoteMoviesResponse = api.getPopularMovies(page)
    val entities = remoteMoviesResponse.results.map { ... }
    
    db.withTransaction {
        if (page == 1) {
            dao.clearAllMovies() // Runs in transaction
        }
        dao.insertMovies(entities) // Runs in transaction
    }
}
```

---

## 4. Kotlin Flow in ViewModels

We connect the local database flow to our UI State in the ViewModel. We use `onEach` to listen to emissions, and `launchIn(viewModelScope)` to keep the flow collection scoped to the ViewModel's lifecycle.

```kotlin
init {
    // Observe database cache flow and update UI State
    repository.getPopularMovies()
        .onEach { moviesList ->
            _state.update { it.copy(movies = moviesList) }
        }
        .launchIn(viewModelScope)

    refreshMovies()
}
```
Whenever the network finishes loading movies and saves them into the database, Room notifies this collector, which instantly updates the UI state.
