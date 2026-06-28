# Interview Q&A → Code Location Map

| Study File | Q | Where it landed |
|---|---|---|
| `01_networking_interview` | Q1 DTO separation | `MovieDto.kt` — `toDomain()` function |
| `01_networking_interview` | Q2 Retrofit suspend | `TmdbApi.kt` — interface declaration |
| `01_networking_interview` | Q3 Kotlinx vs Gson | `MovieDto.kt` — `@Serializable` annotation |
| `01_networking_interview` | Q4 API key security | `AppModule.kt` — `authInterceptor` |
| `01_networking_interview` | Q5 `ignoreUnknownKeys` | `AppModule.kt` — `Json { }` block |
| `02_di_architecture_interview` | Q1 Dependency Inversion | `MovieRepository.kt` — the domain interface |
| `02_di_architecture_interview` | Q2 `@Provides` vs `@Binds` | `AppModule.kt` — module header |
| `02_di_architecture_interview` | Q3 Hilt scopes | `AppModule.kt` — module header |
| `02_di_architecture_interview` | Q4 `@HiltViewModel` | `MoviesViewModel.kt` — class declaration |
| `02_di_architecture_interview` | Q5 StateFlow vs SharedFlow | `MoviesViewModel.kt` — class declaration |
| `03_caching_flow_interview` | Q1 Compile-time SQL validation | `MovieDao.kt` — DAO interface |
| `03_caching_flow_interview` | Q2 Room Flow behavior | `MovieDao.kt` — DAO interface |
| `03_caching_flow_interview` | Q3 `withTransaction` vs `runInTransaction` | `MovieRepositoryImpl.kt` — transaction block |
| `03_caching_flow_interview` | Q4 Migrations | `MovieDatabase.kt` — `@Database` annotation |
| `03_caching_flow_interview` | Q5 Resource leaks | `MoviesViewModel.kt` — `.launchIn(viewModelScope)` |
| `04_compose_state_interview` | Q1 Recomposition | `MoviesScreen.kt` — screen composable |
| `04_compose_state_interview` | Q2 `@Stable` vs `@Immutable` | `MoviesState.kt` — state data class |
| `04_compose_state_interview` | Q3 `remember` vs `rememberSaveable` | `MoviesScreen.kt` — `remember { }` block |
| `04_compose_state_interview` | Q4 `derivedStateOf` | `MoviesScreen.kt` — `derivedStateOf` block |
| `04_compose_state_interview` | Q5 `LaunchedEffect` / `SideEffect` / `DisposableEffect` | `MoviesScreen.kt` — `LaunchedEffect` |
| `05_navigation_interview` | Q1 No complex objects in routes | `MainActivity.kt` — `navigate("detail/${movie.id}")` |
| `05_navigation_interview` | Q2 `SavedStateHandle` & process death | `MovieDetailViewModel.kt` — `savedStateHandle.get<Int>` |
| `05_navigation_interview` | Q3 Debouncing with Flows | `MoviesViewModel.kt` — `.debounce(500)` chain |
| `05_navigation_interview` | Q4 Cache-First, Network-Second | `MovieDetailViewModel.kt` — `loadMovieDetails` |
