# Study Guide 02: Architecture & Dependency Injection (Hilt)

This guide covers architectural separation of concerns, MVVM, Clean Architecture, and Dependency Injection (DI) with **Dagger Hilt** in Android applications.

---

## 1. Clean Architecture Layers

Clean Architecture divides the project into independent layers: **Domain**, **Data**, and **Presentation**. This promotes code testability, maintainability, and scalability.

```
       ┌────────────────────────────────────────────────────────┐
       │                   PRESENTATION LAYER                   │
       │  (Compose Screens, ViewModels, UI State, Navigation)   │
       └───────────────────────────┬────────────────────────────┘
                                   │ (Depends on)
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │                      DOMAIN LAYER                      │
       │  (Business Logic, Domain Models, Repository Interfaces)│
       └───────────────────────────▲────────────────────────────┘
                                   │ (Implemented by / Depends on)
       ┌───────────────────────────┴────────────────────────────┘
       │                       DATA LAYER                       │
       │  (Room DB, Retrofit API, Repository Implementations)   │
       └────────────────────────────────────────────────────────┘
```

### Layer Details
1. **Domain Layer (Core)**:
   - Contains pure business logic.
   - Contains **Domain Models** (`Movie`) and **Repository Interfaces** (`MovieRepository`).
   - Does **not** know about databases, network clients, or UI frameworks.
   - Has zero dependencies on Android libraries (except Kotlin features).
2. **Data Layer**:
   - Implements the Repository interfaces defined in the Domain layer.
   - Coordinates caching (Room) and remote fetching (Retrofit).
   - Contains DTOs, DAOs, and remote API definitions.
3. **Presentation Layer**:
   - Manages UI state (`MoviesState`) and handles UI rendering (Jetpack Compose).
   - ViewModels (`MoviesViewModel`) react to user events and pull data from the Domain/Data repositories.

---

## 2. Dependency Injection (DI) Principles

### What is DI?
Dependency Injection is a pattern where an object receives its dependencies from external sources rather than creating them itself.

**Without DI (Bad)**:
```kotlin
class MovieRepositoryImpl {
    // Repository is tightly coupled to the concrete Retrofit instance
    private val api = Retrofit.Builder()...create(TmdbApi::class.java)
}
```
**With DI (Good)**:
```kotlin
class MovieRepositoryImpl(
    private val api: TmdbApi // Dependency is passed in (injected)
)
```

### Why use DI?
- **Testability**: We can easily pass a mock API client (`mock(TmdbApi::class)`) to test `MovieRepositoryImpl` without making real network calls.
- **Flexibility**: If we change the API implementation, we only change the DI configuration (module) rather than modifying all consumer classes.
- **Reusability**: Shared dependencies (like `OkHttpClient`, `RoomDatabase`) can be instantiated once (Singleton) and shared across the entire app.

---

## 3. Dependency Injection with Dagger Hilt

Hilt is Google's recommended library for DI on Android. Built on top of Dagger, it simplifies setup by providing pre-defined scopes and Android lifecycle integration.

### Core Hilt Components
1. **`@HiltAndroidApp`**:
   - Placed on the custom `Application` class.
   - Triggers Hilt's code generation and creates the top-level application container.
2. **`@AndroidEntryPoint`**:
   - Placed on Android components (like `MainActivity` or `Fragment`).
   - Enables Hilt to inject dependencies into these components.
3. **`@HiltViewModel`**:
   - Placed on `ViewModel` classes.
   - Configures the ViewModel to be retrieved from Hilt's factories, allowing you to constructor-inject repositories.
4. **`@Module` & `@InstallIn`**:
   - Placed on objects that define **how** to create dependencies (e.g. Retrofit, Room) that cannot be constructor-injected (like third-party libraries).
   - `@InstallIn(SingletonComponent::class)` dictates that these dependencies live as long as the application itself.

### AppModule Implementation
Our `AppModule.kt` defines how to construct OkHttp, Retrofit, and the Repositories:
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor())
            .build()
    }

    @Provides
    @Singleton
    fun provideTmdbApi(okHttpClient: OkHttpClient): TmdbApi {
        return Retrofit.Builder()
            .baseUrl(TmdbApi.BASE_URL)
            .client(okHttpClient)
            .build()
            .create(TmdbApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMovieRepository(api: TmdbApi): MovieRepository {
        return MovieRepositoryImpl(api)
    }
}
```

---

## 4. UI State Management (ViewModel + Flow)

We use **Unidirectional Data Flow (UDF)** to manage state:
1. The View observes a read-only StateFlow (`state: StateFlow<MoviesState>`).
2. The View renders itself based on the state.
3. When user actions occur, they are forwarded to the ViewModel as function calls (or UIEvents).
4. The ViewModel updates the state, triggering Compose recomposition.

### View State Pattern
```kotlin
data class MoviesState(
    val isLoading: Boolean = false,
    val movies: List<Movie> = emptyList(),
    val error: String? = null
)
```
Using a single State class guarantees that the UI can never represent an invalid combination of states (e.g., loading and displaying errors simultaneously).
