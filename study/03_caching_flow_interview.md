# Deep-Dive Interview: Caching, Room & Flow

These target the *runtime semantics* of Room's invalidation tracker, transaction atomicity as it
relates to Flow emissions, and the correctness bugs hiding in this repo's pagination. Grounded in
`MovieDao`, `MovieRepositoryImpl`, and `MoviesViewModel.loadNextPage`.

---

### Q1: Inside `refreshPopularMovies`, the code does `clearAllMovies()` then `insertMovies()` *inside* `db.withTransaction { }`. A `Flow` from `SELECT * FROM movies` is being collected by the UI at the same time. Does the collector ever observe the intermediate **empty** list (a UI flash)? Explain precisely why or why not.

**What it probes:** Whether they understand Room's `InvalidationTracker` defers notifications until
commit — the *real* reason the transaction matters here (atomicity of observation, not just speed).

**Strong answer:** **No empty flash.** Room's `InvalidationTracker` doesn't fire per-statement; it
batches table-change notifications and only dispatches them **after the transaction commits**. So
the `DELETE` and the `INSERT` are observed as a single atomic invalidation — the collector
re-runs the query once and sees the final, populated list. Without the transaction, the `DELETE`
would commit on its own, the observer could re-query and emit `[]` (blank screen), then the
`INSERT` would emit the new list — a visible flicker. So the transaction's benefit here is
**observational atomicity**, not merely the disk-commit performance win.

**Follow-up probes:**
- *"So is the performance argument (one fsync) even the main point here?"* → No. For correctness
  with an observing `Flow`, the atomic-invalidation property is the headline. Performance is a
  bonus.
- *"What thread does the re-query run on after commit?"* → Room's transaction executor / query
  executor, not Main.

**Red flag:** "Transactions make it faster / are about ACID." True in general but misses that the
*observable* reason in this code is suppressing the intermediate empty emission.

---

### Q2: `getPopularMovies()` is `@Query("SELECT * FROM movies")` with no `ORDER BY`. The list endpoint returns movies in popularity order. Combined with `loadNextPage` calling `refreshPopularMovies(nextPage)` (which inserts with `OnConflictStrategy.REPLACE` and does *not* clear for page > 1), what ordering does the user actually see, and why is this a real bug?

**What it probes:** SQLite ordering guarantees, `REPLACE` semantics, and pagination correctness.

**Strong answer:**
- **`SELECT *` with no `ORDER BY` has no guaranteed order.** SQLite is free to return rows in any
  order (typically rowid/b-tree order, but that's an implementation detail you must not rely on).
  So the API's popularity ranking is *lost the moment it hits the cache*.
- With `REPLACE` on conflict: inserting page 2 keeps page-1 rows (no clear for page>1), but
  `REPLACE` is a **DELETE + INSERT** for any conflicting primary key — so a movie that appears on
  both pages gets deleted and re-inserted, **changing its rowid** and thus its physical position.
  Over several pages, the visual order drifts and can reshuffle.
- Net bug: pagination "works" (more items appear) but the order is non-deterministic and doesn't
  match TMDB's ranking — and items can jump around as pages load.

**Follow-up probes:**
- *"How do you fix ordering without an `ORDER BY` column that exists?"* → You need to persist the
  server rank: add a `position`/`page` column populated from the response index, and
  `ORDER BY position`. Otherwise there is no correct ordering to sort by.
- *"`REPLACE` vs `IGNORE` here — which is right?"* → For refresh you usually want `REPLACE` (data
  changes), but you must own ordering separately. Be aware `REPLACE` fires foreign-key cascades and
  resets rowids.

**Red flag:** "It returns them in insertion order." That's an assumption SQLite does not guarantee,
and `REPLACE` breaks it anyway.

---

### Q3: The DAO rule "Flow queries are not `suspend`, one-shot writes are `suspend`" is stated as dogma. *Why* can a `Flow` query not be `suspend`, and what would actually happen if you tried `suspend fun getPopularMovies(): Flow<List<MovieEntity>>`?

**What it probes:** Understanding the difference between a *cold observable stream* and a
*one-shot suspending call*.

**Strong answer:**
- A `Flow`-returning DAO method returns *immediately* with a cold stream object; nothing has run
  yet. Collection (and the actual query + observer registration) happens when you collect. There's
  nothing to suspend at the *return* — the suspension/threading is handled per-emission internally.
  Marking it `suspend` is contradictory: `suspend` means "this call does work and suspends until it
  produces its single result," but a `Flow` *is* the result and produces many values lazily.
- Room actually rejects `suspend fun ...: Flow<T>` at compile time (annotation processor error) —
  a `Flow` return type is inherently main-safe/asynchronous, so combining it with `suspend` is
  disallowed.
- One-shot writes (`@Insert`, `@Delete`) *do* the work eagerly and return once, so they're `suspend`
  to be main-safe.

**Follow-up probes:**
- *"`getMovieById` is `suspend` and returns `MovieEntity?` (a snapshot). When would you make it a
  `Flow` instead?"* → If the detail screen should live-update when the row changes (e.g. favorite
  toggled). It's a snapshot today because detail is cache-first-then-network, not reactive.

**Red flag:** "Because Room says so." No mechanism — they memorized the rule of thumb.

---

### Q4: `getPopularMovies()` is collected in `init` via `.launchIn(viewModelScope)`. If the `.map { it.toDomain() }` ever threw (say a mapping bug), what happens to the *entire* ViewModel, and why is `launchIn` riskier than it looks here?

**What it probes:** Structured concurrency + exception propagation in flow collection.

**Strong answer:** An exception in the flow pipeline (e.g. inside `map`) propagates up through the
collecting coroutine. With `.launchIn(viewModelScope)`, that coroutine is a child of
`viewModelScope`; an uncaught exception **cancels the scope**, which cancels *every other*
coroutine in the VM (the search collector, in-flight refresh, pagination) and effectively bricks
the screen — and there's no `catch` operator on this chain. The Room flow itself won't normally
throw, but the `map` transform is app code that can. A robust version wraps the upstream with a
`.catch { }` operator (which only catches *upstream* exceptions, not downstream collector errors)
and emits an error state instead of crashing the scope.

**Follow-up probes:**
- *"Does `.catch {}` catch an exception thrown in the `onEach` block?"* → No — `catch` only handles
  exceptions from *upstream* of where it's placed. An error in `onEach`/the collector is downstream;
  you'd need a try/catch there or restructure.
- *"How does this differ from the try/catch in `refreshMovies`?"* → `refreshMovies` launches a
  separate child coroutine; its failure is caught locally. The `launchIn` collector has no guard.

**Red flag:** "viewModelScope cancels itself when cleared so it's safe." Conflates lifecycle
cancellation with exception-driven cancellation.

---

### Q5: `withTransaction` is a `suspend` function and you can call `api.getPopularMovies(page)` (a network call) before it. What happens if you accidentally move the network call *inside* `db.withTransaction { }`? Reason about lock duration and dispatcher.

**What it probes:** Transaction scope discipline and what a transaction actually holds.

**Strong answer:** `withTransaction` holds a database transaction (and a connection from Room's
limited pool) open for the duration of the lambda. If you put the *network* call inside it, you
hold the DB transaction open across an unbounded network round-trip — pinning a connection,
blocking other writers, and risking transaction timeouts/`SQLiteDatabaseLockedException` under
contention. The current code correctly does I/O-bound *network* work first, then opens a short
transaction only around the DB mutations. The principle: **transactions should wrap only DB work
and be as short as possible**; never await network or other slow suspensions inside one.

**Follow-up probes:**
- *"`withTransaction` is suspend — can you suspend inside it?"* → Yes, it's designed to allow
  suspending DB calls, and it pins everything to Room's transaction dispatcher so nested DAO calls
  use the same connection. But suspending on *non-DB* work (network) inside it is the anti-pattern.
- *"Why does Room need its own transaction dispatcher / connection pinning?"* → SQLite transactions
  are tied to a single connection/thread; Room pins the coroutine to that connection so all
  statements in the lambda run on the same transaction.

**Red flag:** "withTransaction is async so it doesn't matter where the network call goes." Ignores
lock-hold duration.

---

### Q6: Search results never touch Room. The detail screen is "cache-first": `getMovieFromCache(id)`. Construct the exact user flow where cache-first silently degrades to network-only, and explain the SSOT violation.

**What it probes:** Tracing cross-feature data consistency.

**Strong answer:** Flow: user searches "Dune" → `searchMovies` returns results held only in the
ViewModel's in-memory state, **not written to Room** → user taps a result that isn't in the popular
cache → detail VM calls `getMovieFromCache(id)` → returns `null` → no instant render; the screen
shows loading until the network detail call returns. So "cache-first" only works for movies that
happened to be in the popular list. This violates Single Source of Truth: the same movie has two
disjoint sources (in-memory search list vs. Room), and they never reconcile. If TMDB later updates
that movie's rating, the popular cache and the search snapshot disagree.

**Follow-up probes:**
- *"Minimal fix to restore SSOT?"* → Write search results (and detail responses) into Room and have
  every screen read from Room flows; the network only updates the DB. Then detail cache-first works
  uniformly and there's one source.
- *"Trade-off of caching search results?"* → Cache invalidation/staleness for transient queries;
  you might use a separate table or TTL so search junk doesn't pollute the popular list.

**Red flag:** "Cache-first always shows instantly." Only true for cached ids; misses the
search-result gap.
