# Deep-Dive Interview: Architecture & Dependency Injection

These go past "what is the D in SOLID" into *why the dependency arrow points where it does*,
what Hilt actually generates, and the concurrency semantics of the state primitives. Grounded
in this repo's `AppModule`, `MovieRepositoryImpl`, and the two ViewModels.

---

### Q1: Dependency Inversion is supposedly satisfied here because `MovieRepository` lives in `domain` and `MovieRepositoryImpl` lives in `data`. But `MovieDto.toDomain()` sits in the data layer and *imports* `domain.model.Movie`. Is the dependency arrow still correct? Where do mappers belong and why?

**What it probes:** Whether they understand DIP as a *direction-of-source-dependency* rule, not a
folder-naming convention.

**Strong answer:**
- DIP says source dependencies point *inward*: data → domain, never domain → data. `MovieDto`
  importing `Movie` is **correct** — the outer (data) layer depends on the inner (domain)
  abstraction/model. The arrow is fine.
- Mappers belong in the layer that owns the "foreign" type, i.e. the data layer, because that's
  where the anti-corruption boundary is. Domain must stay free of `@Serializable`, Retrofit, and
  Room types. Putting `toDomain()` on the DTO keeps domain pure.
- The subtle point: domain *defines the interface* (`MovieRepository`), so at compile time the
  data module depends on domain, and at runtime Hilt injects the data implementation upward into
  the presentation layer. The inversion is the interface ownership, not the file location.

**Follow-up probes:**
- *"Could the domain layer accidentally depend on data?"* → Only if someone references
  `MovieRepositoryImpl`, `MovieEntity`, or a DTO from domain. The compiler won't stop you unless
  the module boundary is enforced by a separate Gradle module — here everything is one module, so
  the inversion is by *convention*, not *enforcement*. A staff-level answer recommends splitting
  into `:domain`, `:data`, `:app` modules so the build graph enforces DIP.

**Red flag:** "DTO imports domain, that violates inversion." Backwards — they've inverted the
inversion.

---

### Q2: Every `@Provides` in `AppModule` is `@Singleton`. Defend or attack scoping `provideMovieRepository` as `@Singleton`. Then: which of these singletons is actually *dangerous* to scope, and why?

**What it probes:** Understanding that scoping is about *lifetime & shared state*, not "make it fast."

**Strong answer:**
- `MovieRepositoryImpl` holds no mutable state (just an `api` and a `db` reference). Scoping it
  `@Singleton` is harmless but largely unnecessary — it could be unscoped and you'd just allocate
  a tiny stateless object per injection. Scoping it is defensible only to avoid re-allocating and
  to guarantee a single instance if you later add an in-memory cache.
- The genuinely *important* singletons are `OkHttpClient` and `MovieDatabase`. `OkHttpClient` owns
  a connection pool + thread pool + dispatcher; creating multiple defeats connection reuse and
  wastes sockets. `MovieDatabase` holds file handles and an in-process write lock — **multiple
  instances pointing at the same file is a correctness hazard** (separate invalidation trackers,
  potential `SQLiteDatabaseLockedException`). These *must* be singletons.
- So: scope the expensive/stateful things; the repository is the least important one to scope.

**Follow-up probes:**
- *"What breaks if `MovieDatabase` were unscoped?"* → Two `RoomDatabase` instances → two
  `InvalidationTracker`s → a write through instance A won't notify a `Flow` collected from
  instance B. The UI silently stops updating. This is the canonical "why DB must be singleton."
- *"Is over-scoping ever harmful?"* → Yes: an unnecessarily scoped object that holds a `Context`
  or a coroutine scope can leak for the app's lifetime.

**Red flag:** "Singleton is faster so always use it." Misses that the real reason is shared state /
single connection, and that over-scoping can leak.

---

### Q3: This module is an `object` using `@Provides`. Could you convert `provideMovieRepository` to `@Binds`? Walk through what would have to change, and explain the actual code-gen difference between `@Provides` and `@Binds` — not just "binds is more efficient."

**What it probes:** Whether they know the *mechanical* difference and the constraints.

**Strong answer:**
- To `@Binds` the repository: `MovieRepositoryImpl` would need an `@Inject constructor(api, db)`
  (it has a normal constructor now), and the binding method would be `abstract`, which means the
  module can no longer be an `object` — `@Binds` must live in an `abstract class` or `interface`.
  You'd typically split into two modules: an `abstract class` for `@Binds` and an `object` for the
  `@Provides` (OkHttp/Retrofit/Room), because you can't mix abstract `@Binds` and concrete
  `@Provides` static methods in the same `object`.
- Mechanical difference: `@Provides` generates a `Factory` whose `get()` *calls your method body*
  (real instantiation, with its own allocation). `@Binds` generates **no factory body** — Dagger
  just records "when someone asks for `MovieRepository`, use the `MovieRepositoryImpl` binding it
  already knows how to create." It's a compile-time *aliasing* of one binding to another type, so
  there's zero runtime indirection and less generated code.
- But `@Binds` only works when you have a single implementation whose creation Dagger already
  owns (via `@Inject constructor`). You can't `@Binds` something you must *configure*
  (OkHttp/Retrofit/Json) — hence those stay `@Provides`.

**Follow-up probes:**
- *"Why can't `provideOkHttpClient` be `@Binds`?"* → There's no implementation class to bind; you
  build and configure the instance imperatively. `@Binds` has no body to run configuration in.

**Red flag:** "Binds is just the faster version of Provides." True-ish but misses the
abstract-class requirement and that Binds *can't configure*.

---

### Q4: When you write `hiltViewModel()` in `MainActivity`'s NavHost, trace what Hilt generated so that `MovieDetailViewModel`'s constructor receives both a `MovieRepository` *and* a `SavedStateHandle`. Why is `SavedStateHandle` special?

**What it probes:** Understanding assisted/runtime injection and the ViewModel component boundary.

**Strong answer:**
- `@HiltViewModel` makes Hilt generate a `ViewModelComponent`-scoped factory and register it in a
  map keyed by ViewModel class. `hiltViewModel()` resolves the `HiltViewModelFactory`, which looks
  up that map and constructs the VM.
- `MovieRepository` is a *static* graph dependency resolved from the `SingletonComponent` (exposed
  down into the `ViewModelComponent`). `SavedStateHandle`, however, is **runtime data** that can't
  exist in the static graph — it depends on the specific navigation entry's arguments
  (`detail/{movie_id}`). Hilt provides it via the `SavedStateHandle` that the
  `AbstractSavedStateViewModelFactory` machinery creates from the `NavBackStackEntry`'s arguments
  + saved state. So it's effectively *assisted injection*: part graph-provided, part
  runtime-provided.
- That's why the nav arg "movie_id" magically appears in `savedStateHandle.get<Int>("movie_id")` —
  Navigation seeds the handle from the route arguments.

**Follow-up probes:**
- *"What's the lifecycle of this VM and when is `SavedStateHandle` cleared?"* → The VM is scoped to
  the `NavBackStackEntry`; it survives config changes, is cleared when that entry pops off the back
  stack. The handle survives process death (backed by the saved-state registry).
- *"Why not just pass the id through the constructor directly?"* → Hilt can't put runtime route
  args into the static graph; `SavedStateHandle` is the bridge, and it's the only piece that
  survives process death.

**Red flag:** "@HiltViewModel injects everything from the module." Doesn't distinguish static graph
deps from runtime SavedState.

---

### Q5: Both ViewModels expose state as `MutableStateFlow(...).asStateFlow()`. Compare this to the idiomatic `repository.getPopularMovies().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ...)`. What concrete behaviors differ, and when does `StateFlow`'s conflation actively hurt you?

**What it probes:** Hot-flow semantics, sharing strategies, and conflation pitfalls — well beyond
"StateFlow has an initial value."

**Strong answer:**
- **Current approach (`MutableStateFlow` + manual `onEach`/`update`):** the upstream Room flow is
  collected eagerly in `init` via `launchIn(viewModelScope)` and *stays* collected for the whole
  VM lifetime, even when no UI observer is present (e.g. screen backgrounded). It also imperatively
  merges DB emissions and search results into one `_state`.
- **`stateIn(WhileSubscribed(5000))`:** collection of the upstream is *driven by subscribers* — it
  starts when the UI subscribes and stops 5s after the last collector leaves, so it doesn't keep
  the Room query alive in the background. It also handles the initial value + sharing in one
  declarative line. The 5s grace avoids re-subscribing across config changes.
- **Where conflation hurts:** `StateFlow` only keeps the *latest* value and drops intermediates
  via `equals`. That's fine for UI state but **wrong for one-time events** (snackbars, navigation):
  if you modeled "show error toast" as a `StateFlow` field, a config-change re-collection would
  re-emit the latest state and fire the toast again; and two identical errors in a row would be
  conflated to one. Events need `SharedFlow`/`Channel`. Also, if `MoviesState` weren't a proper
  `data class` with structural `equals`, conflation would mis-fire.

**Follow-up probes:**
- *"Here, the Room flow is collected in `init` and never tied to subscription. What's the cost?"* →
  Background work: the SQLite query observer stays registered while the user is in another screen
  or app. `WhileSubscribed` would pause it.
- *"Why is `StateFlow` a poor event bus?"* → Conflation + replay-of-latest to new collectors →
  duplicate/lost events across lifecycle. Demonstrate with the config-change re-collection case.

**Red flag:** "StateFlow needs an initial value, SharedFlow doesn't." Surface-level; misses
subscription-driven sharing and the event-replay hazard.

---

### Q6: The presentation layer talks to `MovieRepository` (domain). But `MoviesViewModel` also encodes a policy: "popular movies stream from Room; search results are held in memory and never cached." Is that business logic in the right layer?

**What it probes:** Where orchestration/policy belongs; use cases vs. fat ViewModels.

**Strong answer:** This caching/sourcing policy is a *data* concern leaking into presentation. The
ViewModel currently decides when to read cache vs. network, merges pages, and treats search and
popular differently. That makes the VM hard to test and couples UI to storage strategy. A cleaner
design pushes "single source of truth + refresh" behind the repository (or a `GetMoviesUseCase` /
a `NetworkBoundResource`-style flow), so the VM just collects one stream of UI-ready data. The
domain could expose `observeMovies()` and `search()` that both go through Room, keeping SSOT
consistent (search results aren't cached today, which breaks SSOT for the detail screen).

**Follow-up probes:**
- *"Do you need use-case classes for an app this small?"* → Judgment: they add indirection; for a
  toy app, repository methods suffice. The smell isn't "no use cases," it's that *divergent
  sourcing policy* lives in the VM and isn't consistent (search bypasses the cache).

**Red flag:** "ViewModel calls repository, that's MVVM, it's correct." Doesn't notice the policy
leak or the SSOT inconsistency.
