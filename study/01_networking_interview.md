# Android Technical Interview Prep: Networking & Serialization

This guide outlines common interview questions, explanations, and key talking points related to networking, JSON parsing, API key security, and architectural data mapping.

---

### Q1: Why should we separate API models (DTOs) from UI/Domain models?
**Answer:**
1. **Separation of Concerns & Single Responsibility Principle (SRP):** DTOs represent the structure expected by the backend API. UI or Domain models represent the structure expected by our application logic and user interface.
2. **Resilience to API changes:** If the API structure changes (e.g., snake_case changes to camelCase, or field names are modified), we only modify the annotations or mapping functions in the data layer. The domain and presentation layers remain unaffected.
3. **Optimizing UI consumption:** DTO fields are often typed as raw strings (e.g., date formats, status strings) or contain nullable properties. During the mapping process (`toDomain()`), we can parse dates, provide default safe values, or map strings into strongly-typed Kotlin `enums` or `sealed classes`.
4. **Decoupled from Framework/Library dependencies:** If we decide to migrate from Kotlinx Serialization to Moshi or Gson, we only have to change serialization annotations on the DTO class. The domain models are pure Kotlin classes with zero annotations.

**Key Coding Phrase for Interview:**
> *"Using mapping functions like `toDomain()` acts as an anti-corruption layer (ACL), keeping library dependencies and backend schemas isolated in the data layer."*

---

### Q2: How does Retrofit execute `suspend` functions under the hood?
**Answer:**
Starting with Retrofit 2.6.0, Retrofit natively supports Kotlin coroutines. 
1. When you declare a function with the `suspend` modifier, Retrofit uses Kotlin reflection (specifically, checking if the last parameter of the method is of type `kotlin.coroutines.Continuation`).
2. Retrofit executes the call asynchronously using its standard OkHttp client dispatcher.
3. Instead of blocking the calling thread, it registers a callback (similar to `enqueue()`) and uses `suspendCancellableCoroutine` to yield the thread execution.
4. When the response arrives:
   - If the network call is successful, Retrofit calls `continuation.resume(response)` returning the parsed body.
   - If the network call fails or throws an exception, it calls `continuation.resumeWithException(exception)`.
5. Importantly, Retrofit handles the suspension and thread dispatching internally, meaning you do **not** need to wrap Retrofit suspend calls in `withContext(Dispatchers.IO)`.

---

### Q3: What is the difference between Kotlinx Serialization and Gson? Why choose one over the other?
**Answer:**

| Feature | Kotlinx Serialization | Gson |
| :--- | :--- | :--- |
| **Compilation** | **Compiler plugin-based**. Code generation happens at compile-time. | **Reflection-based**. Parses objects at runtime. |
| **Performance** | Extremely fast. Zero runtime reflection overhead. | Slower, especially for large JSONs, due to extensive reflection. |
| **Kotlin Type Safety** | Fully respects Kotlin properties, default values, and nullability. | Bypasses constructors using `UnsafeAllocator`. Can write `null` into non-nullable Kotlin types, causing runtime crashes. |
| **Platform support** | Kotlin Multiplatform (KMP) ready (Android, iOS, JVM, JS). | JVM/Android only. |

**Important Warning to Mention:**
> *"Gson does not check Kotlin nullability rules. If a JSON key is missing and the Kotlin property is non-nullable without a default value, Gson will inject `null` anyway via JVM reflection. This results in a NullPointerException when you access the field. Kotlinx Serialization guarantees type safety by respecting Kotlin's type system at compile time."*

---

### Q4: How do you handle TMDB API key security, and what are the limitations of storing it in `local.properties` / `BuildConfig`?
**Answer:**
We configure `local.properties` to ensure the API key is **not** pushed to public Git repositories. The Gradle script reads this file locally and injects it into `BuildConfig` at compile-time.

**Limitations / Vulnerabilities:**
Even if the API key is not in Git, compiling it into `BuildConfig` means it is stored as a plaintext String inside the final APK. A reverse engineer can easily decompile the APK using tools like **JADX** and extract the string in seconds.

**Mitigations for production applications:**
1. **Proguard / DexGuard Obfuscation:** Enable string obfuscation so keys aren't stored in cleartext.
2. **NDK (Native Development Kit):** Store keys inside native C/C++ files (`.cpp`) and compile them into shared libraries (`.so`). This is harder to reverse-engineer but still not 100% secure.
3. **Backend Proxy / API Gateway (Best Practice):** Instead of making direct calls to the TMDb API from the client app, the app should call a backend proxy server managed by your company. The proxy server stores the TMDb API key securely in its environment variables, appends it to requests, and forwards them. This keeps the API key completely hidden from the client application.

---

### Q5: What does `ignoreUnknownKeys = true` do in Kotlinx Serialization Json configuration? Why is it crucial?
**Answer:**
By default, Kotlinx Serialization is strict. If the JSON payload returned by the server contains keys that are not defined in the DTO class, the library will throw a `SerializationException` and crash.
Setting `ignoreUnknownKeys = true` configures the parser to simply ignore any undocumented fields. This is crucial for app stability because public APIs (like TMDb) frequently add new keys to their endpoints. Without this setting, a minor API update from the backend team would instantly break existing versions of the app in production.
