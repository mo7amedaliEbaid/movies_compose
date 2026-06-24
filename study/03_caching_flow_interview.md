# Android Technical Interview Prep: Caching & Flow

This guide lists common technical interview questions and detailed answers regarding local databases (Room), data streams (Kotlin Flow), database transactions, and caching patterns.

---

### Q1: How does Room validate SQL queries at compile time?
**Answer:**
Room is not just a runtime library; it utilizes annotation processing (via KSP/KAPT) to parse your SQL queries defined in `@Query` annotations during the build phase.
1. When you compile the app, Room accesses your SQLite schema and validates your SQL syntax against it.
2. It verifies that tables and columns queried actually exist.
3. It checks that return types of queries match the return types of your DAO functions (e.g., if you select a column, that column maps correctly to a field in your entity).
4. If there is a syntax error or mismatch, Room fails the build at compile-time instead of letting it crash at runtime.

---

### Q2: How does Room handle `Flow` queries under the hood? On what thread do they run?
**Answer:**
1. When a DAO function returns a `Flow<T>`, Room generates an observer pattern implementation.
2. When the flow is first collected, Room executes the query on a background dispatcher (using its own internal database executors/thread pool) and emits the initial results.
3. Room registers an observer on the underlying SQLite database tables queried.
4. Whenever any write operation (Insert, Update, Delete) is performed on a table associated with the query, Room is notified of the table change.
5. Upon notification, Room automatically triggers the query again in the background and emits the updated results list to the flow.
6. **Threading:** Room executes database queries on a background thread. You do **not** need to wrap DAO Flow collection in `flowOn(Dispatchers.IO)`. Room handles the off-threading internally.

---

### Q3: What is the difference between `withTransaction` and `runInTransaction` in Room?
**Answer:**
- **`runInTransaction`**:
  - A blocking Java/Kotlin function. It runs the database transaction synchronously on the calling thread.
  - If called from the Main Thread, it will block the UI thread, potentially causing frame drops (ANR).
- **`withTransaction`**:
  - A Kotlin **suspend** extension function.
  - It handles suspension and executes the transaction asynchronously without blocking the calling thread.
  - It inherits the coroutine context and dispatcher, and automatically uses the correct database dispatcher internally.
  - Highly recommended for modern Kotlin-first Android apps using coroutines.

---

### Q4: What are Room Database Migrations? How do you handle schemas changes in production?
**Answer:**
When you modify a database entity (e.g., adding a column, changing a data type), you must increment the database version. If you do not provide a migration path, Room will throw an `IllegalStateException` and crash the application.

**How to handle it:**
1. **`fallbackToDestructiveMigration()`**: If you do not care about old data (e.g., during development), you can use this. Room will drop all tables and recreate the database. All user data is lost.
2. **`Migration` class (Standard)**: Write a concrete `Migration` object defining the SQL statement to transform the database from the old version to the new version:
   ```kotlin
   val MIGRATION_1_2 = object : Migration(1, 2) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE movies ADD COLUMN rating DOUBLE DEFAULT 0.0")
       }
   }
   ```
   Then register it when building the database:
   ```kotlin
   Room.databaseBuilder(...)
       .addMigrations(MIGRATION_1_2)
       .build()
   ```

---

### Q5: If a database flow is collected in a Composable or ViewModel, how do we prevent resource leaks?
**Answer:**
- **In ViewModel**:
  We collect using `.launchIn(viewModelScope)`. Because `viewModelScope` is automatically cancelled when the ViewModel is cleared (when the associated Activity/Fragment finishes), the flow collection is cancelled, preventing memory leaks.
- **In Composable**:
  We must collect flow safely respecting the lifecycle. Using `collectAsState()` is common, but `collectAsStateWithLifecycle()` (from `lifecycle-runtime-compose` library) is the recommended best practice. 
  `collectAsStateWithLifecycle` stops collecting when the app goes into the background (e.g., when the lifecycle goes below `STARTED`), conserving CPU cycles and resources, and automatically resumes when the app returns to the foreground.
