# Deep-Dive Interview: Networking & Serialization

These questions are designed to separate someone who *memorized* the layers from
someone who actually understands the runtime behavior, failure modes, and trade-offs.
Every question is grounded in the real code in this repo. Each item lists **what it
probes**, a **strong answer**, **follow-up probes** to go deeper, and the **red flag**
shallow answer to listen for.

---

### Q1: Walk me through *exactly* what happens to a coroutine — frame by frame — when `api.getPopularMovies(page)` suspends. Who frees the thread, and what resumes it?

**What it probes:** Whether the candidate understands CPS transformation and Retrofit's
suspend adapter, or just parrots "coroutines are lightweight threads."

**Strong answer:**
- The Kotlin compiler rewrites the enclosing `suspend` function into a state machine. Each
  suspension point becomes a label; locals are hoisted into a generated `Continuation`
  object that captures the call frame.
- At the call site, Retrofit's suspend adapter wraps the OkHttp `Call` in
  `suspendCancellableCoroutine`. It calls `enqueue()` (async), then *returns* `COROUTINE_SUSPENDED`.
  Returning that sentinel is what actually releases the thread back to the dispatcher — the
  stack unwinds, the thread is free to run other work.
- No thread is blocked while the socket is waiting. OkHttp's own dispatcher thread owns the
  in-flight request.
- When the HTTP response arrives, OkHttp invokes the callback on its dispatcher thread, which
  calls `continuation.resume(body)` (or `resumeWithException`). That re-dispatches the
  continuation back onto the original coroutine's dispatcher (`viewModelScope` → `Dispatchers.Main.immediate`)
  to resume at the saved label.

**Follow-up probes:**
- *"So which thread does the JSON parsing happen on?"* → OkHttp's background thread (inside the
  converter), not Main. That's why no `withContext(Dispatchers.IO)` is needed.
- *"If I cancel `viewModelScope` mid-flight, does the socket connection get cancelled?"* → Yes —
  `suspendCancellableCoroutine` registers an `invokeOnCancellation` that calls `call.cancel()`.
  Cancellation is cooperative and propagates to OkHttp. A shallow candidate thinks cancellation
  only stops the coroutine but leaks the socket.

**Red flag:** "Retrofit runs it on a background thread with `withContext`." It does not — and not
knowing *who* resumes the continuation means they've memorized the slogan, not the mechanism.

---

### Q2: This app catches `catch (e: Exception)` in the ViewModel. A TMDB call can fail in at least three structurally different ways. Enumerate them and explain why a single generic catch is a design smell.

**What it probes:** Real-world error taxonomy vs. "wrap it in try/catch."

**Strong answer:** With a suspend Retrofit function that returns the body directly (not `Response<T>`):
1. **`IOException`** — no network, DNS failure, timeout, socket reset. *Retryable / show "offline".*
2. **`HttpException`** — a non-2xx response (401 bad key, 404 unknown movie, 429 rate-limited).
   *Not retryable by simple retry; 429 needs backoff, 401 is a config bug.*
3. **`SerializationException` / `MissingFieldException`** — the body parsed but didn't match the
   DTO contract. *A client bug or breaking API change — retrying will never help.*
4. (Plus `CancellationException`, which **must not** be swallowed — see follow-up.)

A blanket `catch (Exception)` collapses all of these into one "error" string, so the UI can't
distinguish "you're offline, retry" from "your API key is wrong" from "the app is broken." A
mature design maps exceptions to a sealed `DataError`/`Result` type at the repository boundary.

**Follow-up probes:**
- *"What's wrong with `catch (e: Exception)` specifically in a coroutine?"* → It catches
  `CancellationException` too. Swallowing it breaks structured concurrency: the coroutine keeps
  running its catch/finally as if alive, and the parent thinks the child completed normally. You
  should rethrow `CancellationException` (or catch the narrower types). **This repo's
  ViewModels have this exact bug.**
- *"Where should the mapping from exception → domain error live?"* → Data layer (repository), so
  the domain/presentation never import `retrofit2.HttpException`.

**Red flag:** "Exceptions are exceptions, the catch handles all of them." Misses cancellation and
the retryable-vs-fatal distinction.

---

### Q3: The DTO uses `coerceInputValues = true` *and* default values like `voteAverage: Double = 0.0`. Construct a JSON payload that still crashes deserialization despite both safety nets. Then explain the precise difference between what `ignoreUnknownKeys`, `coerceInputValues`, and defaults each protect against.

**What it probes:** Whether they understand these flags are orthogonal, not interchangeable.

**Strong answer:** A payload missing a **non-nullable field that has no default** — e.g. `title`
is `val title: String` with no default. If the JSON omits `"title"`, kotlinx throws
`MissingFieldException` regardless of the two flags. (Also: `"id": null` would fail because `id`
is non-null with no default and `coerceInputValues` only coerces to a *declared default*, which
`id` doesn't have.)

The three mechanisms cover **different** failures:
| Mechanism | Protects against | Does NOT help with |
|---|---|---|
| `ignoreUnknownKeys` | JSON has **extra** keys not in the DTO | Missing keys |
| Default value (`= 0.0`) | JSON **omits** that key | `null` for a non-null type |
| `coerceInputValues` | JSON sends **`null`** (or invalid enum) for a field **that has a default** → uses the default instead of crashing | Missing non-null field with no default |

So `coerceInputValues` is only meaningful *in combination with* a default. A field with no
default gets no protection from it.

**Follow-up probes:**
- *"`id` has no default. Is that intentional?"* → Yes — `id` is the primary key; you *want* a hard
  failure if it's missing rather than silently coercing to 0 and corrupting the cache.
- *"Why is making everything nullable-with-default a bad over-correction?"* → It pushes null
  handling into every UI call site and hides genuine contract breaks behind silent zeros.

**Red flag:** "Those flags make parsing never crash." Demonstrably false, and shows they treat
config as magic.

---

### Q4: This module authenticates with a `Bearer` token header in an OkHttp interceptor, but the study guide describes a query-param `api_key` in `BuildConfig`. Compare the two TMDB auth schemes, and critique putting the token in an interceptor vs. an `@Query` parameter.

**What it probes:** Reading the *actual* code, understanding interceptors, and security/observability trade-offs.

**Strong answer:**
- TMDB supports two schemes: a **v3 `api_key` query param** and a **v4 bearer access token** in
  the `Authorization` header. This app uses the v4 bearer header via `authInterceptor`.
- **Interceptor (header) advantages:** the secret is applied in exactly one place, never appears
  in the URL, doesn't leak into logs/analytics/`Referer`, and isn't cached by proxies keyed on URL.
- **`@Query` param disadvantages:** the key is in the URL → shows up in HTTP logs, server access
  logs, and crash reports; it also pollutes the cache key.
- **Critique of the interceptor here:** it's an *application* interceptor, so it runs once per
  call but won't re-apply on redirects handled at the network layer; fine for this. The bigger
  issue is ordering — `loggingInterceptor` is added *before* `authInterceptor`, so at
  `Level.BODY` the logger won't see the `Authorization` header (auth is added after logging in
  the chain)... actually it *will*, because both are application interceptors and the request is
  rebuilt; the candidate should reason about chain order and that **BODY-level logging will print
  the bearer token to Logcat in debug** — a real leak risk that should be gated behind
  `BuildConfig.DEBUG`.

**Follow-up probes:**
- *"Regardless of header vs query, the token is still compiled into the APK. Rank the real-world
  mitigations."* → Backend proxy (only true fix) > short-lived tokens fetched at runtime >
  NDK/obfuscation (raise the bar, not a fix). `local.properties` only keeps it out of git; it
  does nothing against decompilation.
- *"Why is `HttpLoggingInterceptor.Level.BODY` dangerous in production?"* → Logs full bodies +
  headers (including the token) to Logcat; should be `NONE` in release.

**Red flag:** "We hide the key in `local.properties` so it's secure." Conflates git hygiene with
runtime security.

---

### Q5: Trace a single `MovieDto` from the wire all the way to a `MovieEntity` row. Count the mappings, and identify the data that is silently *lost* on the way into the cache. Why is that a latent bug?

**What it probes:** End-to-end data-flow thinking and spotting lossy mappings.

**Strong answer:**
- Path: JSON → `MovieDto` (kotlinx) → in `MovieRepositoryImpl.refreshPopularMovies`, mapped
  *manually* into `MovieEntity` (a third, hand-written mapping that does **not** use `toDomain()`).
- `MovieDto` and `Movie` carry `runtime` and `genres`, but **`MovieEntity` has neither field**, so
  those are dropped when caching. The popular endpoint doesn't return them anyway, but
  `getMovieDetails` does.
- The latent bug: the detail screen is "cache-first" and reads `getMovieFromCache(id)?.toDomain()`,
  but the cache can *never* hold `runtime`/`genres` because the entity can't store them — and
  detail results are never written back to Room at all. So the cache is permanently a
  partial projection, and there's a third, drift-prone mapping (`Dto→Entity`) maintained by hand
  next to `Dto→Domain` and `Entity→Domain`.

**Follow-up probes:**
- *"How many representations of a movie exist and is that justified?"* → Four: `MovieDto`,
  `Movie`, `MovieEntity`, plus the UI reading `Movie`. DTO↔Domain separation is justified
  (anti-corruption layer); a separate `Entity` is justified *if* persistence needs differ — but
  here it's nearly identical and just creates a hand-maintained mapping that already diverged
  (missing runtime/genres). A `MovieEntity.fromDomain` exists but `refreshPopularMovies` ignores
  it and re-maps inline — so the mappings can drift.
- *"Fix?"* → Either store details in the entity (add columns + migration) and write detail
  responses back to cache, or be explicit that the list cache is a summary projection and the
  detail screen is network-only.

**Red flag:** "It maps DTO to domain, clean architecture, done." Doesn't notice the silent field
loss or the duplicated hand mapping.

---

### Q6: Why does this app need `MovieResponse` as a wrapper, and what does its existence tell you about pagination correctness elsewhere in the app?

**What it probes:** Connecting the envelope DTO to the pagination logic.

**Strong answer:** `MovieResponse` carries `page`, `total_pages`, `total_results` plus `results`.
The app currently determines "reached end" for *popular* movies by streaming from Room and never
sets `hasReachedEnd`, and for *search* by checking `results.isEmpty()`. But `total_pages` is the
authoritative signal — the app ignores it. So pagination "knows" the API exposes totals yet
relies on an emptiness heuristic, which fires one wasted request past the real end and can't
short-circuit. A deep candidate notices the envelope is under-used.

**Follow-up probes:**
- *"Where would you thread `total_pages` to fix `loadNextPage`?"* → Repository returns it (or a
  paged result type); ViewModel compares `currentPage >= totalPages`.

**Red flag:** "It's just the JSON structure." True but misses that the metadata is being discarded.
