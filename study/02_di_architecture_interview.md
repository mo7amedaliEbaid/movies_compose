# Android Technical Interview Prep: Architecture & DI

This guide covers typical technical interview questions and concepts regarding Clean Architecture, MVVM, Dependency Inversion, Dagger Hilt, and reactive state representation.

---

### Q1: What is Dependency Inversion, and how is it applied in Clean Architecture?
**Answer:**
Dependency Inversion is the "D" in SOLID. It states that:
1. High-level modules should not depend on low-level modules. Both should depend on abstractions.
2. Abstractions should not depend on details. Details should depend on abstractions.

**Application in our TMDb app:**
- The **Domain layer** contains the business logic and defines the `MovieRepository` interface (abstraction). This is high-level.
- The **Data layer** defines the `MovieRepositoryImpl` (detail) which implements the interface and depends on `TmdbApi` (Retrofit). This is low-level.
- Instead of the Domain layer depending on the Data layer, the Data layer depends on the Domain layer (it imports the interface). The dependency arrow is *inverted*!

**Key Talking Point for Interview:**
> *"By defining interfaces in the domain layer and implementing them in the data layer, we satisfy the Dependency Inversion Principle. The domain layer remains a pure-Kotlin core with zero dependencies on database libraries, network clients, or Android packages."*

---

### Q2: What is the difference between `@Provides` and `@Binds` in Dagger/Hilt?
**Answer:**

| Feature | `@Provides` | `@Binds` |
| :--- | :--- | :--- |
| **Method Body** | Contains actual instantiation logic. | Abstract method with no body. |
| **Use Case** | Used for classes you don't own (e.g. Retrofit, Room, OkHttp) or when configuration is needed. | Used to bind an implementation class to its interface. |
| **Performance** | Slightly higher overhead because Hilt compiles concrete classes and provider methods. | More efficient; Hilt does not generate additional code for binds methods, it simply checks types at compile-time. |
| **Class Modifier** | Declared inside standard `object` or `class`. | Declared inside an `abstract class` or `interface`. |

**Code Example:**
```kotlin
// Using Provides (Standard)
@Provides
fun provideMovieRepository(api: TmdbApi): MovieRepository {
    return MovieRepositoryImpl(api)
}

// Equivalent using Binds (More efficient)
@Binds
abstract fun bindMovieRepository(impl: MovieRepositoryImpl): MovieRepository
```
*Note: To use `@Binds`, the implementation constructor (`MovieRepositoryImpl`) must be annotated with `@Inject constructor(...)`.*

---

### Q3: Explain Hilt Lifecycles and Scopes. What happens when you annotate a dependency with `@Singleton` vs leaving it unscoped?
**Answer:**
Hilt provides pre-defined components that map to Android lifecycles:

| Component | Lifecycle Scope | Created When | Destroyed When |
| :--- | :--- | :--- | :--- |
| **`SingletonComponent`** | `@Singleton` | Application starts (`onCreate`) | Application terminates |
| **`ActivityRetainedComponent`** | `@ActivityRetainedScoped` | Activity created (survives rotation) | Activity finishes |
| **`ActivityComponent`** | `@ActivityScoped` | Activity created | Activity destroyed |
| **`ViewModelComponent`** | `@ViewModelScoped` | ViewModel created | ViewModel cleared |

**Scoped (`@Singleton`) vs Unscoped:**
- **Unscoped (Default)**: Every time a class requests the dependency, Hilt creates a **new instance**.
- **Scoped (`@Singleton`)**: Hilt instantiates the dependency **once** within the `SingletonComponent`. Every injection target gets the same instance.
- **Warning**: Do not over-use scopes. Keeping objects in memory longer than needed leads to increased memory usage and potential leaks. Scope only objects that hold state, manage expensive operations (like databases or connection pools), or must be shared.

---

### Q4: Why do we use `@HiltViewModel`? How does Hilt handle ViewModel factory generation under the hood?
**Answer:**
Under standard Android architecture, ViewModels are created by `ViewModelProvider.Factory`. If a ViewModel requires constructor parameters (like a repository), you have to write a custom factory class to pass them in.

Annotating a ViewModel with `@HiltViewModel`:
1. Instructs the Hilt compiler to generate a `ViewModelProvider.Factory` for this class automatically.
2. Hilt integrates with the `ViewModelComponent` to resolve and inject all dependencies declared in the constructor (e.g., `MovieRepository`).
3. You can then retrieve the ViewModel easily in Compose using `hiltViewModel()` or in Activities using `by viewModels()`, and Hilt takes care of the factory initialization.

---

### Q5: What is the difference between `StateFlow` and `SharedFlow`?
**Answer:**
- **`StateFlow`**:
  - Represents a **state** (requires an initial value).
  - Emits the **latest** state to new collectors (hot stream, acts like a state holder).
  - Conflates values: if the value is updated to the exact same value as before, it is not re-emitted.
  - Ideal for UI State management (e.g., `MoviesState`).
- **`SharedFlow`**:
  - Represents an **event** (does not require an initial value).
  - Useful for one-time events like showing a Snackbar, navigation actions, or playing an audio track.
  - Can configure buffer sizes to replay old emissions to new collectors if desired.
  - Emits every value updates, even if identical to the previous one.
