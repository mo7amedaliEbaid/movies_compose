# Android Technical Interview Prep: Navigation & Search Optimization

This guide covers common senior-level technical interview questions about Jetpack Compose Navigation, State Preservation, SavedStateHandle, and Asynchronous Flow optimizations.

---

### Q1: Why is passing complex data objects (like Parcelable) through Compose Navigation routes considered a bad practice, and what is the recommended alternative?
**Answer:**
Passing complex data objects in routes is discouraged for three primary reasons:
1. **URL Semantic Violations**: Compose Navigation routes are structurally similar to Web URLs. Complex payloads require URL encoding, which is brittle and introduces parsing overhead.
2. **Single Source of Truth (SSOT)**: If you pass an object from Screen A to Screen B, Screen B has a stale snapshot. If Screen B modifies the movie status (e.g., marking it as favorite) or if database syncing updates the movie's rating, Screen B's UI will not reflect the changes unless it retrieves the data directly from the shared Database/Repository layer.
3. **State Overhead**: Storing large objects in the backstack bundle wastes memory and can lead to `TransactionTooLargeException` if the system saves state.

**Recommended Alternative:**
Pass only the unique identifier (`movie_id: Int` or `String`) through the route path. The target ViewModel should retrieve the latest information from the local Room database cache or repository on startup.

---

### Q2: What is the purpose of `SavedStateHandle` in ViewModels, and how does it relate to system process death?
**Answer:**
`SavedStateHandle` is a key-value map injected into ViewModels that persists data across configuration changes (e.g. rotation) and **system-initiated process death**.

- **Process Death**: When the app is in the background, Android may kill the host process to reclaim memory for foreground applications. When the user returns, the system reconstructs the Activity and ViewModels.
- **Role of `SavedStateHandle`**: Arguments passed in Compose Navigation routes are automatically bundled and stored in this handle. When Hilt constructs the ViewModel after process death, the arguments are restored, allowing the ViewModel to query the database/network and restore the UI state seamlessly without crashing.

**Code Example:**
```kotlin
val id = savedStateHandle.get<Int>("movie_id") ?: throw IllegalArgumentException("Missing ID")
```

---

### Q3: Explain the implementation and benefits of input debouncing using Kotlin Flows.
**Answer:**
**Debouncing** is an optimization technique that limits the execution of a high-frequency function. In search screens, instead of sending an API query on every keypress, we wait for a specific window of inactivity (e.g., 500ms) before making the network request.

**Flow implementation:**
```kotlin
_searchQueryFlow
    .debounce(500)
    .distinctUntilChanged()
    .collect { query ->
        performSearch(query)
    }
```
**Operators Breakdowns:**
1. **`debounce(500)`**: Delays emissions. If a new search query is typed before 500ms has elapsed, the previous query is discarded, resetting the timer.
2. **`distinctUntilChanged()`**: Filters out duplicate values. If a user types "a", then backspaces back to "a" within 500ms, it prevents redundant API calls.

**Benefits**: Saves network bandwidth, reduces server load, prevents UI lags, and avoids out-of-order race conditions where an older, slower search request arrives *after* a newer search response.

---

### Q4: How would you implement a "Cache-First, Network-Second" loading pattern on a detail screen?
**Answer:**
We implement this by performing sequential asynchronous calls within the ViewModel's initial scope:

1. **Immediate Cache Read**: Read the basic item from the Room database cache using a suspend function (`repository.getMovieFromCache(id)`). Update the UI state immediately with these details so the user sees a styled page instantly.
2. **Network Update**: Launch the network query to fetch additional metadata (e.g., runtime, genres) that are not kept in the popular list table.
3. **State Modification**: On network success, merge the new details into the UI State. On network failure, display a non-blocking error badge or toast, keeping the cached details visible.

This delivers a fast UI experience, eliminating visible blank pages while fetching remote data.
