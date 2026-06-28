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

---

### Q7: `refreshPopularMovies` calls `api.getPopularMovies(page)` from a coroutine launched on `viewModelScope` (which defaults to `Dispatchers.Main`). Why doesn't this block the UI thread, and what would wrapping it in `withContext(Dispatchers.IO)` actually do?

**What it probes:** Whether the candidate understands that suspend Retrofit calls don't block,
or just cargo-cults `withContext(Dispatchers.IO)` for all "async" work.

**Strong answer:**
- `api.getPopularMovies(page)` is a `suspend` function. Retrofit's suspend adapter wraps the OkHttp
  `Call` in `suspendCancellableCoroutine`, calls `enqueue()` asynchronously, then **returns
  `COROUTINE_SUSPENDED`** — that sentinel is what releases the Main thread. The stack unwinds; the
  thread is free. OkHttp's own dispatcher thread owns the in-flight request.
- So wrapping with `withContext(Dispatchers.IO)` would jump to an IO thread, immediately suspend
  on the network call (freeing *that* IO thread), wait, then jump back to Main. It adds two context
  switches for zero benefit — the UI is never blocked in either case.
- `withContext(Dispatchers.IO)` *is* needed for **truly blocking** calls: synchronous Java I/O
  (`BufferedReader.readLines()`), `Thread.sleep()`, or any API that blocks the calling thread
  rather than accepting a callback/continuation.
- The Room DAO writes (`clearAllMovies`, `insertMovies`) are also `suspend` and dispatch
  internally to Room's own executor — again, no manual `withContext` needed.

**Follow-up probes:**
- *"What's the difference between `Dispatchers.IO` and `Dispatchers.Default`?"* → IO is sized for
  blocking work (up to 64 threads, or `kotlinx.coroutines.io.parallelism` system property); Default
  is sized for CPU-bound work (one thread per CPU core). Both are background pools but tuned
  differently. Using IO for CPU-heavy sorting wastes threads; using Default for blocking I/O starves it.
- *"If a junior dev adds `withContext(Dispatchers.IO)` around the Retrofit call, does anything break?"*
  → No visible breakage, but it adds latency (two thread hops) and misleads readers into thinking the
  call *needs* it. It's a correctness-neutral but semantically wrong pattern.

**Red flag:** "You always need `withContext(Dispatchers.IO)` for network calls in a coroutine."
A very common cargo-cult — harmless for suspend Retrofit calls but shows they haven't traced *why*.

---

### Q8: `viewModelScope` is used across `MoviesViewModel` to launch `refreshMovies`, `loadNextPage`, and the search coroutine. If `loadNextPage` throws an uncaught exception, does it cancel the search coroutine? What internal `Job` type does `viewModelScope` use, and why?

**What it probes:** Structured concurrency internals — `Job` vs `SupervisorJob`, and whether
the candidate knows that `viewModelScope` isolates sibling failures.

**Strong answer:**
- `viewModelScope` is created internally by the AndroidX `ViewModel` extension with a
  **`SupervisorJob`** (not a plain `Job`). In a `SupervisorJob` hierarchy, a child's failure does
  **not** propagate upward to cancel the parent or siblings. So if `loadNextPage` throws and that
  exception isn't caught inside the `launch` block, only *that* coroutine fails — the search and
  refresh coroutines keep running.
- With a plain `Job`, any child exception propagates up to the parent, which cancels *all* other
  children — a single failure brings down the whole scope.
- However, "survives" doesn't mean "silent": the uncaught exception is still delivered to any
  installed `CoroutineExceptionHandler` on the scope, or rethrown on the Main thread if none
  exists (potentially crashing the app). `SupervisorJob` isolates scope-level cancellation, not
  unhandled crashes.

**Follow-up probes:**
- *"When would you deliberately create a scope with a plain `Job`?"* → When child operations are
  tightly coupled: if step 1 fails, steps 2 and 3 are meaningless. A multi-step upload pipeline
  where every stage depends on the previous one is a good case.
- *"What's the difference between a `SupervisorJob` scope and using `supervisorScope { }` inside
  a coroutine?"* → `supervisorScope` is a suspend function that creates a transient supervisor scope
  for the duration of its block; it re-throws the *block's own* exception to the caller but doesn't
  propagate children's failures to siblings. Good for parallel `async` calls where you want one
  failure to not kill the others.

**Red flag:** "`viewModelScope` uses a regular `Job` so one crash cancels everything." Wrong —
it uses `SupervisorJob`. Reveals the candidate has never read the Jetpack lifecycle source.

---

### Q11: What is a coroutine?

**What it probes:** Whether the candidate has a precise mental model or just repeats the
"lightweight thread" slogan without understanding *what makes them light* or *how suspension
actually works*.

**Strong answer:** A coroutine is a unit of execution whose progress can be **suspended and
resumed** without blocking the underlying thread. When a coroutine hits a suspension point
(a `suspend` function that returns `COROUTINE_SUSPENDED`), the Kotlin runtime saves the
call frame — locals, program counter, everything — into a heap-allocated `Continuation` object,
then **returns the thread** to its pool. No thread is parked; no OS context switch happens. When
the awaited work completes (a network response arrives, a timer fires), the `Continuation` is
dispatched back onto the appropriate thread and execution resumes from the exact same point.

This is why coroutines are lightweight compared to threads:
- A **thread** is an OS resource: ~1 MB stack, kernel-managed scheduling, expensive context
  switches. Blocking a thread (e.g. `Thread.sleep()`) truly parks it — the OS can't use it.
- A **coroutine** is a Kotlin object. Suspending it costs a heap allocation (the `Continuation`)
  and a few hundred bytes. You can have millions active simultaneously with no OS overhead.

Three pieces make it work in practice:
1. **`suspend` keyword** — marks a function that may suspend. The compiler rewrites it into a
   state machine where each suspension point is a label; locals become fields on the generated class.
2. **`CoroutineScope`** — the structured container that ties coroutine lifetimes to a lifecycle
   (e.g. `viewModelScope`). When the scope is cancelled, all children are cancelled too.
3. **`Dispatcher`** — decides *which thread(s)* run the coroutine after each resumption
   (`Dispatchers.Main`, `Dispatchers.IO`, `Dispatchers.Default`).

**Follow-up probes:**
- *"If coroutines don't block threads, how does `delay(1000)` work?"* → `delay` is a suspend
  function. It schedules a resumption after 1 second via a timer and immediately returns
  `COROUTINE_SUSPENDED`, freeing the thread. No thread sleeps.
- *"What's the difference between concurrency and parallelism in the context of coroutines?"* →
  Coroutines on `Dispatchers.Main` achieve *concurrency* (interleaved progress, single thread) but
  not *parallelism*. `Dispatchers.Default` can achieve *parallelism* by running coroutines on
  multiple CPU cores simultaneously.
- *"So is a coroutine a thread?"* → No. A thread is an OS concept; a coroutine is a Kotlin runtime
  concept. One thread can run thousands of coroutines by switching between their `Continuation`s.

**Red flag:** "A coroutine is a lightweight thread." Technically the docs say this, but stopping
there shows the candidate has no model of *why* — no mention of suspension, `Continuation`, or
the fact that no thread is blocked. A deeper candidate explains the mechanism, not the tagline.

---

### Q9: Every `viewModelScope.launch {}` block in `MoviesViewModel` and `MovieDetailViewModel` uses `catch (e: Exception)`. Why is swallowing `CancellationException` here a structural correctness bug, and what is the minimal fix?

**What it probes:** Understanding that `CancellationException` is the cooperative cancellation
*signal* — swallowing it breaks structured concurrency without throwing or crashing.

**Strong answer:**
- Kotlin coroutines use `CancellationException` as the mechanism for cooperative cancellation.
  When a coroutine's `Job` is cancelled (e.g. `viewModelScope` cleared on ViewModel death), the
  *next suspension point* inside the coroutine throws `CancellationException`. If `catch (e: Exception)`
  catches it and doesn't re-throw, the coroutine **continues executing** past cancellation — it
  escapes the structured cancellation contract.
- Consequences: the coroutine may update `_state` after the ViewModel is cleared, hold resources
  open that should be released, or run through expensive DB/network work that the system already
  decided is no longer needed. No crash, no log — it just silently misbehaves.
- **Minimal fix:** re-throw `CancellationException` before handling other errors:
  ```kotlin
  } catch (e: Exception) {
      if (e is CancellationException) throw e
      _state.update { it.copy(error = e.message) }
  }
  ```
  Or replace `catch (e: Exception)` with typed catches (`catch (e: IOException)`,
  `catch (e: HttpException)`) that structurally cannot match `CancellationException`.

**Follow-up probes:**
- *"Does `runCatching { }` have the same problem?"* → Yes — `runCatching` wraps everything in
  `Result.failure`, so `CancellationException` gets stored instead of re-thrown. You need
  `.onFailure { if (it is CancellationException) throw it }` after `runCatching`.
- *"What about `catch (e: Throwable)`?"* → Same issue: `CancellationException` is a `Throwable`,
  so it's caught and swallowed there too. Always re-throw it.
- *"Is there a helper for this?"* → `ensureActive()` (from `CoroutineScope` or `currentCoroutineContext().ensureActive()`)
  throws `CancellationException` if the coroutine is already cancelled — useful to call at the
  start of a catch block or in a long computation loop.

**Red flag ⚠️:** "It's fine — `catch (Exception)` is the standard pattern for error handling."
This is a latent bug in the real codebase. The candidate who spots it without prompting has a
meaningfully deeper model of coroutines than one who doesn't.

---

### Q10: `loadNextPage` currently runs `refreshPopularMovies(nextPage)` sequentially. Suppose we wanted to prefetch the next two pages in parallel. Why is `async`/`await` preferable to launching two `launch` coroutines, and what scope should the `async` blocks live in?

**What it probes:** Concurrency primitives — `launch` vs `async`, result propagation, and the
critical distinction between `coroutineScope` vs `viewModelScope` for structured parallel work.

**Strong answer:**
- `launch` returns a `Job` with no result. To run two calls in parallel and wait for both, you'd
  `join()` both jobs — but if one throws, the exception is **not** re-thrown at the `join()` site;
  it goes to the scope's uncaught handler. You lose the clean "fail fast, re-throw" behavior.
- `async` returns a `Deferred<T>`; calling `.await()` suspends until the result is ready **and
  re-throws any exception** that occurred inside. Failure surfaces exactly where you handle it:
  ```kotlin
  coroutineScope {
      val d1 = async { repository.refreshPopularMovies(page) }
      val d2 = async { repository.refreshPopularMovies(page + 1) }
      d1.await()
      d2.await()
  }
  ```
- **Scope**: both `async` blocks must be children of a transient `coroutineScope { }` (or
  `supervisorScope { }`), **not** launched directly on `viewModelScope`. That way they're tied to
  the enclosing suspend function's lifetime — if `loadNextPage` is cancelled, both fetches cancel.
  Launching on `viewModelScope` directly would make them outlive the calling function's
  cancellation.
- `coroutineScope` vs `supervisorScope` for the two `async` calls: `coroutineScope` cancels the
  other async if one fails (fail-fast, sensible for page prefetch). `supervisorScope` keeps both
  running independently (useful if partial results are still worth showing).

**Follow-up probes:**
- *"`coroutineScope { }` is itself a `suspend` function. What does it do to the parent coroutine
  while the two `async` blocks run?"* → The parent suspends until *all* children inside
  `coroutineScope` complete (or one fails). It's the structured "barrier" — the parent can't
  proceed past `coroutineScope { }` until both fetches are done.
- *"Could you call `d2.await()` before `d1.await()` without losing parallelism?"* → Yes —
  both `async` blocks start immediately when launched; `.await()` just collects the result. Order
  of awaiting doesn't affect when the work runs. But if `d1` threw and you check `d2.await()`
  first, you'd get `d2`'s result before seeing `d1`'s exception. Usually you'd `awaitAll(d1, d2)`.

**Red flag:** "Just `launch` both on `viewModelScope` — they run in parallel." Technically
parallel, but misses exception re-throw semantics, result collection, and scope discipline.
