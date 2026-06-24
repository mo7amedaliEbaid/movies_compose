# Study Guide 04: Jetpack Compose UI & State Management

This guide covers the core concepts of **Jetpack Compose**, **State Management**, **Recomposition**, and building fluid, modern user interfaces in Android.

---

## 1. Unidirectional Data Flow (UDF) in Compose

Jetpack Compose is built on the concept of **Unidirectional Data Flow (UDF)**. In this pattern:
1. **State** flows down from the ViewModel to Composable screens.
2. **Events** (clicks, inputs, refreshes) flow up from the Composable screens to the ViewModel.

```
       ┌────────────────────────────────────────────────────────┐
       │                       VIEWMODEL                        │
       │  (Holds StateFlow<MoviesState>, processes events)      │
       └───────────────────┬───────────────────▲────────────────┘
                           │                   │
                     State │ flows             │ Events (e.g. click)
                     down  │                   │ flow up
                           ▼                   │
       ┌───────────────────────────────────────┴────────────────┐
       │                   COMPOSABLE SCREEN                    │
       │  (Collects State Flow, renders UI, triggers events)    │
       └────────────────────────────────────────────────────────┘
```

### Benefits of UDF
- **Testability**: The UI is a pure function of its state. You can test the screen simply by passing it mock states.
- **De-coupling**: UI code doesn't manage state directly, preventing race conditions or inconsistent views.

---

## 2. State Collection: `collectAsState` vs. `collectAsStateWithLifecycle`

When observing Kotlin `Flow` in Compose, we convert the hot flow to Compose `State` so that Compose can react to emissions and trigger recompositions.

### `collectAsState()`
- Converts a flow into a Compose `State`.
- It keeps collecting from the flow even when the hosting Activity/Fragment goes into the background (e.g., when the user opens another app). This keeps wasting CPU and memory resources.

### `collectAsStateWithLifecycle()` (Recommended Best Practice)
- Exists in the `androidx.lifecycle:lifecycle-runtime-compose` library.
- It stops collecting when the app is in the background (goes below the `STARTED` lifecycle stage) and automatically resumes when the app returns to the foreground.
- Saves system resources and battery life.

---

## 3. Recomposition & The Three Phases of Compose

Compose builds and updates the UI in three distinct phases:

```
1. COMPOSITION ────► 2. LAYOUT ────► 3. DRAWING
(What to show)       (Where to place) (How to paint)
```

1. **Composition**: Runs your Composable functions to build the node tree representing the UI structure.
2. **Layout**: Measures and positions each node in 2D coordinates.
3. **Drawing**: Paints the pixels onto the screen canvas.

### Recomposition
Recomposition is the process of re-executing Composable functions when their inputs (state/parameters) change. 
- Compose is intelligent: it only re-executes Composable functions that depend on modified inputs, skipping unchanged elements to optimize performance.

---

## 4. Key Composable Elements Used

### `LazyVerticalGrid`
Instead of drawing all movie items (which would crash the app for large datasets), we use a lazy layout that only instantiates widgets currently visible on screen.
```kotlin
LazyVerticalGrid(
    columns = GridCells.Fixed(2),
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp)
) {
    items(state.movies) { movie ->
        MovieCard(movie = movie)
    }
}
```

### `Card` & `Box` Layout overlays
We use a `Box` to stack the poster image, a vertical gradient overlay (to darken the bottom), and text views on top of each other, providing a clean movie card aesthetic:
```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    AsyncImage(...) // Bottom layer
    Box(modifier = Modifier.fillMaxSize().background(Gradient)) // Middle overlay layer
    Column(...) // Top info content layer
}
```
