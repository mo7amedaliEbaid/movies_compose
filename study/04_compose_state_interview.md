# Deep-Dive Interview: Compose UI & State

These probe the *recomposition skipping model*, stability inference, and the exact effect APIs —
using the real `MoviesScreen`, which contains a genuine stability problem and a `collectAsState`
vs. lifecycle inconsistency.

---

### Q1: `MoviesScreen` does `val state by viewModel.state.collectAsState()`. The study guide says to prefer `collectAsStateWithLifecycle()`. Beyond "saves battery," what *observable bug* can `collectAsState` cause here, and what's the cost model of switching?

**What it probes:** Lifecycle-awareness of collection, not just the buzzword.

**Strong answer:**
- `collectAsState` collects while the composition is active, which on Android continues *even when
  the app is backgrounded* (the composition isn't torn down on STOP). So the VM's `StateFlow` keeps
  being observed and recompositions can be scheduled for a screen the user can't see — wasted work,
  and in worse cases UI work tied to an invisible screen.
- `collectAsStateWithLifecycle` (from `lifecycle-runtime-compose`) ties collection to the
  `LifecycleOwner`, pausing below `STARTED` and resuming on foreground. The *observable* difference:
  if upstream emits expensive/transient values while backgrounded, `collectAsState` processes them;
  the lifecycle variant drops them and re-reads the latest on resume.
- Cost of switching: essentially free — add the dependency and swap the call. There's no downside
  for UI state.

**Follow-up probes:**
- *"Does conflation save you anyway?"* → Partly: `StateFlow` conflation means you only get the
  latest on resume regardless. But you still pay for recompositions scheduled while backgrounded
  with plain `collectAsState`. For event flows it matters much more.
- *"Why isn't this catastrophic in *this* app?"* → State is conflated and cheap; it's a smell, not a
  crash. Senior candidates calibrate severity.

**Red flag:** "They're the same, one just saves battery." Misses the background-recomposition angle.

---

### Q2: `MovieCard` receives a `Movie`. Look at `domain/model/Movie`: it has `val genres: List<Genre>? = null`. Will `MovieCard` ever be *skipped* during recomposition? Reason from Compose's stability inference rules.

**What it probes:** The single most misunderstood Compose perf topic — parameter stability.

**Strong answer:**
- For Compose to skip a composable, *all* its parameters must be **stable** and unchanged. `Movie`
  is a `data class` with all `val`s, but it contains `List<Genre>?`. The Compose compiler infers
  `List` as **unstable** (the interface's runtime impl, `ArrayList`, is mutable), which makes the
  whole `Movie` class **unstable**. An unstable parameter means `MovieCard` is **never skippable** —
  it recomposes whenever its parent recomposes, even if the same `Movie` is passed.
- So scrolling/refreshing can recompose every visible `MovieCard` unnecessarily.

**Follow-up probes:**
- *"Three ways to fix it."* → (1) Use `kotlinx.collections.immutable`'s `ImmutableList<Genre>` (the
  compiler treats it as stable); (2) annotate `Movie` with `@Immutable` (a *promise* you must
  honor — never mutate the list); (3) enable **strong skipping mode** (Compose Compiler 1.5.4+ /
  default in newer Kotlin) which treats even unstable params as skippable using instance equality —
  but that changes the equality semantics and isn't a license to ignore stability.
- *"Does `@Immutable` change runtime behavior?"* → No — it's a compiler hint; it lets the compiler
  *assume* stability. Lying (mutating the list) causes missed recompositions / stale UI.
- *"Is `MoviesState`'s `movies: List<Movie>` the same problem?"* → Yes, same unstable-`List` issue;
  but `MoviesScreen` reads many scalar fields of `state` anyway, so the screen-level composable
  recomposes regardless. The win is at the *item* level (`MovieCard`).

**Red flag:** "It's a data class so it's stable and skippable." The `List` (and nullable `List`)
makes it unstable — this is the trap.

---

### Q3: Explain why `shouldLoadNextPage` is wrapped in `derivedStateOf` *inside* `remember`. What recomposition storm does it prevent, and would `snapshotFlow` be a better tool here?

**What it probes:** The precise use case for `derivedStateOf` and the alternative.

**Strong answer:**
- `listState.layoutInfo` is backed by snapshot state and changes on **every frame of scrolling**
  (many times per second). If you computed `lastVisibleItem.index >= totalItems - 2` directly in
  the composable body, the reading composable would recompose on every scroll tick.
- `derivedStateOf` creates a derived snapshot state that **only notifies readers when the computed
  result changes** — here, only when `shouldLoadNextPage` flips `false→true`/`true→false`. So the
  high-frequency input is collapsed into a low-frequency boolean. `remember` keeps that derived
  state across recompositions so it isn't recreated each time.
- `snapshotFlow { ... }` is the alternative when you want to *react* with a coroutine (e.g.
  `collect { if (it) loadNextPage() }`) instead of feeding a `LaunchedEffect`. It's arguably
  cleaner here because the goal is a *side effect* (trigger loading), not a value the UI renders.
  Both work; `derivedStateOf` + `LaunchedEffect(boolean)` is fine.

**Follow-up probes:**
- *"`LaunchedEffect(shouldLoadNextPage)` keys on the boolean. While loading, the boolean stays
  true — does the effect re-run and double-fire?"* → No; the key is unchanged so the effect isn't
  relaunched. But there's a subtle hazard: if items load and the last-visible item is *still*
  within the threshold, the boolean never flips back to false, so the effect won't re-trigger for
  the next page — relying on `loadNextPage`'s internal guards. Discuss how a `snapshotFlow` +
  distinct + guard could be more robust.
- *"What if you forgot `remember`?"* → A fresh `derivedStateOf` each recomposition — it still
  works but you lose the caching/identity benefit and re-allocate.

**Red flag:** "derivedStateOf is for state that depends on other state." A definition, not the
*frequency-mismatch* rationale.

---

### Q4: Distinguish `LaunchedEffect`, `DisposableEffect`, and `SideEffect` by *when they run and when they clean up*. Then: this screen triggers pagination from a `LaunchedEffect`. Is that the right effect, and what's the risk of using the wrong one for a one-shot vs. repeating action?

**What it probes:** Effect API semantics and matching the tool to the lifecycle need.

**Strong answer:**
- `LaunchedEffect(key)`: launches a coroutine on first composition / when `key` changes; cancels the
  coroutine on leaving composition or key change. For suspendable work (network, animations,
  triggering `loadNextPage`).
- `DisposableEffect(key)`: runs non-suspending setup on enter; **must** return `onDispose {}` for
  cleanup on leave/key change. For registering & unregistering listeners/observers/sensors.
- `SideEffect`: runs after **every successful recomposition**; no cleanup. For publishing Compose
  state to non-Compose objects (analytics, legacy views).
- Pagination via `LaunchedEffect(shouldLoadNextPage)` is appropriate because the trigger is keyed
  on a state change and the work is suspending. Risk of mismatch: using `SideEffect` would fire on
  every recomposition (spamming `loadNextPage`); using `DisposableEffect` is wrong because the work
  is suspending and there's nothing to dispose.

**Follow-up probes:**
- *"You need to register a `BroadcastReceiver` for connectivity — which effect, and what's the
  failure if you use `LaunchedEffect`?"* → `DisposableEffect`; with `LaunchedEffect` you'd have no
  symmetric unregister, leaking the receiver.
- *"Effect with `Unit`/`true` key vs. a meaningful key?"* → `key = Unit` runs once for the
  composition's life; keying on changing data re-runs on change. Choosing the key *is* the design.

**Red flag:** Listing definitions but not catching that `SideEffect` would spam the action.

---

### Q5: `rememberLazyGridState()` survives configuration change but `remember { derivedStateOf {...} }` does not. Explain the persistence boundaries of `remember` vs `rememberSaveable`, and why the scroll position is preserved on rotation here even though `derivedStateOf` is recomputed.

**What it probes:** What actually survives recomposition vs. config change vs. process death, and
which APIs cross which boundary.

**Strong answer:**
- `remember` caches a value in the *composition*; it survives recomposition but is **lost** when the
  composition is destroyed (config change, process death) — because the whole composition tree is
  rebuilt.
- `rememberSaveable` additionally writes to the saved-instance-state `Bundle`, surviving config
  change and system-initiated process death (type must be `Parcelable`/primitive or have a `Saver`).
- `rememberLazyGridState()` is implemented with `rememberSaveable` + a custom `Saver`, so the
  *scroll index/offset* are persisted and restored after rotation. The `derivedStateOf` wrapped in
  plain `remember` is recomputed from scratch after rotation — but that's fine because it's *derived*
  from the restored `listState`, so the correct value is recomputed immediately.
- Net: you persist the *source of truth* (scroll position) and recompute everything derivable from
  it. You don't (and shouldn't) persist derived state.

**Follow-up probes:**
- *"Would you `rememberSaveable` the `shouldLoadNextPage` boolean?"* → No — it's cheap, derived, and
  recomputed instantly; persisting it is redundant and risks staleness.
- *"What about `searchQuery` — `remember` or hoisted to ViewModel?"* → It's in `MoviesState` in the
  VM, which survives config change; that's the right place, and it's restorable via SavedState.

**Red flag:** "rememberSaveable is just remember that survives rotation." Correct but can't explain
why scroll survives while the derived state is recomputed.

---

### Q6: Compose has three phases — composition, layout, draw. The dark gradient in `MovieCard` uses `Brush.verticalGradient(...)` built inside the composable on every recomposition. Which phase does that allocation hit, and how would you reason about whether it matters?

**What it probes:** Phase awareness and allocation discipline, beyond "Compose has 3 phases."

**Strong answer:**
- Constructing the `Brush` happens during **composition** (it's a normal object allocation in the
  composable body), and it feeds the `background` modifier which is applied at **draw**. Because
  `MovieCard` is unstable (Q2) and not skippable, this `Brush` is re-allocated every time the card
  recomposes.
- Whether it matters: a `Brush` is cheap and gradients are common, so it's minor — but the
  disciplined move is to hoist constant brushes/`remember` them so they're not re-allocated, and to
  fix the underlying skippability problem so the whole card stops recomposing needlessly. The deeper
  point: reads of state in the *composition* phase invalidate composition; some state (e.g. scroll
  offset) is better read in *layout/draw* via lambda-based modifiers (`Modifier.offset { }`,
  `drawBehind`) to skip composition entirely. That "defer the read to a later phase" technique is
  the real performance lever.

**Follow-up probes:**
- *"Give an example where reading state in draw instead of composition is a big win."* → Animating
  offset/alpha on scroll: using `Modifier.graphicsLayer { }` / `offset { }` reads the value in the
  layout/draw phase, so scrolling doesn't trigger recomposition at all.

**Red flag:** Reciting the three phases without connecting allocation/state-reads to a specific phase.
