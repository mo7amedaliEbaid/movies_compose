# Study Guide 05: Jetpack Compose Navigation & Search Debouncing

This guide covers modern navigation patterns in Jetpack Compose, passing arguments securely, managing ViewModel state with `SavedStateHandle`, implementing cache-first detail loading, and debouncing user inputs for network search requests.

---

## 1. Jetpack Compose Navigation Architecture

Compose Navigation introduces a declarative way to navigate between screens using a `NavHost`, a `NavController`, and route definitions.

```
                  ┌──────────────────────┐
                  │    NavController     │
                  │  (Manages Backstack)  │
                  └──────────┬───────────┘
                             │
                             ▼
                  ┌──────────────────────┐
                  │       NavHost        │
                  │  (Renders active     │
                  │   composable route)  │
                  └──────────┬───────────┘
                             │
       ┌─────────────────────┼─────────────────────┐
       ▼                     ▼                     ▼
composable("movies")   composable("detail/{id}")  ...
```

### Key Components:
1. **`NavController`**: The central API that tracks the backstack of composables, handles transitions, and manages navigation actions. Built using `rememberNavController()`.
2. **`NavHost`**: Ties the `NavController` to a navigation graph where destinations are defined.
3. **`composable`**: Defines a destination in the graph with a unique string route.

---

## 2. Dynamic Route Argument Passing

In Android Compose, passing full complex objects through navigation routes is discouraged because:
- **State Integrity**: Routes are represented as URL-like paths. Serializing/deserializing large objects (like a movie entity) into path strings introduces runtime overhead and potential serialization errors.
- **Single Source of Truth**: Passing only a unique ID (e.g. `movie_id`) forces the target screen to fetch up-to-date data from its local database or cache, preventing stale UI.

### Route Syntax
Routes are defined similarly to REST endpoints:
```kotlin
composable(
    route = "detail/{movie_id}",
    arguments = listOf(
        navArgument("movie_id") { type = NavType.IntType }
    )
) {
    MovieDetailScreen(...)
}
```

---

## 3. Resolving Route Arguments with `SavedStateHandle`

When navigating to `"detail/42"`, Hilt and Jetpack Lifecycle inject a `SavedStateHandle` directly into the target ViewModel's constructor. This handle automatically contains the route arguments.

### Extraction Code
```kotlin
@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val repository: MovieRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    init {
        savedStateHandle.get<Int>("movie_id")?.let { id ->
            loadMovieDetails(id)
        }
    }
}
```
### Benefits:
- **Process Death Survival**: `SavedStateHandle` is integrated with Android's system-save-state mechanism, preserving arguments even if the system kills the app process in the background.

---

## 4. Cache-First Detail Loading (Instant UI)

To mirror premium iOS app performance, we implement a **cache-first loading strategy**:
1. Instantly retrieve basic movie metadata (title, poster, rating) from the local **Room Cache** using the `movie_id`. This populates the UI immediately (0ms wait time).
2. Concurrently fire a remote network request to TMDb to fetch heavy details (genres, runtime).
3. Update the UI state dynamically when the network request succeeds.

```
User Clicks Movie
      │
      ▼
Open Detail Screen
      │
      ├─► [Immediate] Query Room Cache ──► Render Title & Backdrop (0ms)
      │
      └─► [Async] Fetch TMDB Network ────► Append Genres & Runtime (Load Complete)
```

---

## 5. Input Debouncing for Search Queries

When implementing search, firing an API call on every keystroke causes rate-limiting issues, wastes user data, and leads to UI stuttering due to out-of-order responses.

We resolve this by applying a **500ms debounce** to the search query flow.

```
Keypresses:   [ S ] ──100ms──► [ Sp ] ──100ms──► [ Spid ] ──500ms (Idle) ──► API Request "Spid"
```

### Coroutines Implementation
We bridge the UI text field changes to a `MutableStateFlow` and collect it using the `debounce` operator:

```kotlin
private val _searchQueryFlow = MutableStateFlow("")

init {
    viewModelScope.launch {
        _searchQueryFlow
            .debounce(500)
            .distinctUntilChanged()
            .collect { query ->
                if (query.isEmpty()) {
                    showPopularMovies()
                } else {
                    executeSearch(query)
                }
            }
    }
}
```
- **`debounce(500)`**: Delays emissions until there is a 500ms pause in typing.
- **`distinctUntilChanged()`**: Prevents duplicated requests if the value did not change (e.g. typing a key and backspacing it immediately).
