# Deep-Dive Interview: Navigation, SavedState & Search

These probe process-death realism, the SSOT consequences of id-only routing, and a genuine
race condition in this repo's debounced search/refresh/pagination interplay. Grounded in
`MainActivity`, `MovieDetailViewModel`, and `MoviesViewModel`.

---

### Q1: The app passes `detail/${movie.id}` and the detail screen re-fetches. The justification is "Single Source of Truth." But search results are never cached. So for a searched movie, id-only routing forces a *network* fetch with a blank wait. Does the id-only argument still hold, and what's the honest trade-off?

**What it probes:** Whether they can defend a principle *and* see where this implementation
undermines it.

**Strong answer:**
- The id-only principle is correct in general: routes are URL-like, large payloads risk
  `TransactionTooLargeException` in the saved back-stack bundle, and a passed object is a stale
  snapshot that won't reflect later updates (favorite toggled, rating refreshed).
- But SSOT only pays off if there *is* a single fast local source. Here, search results bypass Room,
  so for a searched movie the "cache-first" read misses and you wait on the network anyway — the
  benefit of id-only routing (instant local render) is lost for that path.
- Honest trade-off: id-only is still the right call architecturally, but it *requires* the data
  layer to actually cache what the user can navigate to. The fix is data-side (cache search
  results / detail responses), not reverting to passing objects.

**Follow-up probes:**
- *"When *would* passing a small parcelable be acceptable?"* → For data you already have and that's
  cheap/immutable, to render an instant placeholder while the id-based fetch refreshes — a
  "pass-a-skeleton, fetch-the-truth" hybrid. Type-safe Navigation (Kotlin DSL with
  `@Serializable` routes) makes this safer than string routes.
- *"Why is a passed object 'stale' a real problem?"* → Two screens, one source of truth; an object
  snapshot diverges from the DB after any update.

**Red flag:** "Always pass the id, never the object." Dogma without noticing the search-path
regression it causes here.

---

### Q2: `savedStateHandle.get<Int>("movie_id")` is read in `init`. Simulate Android killing the process while the user is on the detail screen. Walk the *exact* restoration sequence and explain why this line still works — and what would break if `movie_id` were instead a complex object you'd stuffed into the handle.

**What it probes:** A concrete mental model of process death, not the textbook sentence.

**Strong answer:**
- Restoration sequence: system reclaims the process → user returns → Android recreates the task,
  `MainActivity`, and the Compose `NavHost` → Navigation restores the back stack from its saved
  state, including the `NavBackStackEntry` for `detail/{movie_id}` and its arguments → Hilt builds a
  *fresh* `MovieDetailViewModel`, and the `SavedStateHandle` is reconstructed from the saved-state
  registry, repopulated with `movie_id` (an `Int`, trivially `Bundle`-able) → `init` reads it and
  re-runs `loadMovieDetails`, re-querying cache then network. No crash, UI restored.
- Why `Int` is safe: `SavedStateHandle` is `Bundle`-backed; primitives serialize trivially. A
  complex object would need to be `Parcelable`/have a saver, would bloat the saved-state bundle
  (TransactionTooLarge risk), and could fail to restore if it referenced non-serializable state.
  That's exactly why you persist the *id* and rebuild the object from the repository.

**Follow-up probes:**
- *"`get<Int>` returns non-null here without a default — why is that safe?"* → The route declares
  `NavType.IntType` as a required arg, so Navigation guarantees it's present; still, the original
  guide's `?: throw` is defensive against misconfiguration.
- *"Does the Room cache survive process death?"* → Yes — it's on disk, so cache-first still renders
  instantly after restoration (for cached ids), which is the whole point of persisting locally
  rather than in the bundle.

**Red flag:** "SavedStateHandle survives process death." Restating the claim without the sequence
or the why-primitive distinction.

---

### Q3: In `MoviesViewModel.init`, three things run: the Room flow collector, the debounced search collector, and `refreshMovies()`. They all mutate the same `_state`. Construct a concrete interleaving where the user sees the *wrong* list. Then fix the race.

**What it probes:** Concurrency reasoning across multiple coroutines sharing one `StateFlow` — the
exact "out-of-order response" hazard the study guide *claims* debounce solves but the code reintroduces.

**Strong answer:**
- Concrete race: user types "batman" → debounce fires `executeSearch("batman")` which launches a
  coroutine doing `searchMovies` (slow network). Meanwhile the user clears the field → the debounce
  collector's empty-branch sets `movies = cachedMovies` immediately. Now the slow "batman" response
  returns and overwrites `_state.movies` with batman results — **even though the query is now
  empty**. The screen shows search results for a cleared box. Symmetric races exist between
  `refreshMovies`, `executeSearch`, and `loadNextPage`, all launching independent coroutines that
  blindly `update { movies = ... }`.
- Why debounce doesn't save you: debounce limits *how often* you launch searches; it does **not**
  serialize or cancel in-flight requests, so a stale older response can still land after a newer
  state change.
- Fix: model search as a `flatMapLatest` over the query flow so a new query **cancels** the previous
  in-flight search (`_query.debounce(500).distinctUntilChanged().flatMapLatest { repo.search(it) }`),
  and/or tag each result with the query it answered and drop it if `state.searchQuery` no longer
  matches. `flatMapLatest` is the idiomatic cancellation-on-new-input operator.

**Follow-up probes:**
- *"Why is `flatMapLatest` better than launching a coroutine per query?"* → It guarantees only the
  latest upstream value has a live downstream; previous searches are cancelled, eliminating the
  out-of-order overwrite by construction.
- *"`onSearchQueryChanged` writes both `_state.searchQuery` and `_searchQueryFlow`. Any issue with
  two sources of the query?"* → Duplicated state can desync; better to derive one from the other.

**Red flag:** "Debounce prevents race conditions." The guide says so, but the code proves it
doesn't — catching that gap is the whole point.

---

### Q4: Detail loading reads cache, then calls `getMovieDetails(id)` and replaces state with the network result. But the network result is **never written back to Room**. Trace the consequences across two visits to the same detail screen, and to the list screen.

**What it probes:** Cache write-through discipline and consistency over time.

**Strong answer:**
- Visit 1: cache hit renders title/poster instantly; network fills in `runtime`/`genres`; state
  updated — but nothing persisted. Visit 2 (later): cache-first read *again* lacks runtime/genres
  (the entity can't even store them), so you re-render the summary and re-hit the network every
  time. The "cache-first" optimization never improves across visits for the rich fields.
- List screen: since detail responses aren't written back, any fresher data from the detail
  endpoint (e.g. updated `vote_average`) never propagates to the list cache — the list keeps showing
  the popular-endpoint values. Two views, diverging data.
- This compounds the SSOT issue: the detail network call is a read-through that forgets to write
  through.

**Follow-up probes:**
- *"Implement proper write-through."* → Add `runtime`/`genres` columns (migration) or a related
  table, upsert the detail response into Room, and have the detail screen *observe* the Room row as
  a `Flow` so it updates reactively and benefits next visit.
- *"What new concern does caching genres introduce?"* → Relational modeling (`@Relation`/junction
  table) or a serialized column; plus migration correctness.

**Red flag:** "Cache-first makes it fast." Doesn't notice it's fast *once* and never persists,
re-fetching forever.

---

### Q5: `loadNextPage` behaves completely differently for popular vs. search: popular calls `refreshPopularMovies(nextPage)` and returns `emptyList()` (trusting Room to stream), while search appends `newMovies` in memory. Critique this split-brain pagination. What invariant is fragile?

**What it probes:** Spotting two inconsistent code paths maintaining the "same" list.

**Strong answer:**
- Popular pagination is *cache-driven*: it writes the next page to Room and lets the `getPopularMovies()`
  flow re-emit the full list. Search pagination is *memory-driven*: it appends to `state.movies`
  directly. Two different mental models for one `movies` field.
- Fragile invariants:
  1. **Ordering** (see caching doc): popular relies on `SELECT *` with no `ORDER BY`, so appended
     pages aren't reliably ordered; search relies on append order, which is correct but different.
  2. **End-of-list**: popular hard-codes `hasReachedEnd = false` (never ends), so it paginates
     forever / past the real end; search uses `newMovies.isEmpty()`. Neither uses `total_pages`.
  3. **Switching modes**: when the user clears search, the empty-branch resets `movies = cachedMovies`
     and `currentPage = 1`, but a half-loaded search list / in-flight `loadNextPage` could clobber it
     (the Q3 race).
- A unified design: one paginated source (ideally Room-backed via Paging 3 / `RemoteMediator`), one
  ordering column, one end signal from `total_pages`.

**Follow-up probes:**
- *"Why does returning `emptyList()` for popular and then `movies = stateVal.movies` look like dead
  code?"* → Because the real update arrives via the Room flow; the returned list is intentionally
  ignored. It's confusing precisely because the two paths share variables but not logic.
- *"Would Paging 3 fix this?"* → Yes — `RemoteMediator` unifies network+DB pagination with proper
  load states and ordering, removing the split-brain.

**Red flag:** "It loads the next page." Doesn't see the two divergent code paths or the
never-ends bug for popular.

---

### Q6: `debounce(500)` requires `@OptIn(FlowPreview::class)`, and the query is held in a `MutableStateFlow`. Explain two subtle properties: (a) why `MutableStateFlow` conflation interacts with `debounce`, and (b) what `distinctUntilChanged()` actually compares and a case where it surprises you.

**What it probes:** Operator-level precision on a chain they wrote.

**Strong answer:**
- (a) `MutableStateFlow` is **conflated**: if the user types several characters faster than the
  collector processes them, intermediate values can be dropped and only the latest is observed.
  Combined with `debounce(500)`, that's usually desirable (you want the latest after a pause), but
  it means `debounce` isn't seeing every keystroke — it's seeing the conflated latest. With a
  `Channel`/`SharedFlow` you'd get every value, changing debounce behavior subtly.
- (b) `distinctUntilChanged()` compares **consecutive** emissions by `equals`. Surprise case: it
  only filters *adjacent* duplicates, not all repeats — "a" → "b" → "a" passes through twice
  because the repeats aren't adjacent. Also, since it's on the query `String`, typing then deleting
  back to the same string *within* the debounce window is collapsed (good), but it does **not**
  dedupe identical *results* — two different queries returning the same list still both fire.

**Follow-up probes:**
- *"`FlowPreview` — risk of shipping a preview API?"* → API could change across coroutines versions;
  acceptable for `debounce` (stable in practice) but worth pinning the coroutines version.
- *"Where should `debounce` sit relative to `distinctUntilChanged` and why?"* → Typically
  `distinctUntilChanged()` *after* `debounce` so you debounce raw input then drop the no-op repeats;
  ordering changes whether you debounce duplicates. Discuss the trade-off.

**Red flag:** "debounce waits 500ms, distinct removes duplicates." Correct headline, no awareness of
conflation or adjacent-only comparison.
