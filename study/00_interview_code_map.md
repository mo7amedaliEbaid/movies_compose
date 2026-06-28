# Deep-Dive Interview Q → Code Location Map

The interview files now probe *depth* (runtime behavior, trade-offs, edge cases, and real
bugs/limitations in this codebase) rather than definitions. This map points each question at the
code you should be able to reason about — and flags the questions that expose a genuine
**latent issue** in the repo (great signal for whether the interviewee actually understands).

Legend: ⚠️ = the question targets a real bug / limitation / smell in this code.

| Study File | Q | Topic | Where it lives in code |
|---|---|---|---|
| `01_networking_interview` | Q1 | CPS / Retrofit suspend resume + cancellation | `TmdbApi.kt`; `MovieRepositoryImpl` call sites |
| `01_networking_interview` | Q2 ⚠️ | Error taxonomy; `catch(Exception)` swallows `CancellationException` | `MoviesViewModel` / `MovieDetailViewModel` try-catch blocks |
| `01_networking_interview` | Q3 | `ignoreUnknownKeys` vs `coerceInputValues` vs defaults; `MissingFieldException` | `MovieDto.kt`; `AppModule.provideTmdbApi` `Json {}` |
| `01_networking_interview` | Q4 ⚠️ | Bearer header vs query key; BODY logging leaks token | `AppModule.provideOkHttpClient` interceptors |
| `01_networking_interview` | Q5 ⚠️ | Lossy DTO→Entity mapping (runtime/genres dropped) | `MovieRepositoryImpl.refreshPopularMovies`, `MovieEntity.kt` |
| `01_networking_interview` | Q6 ⚠️ | `total_pages` discarded by pagination | `MovieResponse.kt`; `MoviesViewModel.loadNextPage` |
| `02_di_architecture_interview` | Q1 | DIP arrow direction; where mappers belong | `MovieRepository.kt`, `MovieDto.toDomain()` |
| `02_di_architecture_interview` | Q2 ⚠️ | Which singletons matter; DB-must-be-singleton invalidation | `AppModule.kt` all `@Provides @Singleton` |
| `02_di_architecture_interview` | Q3 | `@Provides` vs `@Binds` mechanics & constraints | `AppModule.kt` module header |
| `02_di_architecture_interview` | Q4 | `@HiltViewModel` codegen; `SavedStateHandle` as assisted/runtime dep | `MovieDetailViewModel.kt`, `MainActivity` `hiltViewModel()` |
| `02_di_architecture_interview` | Q5 | `MutableStateFlow` vs `stateIn(WhileSubscribed)`; conflation hurts events | `MoviesViewModel` state declaration & `init` |
| `02_di_architecture_interview` | Q6 ⚠️ | Sourcing policy leaking into ViewModel; SSOT inconsistency | `MoviesViewModel` popular-vs-search logic |
| `03_caching_flow_interview` | Q1 | `withTransaction` → atomic invalidation (no empty flash) | `MovieRepositoryImpl.refreshPopularMovies` `db.withTransaction` |
| `03_caching_flow_interview` | Q2 ⚠️ | No `ORDER BY` + `REPLACE` → non-deterministic order | `MovieDao.getPopularMovies`, `loadNextPage` |
| `03_caching_flow_interview` | Q3 | Why Flow queries can't be `suspend` | `MovieDao.kt` |
| `03_caching_flow_interview` | Q4 ⚠️ | `launchIn` collector has no `catch`; exception cancels whole scope | `MoviesViewModel.init` `.onEach{}.launchIn()` |
| `03_caching_flow_interview` | Q5 | Keep transactions short; don't await network inside | `MovieRepositoryImpl.refreshPopularMovies` |
| `03_caching_flow_interview` | Q6 ⚠️ | Search results never cached → cache-first degrades | `MovieRepositoryImpl.searchMovies`, `getMovieFromCache` |
| `03_caching_flow_interview` | Q7 | Why Retrofit suspend calls don't need `withContext(Dispatchers.IO)` | `MovieRepositoryImpl.refreshPopularMovies`, `TmdbApi.kt` |
| `03_caching_flow_interview` | Q8 | `SupervisorJob` in `viewModelScope`; sibling failure isolation | `MoviesViewModel` all `viewModelScope.launch` sites |
| `03_caching_flow_interview` | Q9 ⚠️ | `catch (Exception)` swallows `CancellationException`; breaks cooperative cancellation | `MoviesViewModel:113,136,180`, `MovieDetailViewModel:65` |
| `03_caching_flow_interview` | Q10 | `async`/`await` vs `launch` for parallel page prefetch; `coroutineScope` scoping | `MoviesViewModel.loadNextPage` |
| `03_caching_flow_interview` | Q11 | What is a coroutine? suspension, `Continuation`, scope, dispatcher | Conceptual — no single file |
| `04_compose_state_interview` | Q1 ⚠️ | `collectAsState` vs `collectAsStateWithLifecycle` | `MoviesScreen` `viewModel.state.collectAsState()` |
| `04_compose_state_interview` | Q2 ⚠️ | `Movie` unstable via `List<Genre>?` → `MovieCard` never skips | `Movie.kt`, `MovieCard` in `MoviesScreen.kt` |
| `04_compose_state_interview` | Q3 | `derivedStateOf` frequency mismatch; `snapshotFlow` alt | `MoviesScreen` `shouldLoadNextPage` |
| `04_compose_state_interview` | Q4 | `LaunchedEffect`/`DisposableEffect`/`SideEffect` semantics | `MoviesScreen` `LaunchedEffect(shouldLoadNextPage)` |
| `04_compose_state_interview` | Q5 | `remember` vs `rememberSaveable` persistence boundaries | `MoviesScreen` `rememberLazyGridState`, `remember{derivedStateOf}` |
| `04_compose_state_interview` | Q6 | Compose phases; defer state reads to layout/draw | `MovieCard` `Brush.verticalGradient` |
| `05_navigation_interview` | Q1 ⚠️ | id-only routing vs uncached search results | `MainActivity` `navigate("detail/${movie.id}")` |
| `05_navigation_interview` | Q2 | Process-death restoration sequence; primitive vs object | `MovieDetailViewModel` `savedStateHandle.get<Int>` |
| `05_navigation_interview` | Q3 ⚠️ | Race: stale search response overwrites cleared state; need `flatMapLatest` | `MoviesViewModel` debounce/search/refresh coroutines |
| `05_navigation_interview` | Q4 ⚠️ | Detail network result never written back to Room | `MovieDetailViewModel.loadMovieDetails` |
| `05_navigation_interview` | Q5 ⚠️ | Split-brain pagination (popular vs search); never-ends bug | `MoviesViewModel.loadNextPage` |
| `05_navigation_interview` | Q6 | `MutableStateFlow` conflation + `debounce`; `distinctUntilChanged` adjacency | `MoviesViewModel` search flow chain |

---

## How to use these files

Each question is structured as:
- **What it probes** — the underlying competency being tested.
- **Strong answer** — what a deep, senior/staff-level response covers.
- **Follow-up probes** — how the interviewer drills deeper to find the ceiling.
- **Red flag** — the shallow/memorized answer that *sounds* right but reveals a thin model.

The ⚠️ questions are the highest-signal ones: they describe behavior the candidate can only
explain correctly if they truly understand the system, and several point at fixable issues in
this very codebase.
