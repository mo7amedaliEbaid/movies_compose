# Project Study Roadmap — File Build Order

Files ordered exactly as you would write them when building the project from scratch.
Each phase depends on the previous one being complete.

---

## Phase 1 — Project Bootstrap

| # | File | Why this comes first |
|---|---|---|
| 1 | `MovieApplication.kt` | `@HiltAndroidApp` must be declared before any Hilt injection works — it is the root of the dependency graph |
| 2 | `ui/theme/Color.kt` | Defines the color palette — needed by Theme |
| 3 | `ui/theme/Type.kt` | Defines typography — needed by Theme |
| 4 | `ui/theme/Theme.kt` | Combines Color + Type into `MyApplicationTheme` used in `MainActivity` |

---

## Phase 2 — Domain Layer (pure Kotlin, zero Android/library dependencies)

> Build the domain layer first. It has no imports from Retrofit, Room, or Hilt.
> Every other layer will depend on it, never the other way around (Dependency Inversion).

| # | File | Why this order |
|---|---|---|
| 5 | `domain/model/Genre.kt` | Simplest model — no dependencies on anything |
| 6 | `domain/model/Movie.kt` | References `Genre`, so Genre must exist first |
| 7 | `domain/repository/MovieRepository.kt` | Defines the contract (interface) using `Movie`. Domain owns this interface — Data layer will implement it |

---

## Phase 3 — Data Layer: Remote (Network)

> Now that domain models exist, we can build DTOs that map TO them.

| # | File | Why this order |
|---|---|---|
| 8 | `data/model/GenreDto.kt` | DTO for Genre — calls `Genre()` so `Genre.kt` must exist first |
| 9 | `data/model/MovieDto.kt` | DTO for Movie — calls `Movie()` and `GenreDto.toDomain()`, so both must exist |
| 10 | `data/model/MovieResponse.kt` | Wrapper around `List<MovieDto>` — just a container for the paginated API response |
| 11 | `data/remote/TmdbApi.kt` | Retrofit interface — uses `MovieDto` and `MovieResponse` as return types |

---

## Phase 4 — Data Layer: Local (Room Database)

> With domain models in place, we can build the local cache layer.

| # | File | Why this order |
|---|---|---|
| 12 | `data/local/MovieEntity.kt` | Room `@Entity` — mirrors `Movie` domain model, has `toDomain()` mapping |
| 13 | `data/local/MovieDao.kt` | `@Dao` interface — operates on `MovieEntity`, returns `Flow<List<MovieEntity>>` |
| 14 | `data/local/MovieDatabase.kt` | `@Database` — registers `MovieEntity` and exposes `MovieDao`. Must come after both |

---

## Phase 5 — Data Layer: Repository Implementation

| # | File | Why this order |
|---|---|---|
| 15 | `data/repository/MovieRepositoryImpl.kt` | Implements `MovieRepository` (domain interface) using `TmdbApi` + `MovieDatabase`. Needs all previous data files to exist |

---

## Phase 6 — Dependency Injection

| # | File | Why this order |
|---|---|---|
| 16 | `di/AppModule.kt` | Wires OkHttp → Retrofit → `TmdbApi`, Room → `MovieDatabase`, and binds `MovieRepositoryImpl` to `MovieRepository`. Every object it provides must already be defined |

---

## Phase 7 — Presentation: Movies List Screen

| # | File | Why this order |
|---|---|---|
| 17 | `presentation/movies/MoviesState.kt` | Pure data class holding UI state — no Android/Compose dependency, define before ViewModel |
| 18 | `presentation/movies/MoviesViewModel.kt` | Consumes `MovieRepository`, emits `MoviesState` via `StateFlow`. Needs the state class and DI setup |
| 19 | `ui/components/Shimmer.kt` | Reusable Compose modifier — no ViewModel dependency, but used inside `MoviesScreen` |
| 20 | `presentation/movies/MoviesScreen.kt` | Composable UI — reads `MoviesState`, calls ViewModel methods, uses `Shimmer`. Needs all above |

---

## Phase 8 — Presentation: Movie Detail Screen

| # | File | Why this order |
|---|---|---|
| 21 | `presentation/detail/MovieDetailViewModel.kt` | Contains `MovieDetailState` + ViewModel — uses `SavedStateHandle` and `MovieRepository` |
| 22 | `presentation/detail/MovieDetailScreen.kt` | Composable UI for the detail page — reads `MovieDetailState` from the ViewModel |

---

## Phase 9 — Navigation (connects everything)

| # | File | Why this comes last |
|---|---|---|
| 23 | `MainActivity.kt` | Sets up `NavHost` with both screens. Must come last because it imports and connects all ViewModels, Screens, and the Theme |

---

## Summary at a Glance

```
Phase 1 — Bootstrap
  MovieApplication → Color → Type → Theme

Phase 2 — Domain (core contracts)
  Genre → Movie → MovieRepository (interface)

Phase 3 — Remote Data
  GenreDto → MovieDto → MovieResponse → TmdbApi

Phase 4 — Local Data
  MovieEntity → MovieDao → MovieDatabase

Phase 5 — Repository
  MovieRepositoryImpl

Phase 6 — DI
  AppModule

Phase 7 — List Screen
  MoviesState → MoviesViewModel → Shimmer → MoviesScreen

Phase 8 — Detail Screen
  MovieDetailViewModel → MovieDetailScreen

Phase 9 — Navigation
  MainActivity
```
