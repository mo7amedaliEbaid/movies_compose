# Android Technical Interview Prep: Compose UI & State

This guide lists key technical interview questions, concepts, and answers related to Jetpack Compose state handling, recomposition optimization, stability, and side effects.

---

### Q1: What is recomposition, and how does Compose optimize it?
**Answer:**
Recomposition is the execution of Composable functions again with new data. Compose optimizes this process by:
1. **Intelligent skipping**: Compose checks the inputs of Composable functions. If the inputs are stable and have not changed, Compose skips executing that Composable function and its children entirely.
2. **Positional Memoization**: Compose keeps track of where Composables are declared in the tree, caching their representations and only updating nodes whose backing values have changed.
3. **Execution context**: Composables can recompose independently of each other. Recomposition starts at the nearest Composable scope that reads the modified state, bypassing parent scopes.

---

### Q2: What is the difference between `@Stable` and `@Immutable` annotations in Jetpack Compose?
**Answer:**
Both annotations tell the Compose compiler how to treat objects during recomposition skipping analysis.
- **`@Immutable`**:
  - Represents a promise that the state of all properties will **never** change after construction.
  - All properties must be declared as `val` and be of immutable types.
  - Allows Compose to skip recomposition when this object is passed as a parameter if it equals the old instance.
- **`@Stable`**:
  - Represents a promise that the object can be mutable, but Compose will be notified when any public property changes (e.g., properties are backed by `MutableState`).
  - It promises that if two instances are equal, they will remain equal.
  - It allows Compose compiler to track changes and skip recompositions if properties have not changed.

**Common Gotcha**: Standard Kotlin `List` or `Map` collections are considered **unstable** by the Compose compiler because their underlying implementations (like `ArrayList`) are mutable. Passing `List<Movie>` to a Composable can cause that Composable to *never* skip recomposition. Wrapping it in Kotlin Immutable Collections or marking your wrapper model as `@Immutable` fixes this performance bottleneck.

---

### Q3: What is the difference between `remember` and `rememberSaveable`?
**Answer:**
- **`remember`**:
  - Caches value calculations inside the Composition tree.
  - Survives recomposition.
  - **Does not survive configuration changes** (like screen rotation) or **process death**, because the Composition tree is completely destroyed and recreated during these events.
- **`rememberSaveable`**:
  - Caches values inside Android's `Bundle` state mechanism.
  - **Survives configuration changes** (rotation) and **system-initiated process death**.
  - **Limitation**: The stored type must be compile-safe to be written to a `Bundle` (e.g., primitives, `Parcelable`, or custom serializers).

---

### Q4: When and why should you use `derivedStateOf`?
**Answer:**
`derivedStateOf` is used to create a Compose state that depends on other states, **specifically when the dependencies change frequently, but the output only changes occasionally**.
It acts as a buffer to avoid triggering unnecessary recompositions.

**Example scenario:**
Listening to scroll state in a `LazyColumn`. The scroll offset changes on every single pixel scrolled (100 times per second). If we check `lazyListState.firstVisibleItemIndex > 0` directly:
- The state would trigger recompositions on *every single pixel* of scrolling.
Using `derivedStateOf`:
```kotlin
val showScrollToTopButton by remember {
    derivedStateOf { lazyListState.firstVisibleItemIndex > 0 }
}
```
Here, Compose will only recompose when `showScrollToTopButton` transitions between `true` and `false`, ignoring all intermediate scroll pixel updates. This is a massive performance optimization.

---

### Q5: Explain the difference between `LaunchedEffect`, `SideEffect`, and `DisposableEffect`.
**Answer:**
These are side-effect APIs in Compose designed to run non-UI code safely respecting lifecycle:

1. **`LaunchedEffect`**:
   - Runs a suspending block when entered into the Composition.
   - Clears/cancels the coroutine job when it leaves the Composition, or when the `key` parameter changes.
   - Ideal for network requests, loading databases, or triggering animations on screen entry.
2. **`DisposableEffect`**:
   - Runs a non-suspending block on entry.
   - **Must** end with an `onDispose` block.
   - Clears/unregisters resources (like observers, listeners, sensors, or broadcast receivers) inside `onDispose` when it leaves the Composition or when the key changes.
3. **`SideEffect`**:
   - Runs on **every successful recomposition**.
   - Used to share Compose state with non-Compose components (e.g., updating a Google Analytics tracker or syncing properties with system views).
