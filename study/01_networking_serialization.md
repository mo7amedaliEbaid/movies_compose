# Study Guide 01: Android Networking & Serialization

This guide covers the implementation details, libraries, and design patterns used to establish secure, robust, and clean networking in Android applications using **Retrofit**, **Kotlinx Serialization**, and **Clean Architecture**.

---

## 1. Secure API Key Management

### The Challenge
Hardcoding API keys in source code (e.g., `const val API_KEY = "xyz"`) leads to security vulnerabilities:
- The key is checked into version control (Git).
- It can be easily reverse-engineered from the decompiled APK.

### The Solution: Gradle `local.properties` + `BuildConfig`
We store the sensitive key in a local, Git-ignored file (`local.properties`) and use the Android Gradle Plugin (AGP) to inject it as a constant in a generated class called `BuildConfig`.

1. **Add key to `local.properties`**:
   ```properties
   tmdb.api.key=your_secret_api_key_here
   ```
2. **Retrieve and inject in `app/build.gradle.kts`**:
   ```kotlin
   import java.util.Properties

   val localProperties = Properties().apply {
       val localPropertiesFile = rootProject.file("local.properties")
       if (localPropertiesFile.exists()) {
           localPropertiesFile.inputStream().use { load(it) }
       }
   }
   val tmdbApiKey = localProperties.getProperty("tmdb.api.key") ?: ""

   android {
       buildFeatures {
           buildConfig = true // Required in AGP 8.0+ / 9.0+
       }
       defaultConfig {
           buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
       }
   }
   ```
3. **Usage in Kotlin**:
   ```kotlin
   val apiKey = BuildConfig.TMDB_API_KEY
   ```

---

## 2. Retrofit 2.11.0 + Native Kotlinx Serialization

### Retrofit Evolution
Previously, using **Kotlinx Serialization** with Retrofit required a third-party library: `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter`.
Starting from **Retrofit 2.11.0**, Square added **native** support for Kotlinx Serialization.

### Configuration
1. Add the Kotlin serialization compiler plugin in `libs.versions.toml` and apply it in Gradle scripts:
   ```toml
   [plugins]
   kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
   ```
2. Declare the native converter in `dependencies`:
   ```kotlin
   implementation(libs.retrofit)
   implementation(libs.retrofit.converter.kotlinx.serialization)
   implementation(libs.kotlinx.serialization.json)
   ```
3. Instantiate Retrofit using the converter factory:
   ```kotlin
   import kotlinx.serialization.json.Json
   import okhttp3.MediaType.Companion.toMediaType
   import retrofit2.Retrofit
   import retrofit2.converter.kotlinx.serialization.asConverterFactory

   val json = Json {
       ignoreUnknownKeys = true // Prevents crashes when TMDB adds new response fields
       coerceInputValues = true // Replaces null/invalid json values with default property values
   }

   val retrofit = Retrofit.Builder()
       .baseUrl(TmdbApi.BASE_URL)
       .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
       .build()
   ```

---

## 3. Data Transfer Objects (DTO) vs. Domain Models

In Clean Architecture, we decouple the network data representation from our application's business logic.

```
[ TMDb Server ] 
      │ (JSON)
      ▼
[ MovieDto ] (Data Layer) ────► Mapping: toDomain() ────► [ Movie ] (Domain Layer)
      │                                                         │
      │ (Decoupled from network libraries)                       │ (Consumed by UI)
      ▼                                                         ▼
[ Network/Serialization Details ]                         [ Presentation Layer / Compose ]
```

### Why separate them?
1. **De-coupling**: If TMDb changes a JSON field name (e.g., changes `poster_path` to `poster_url`), we only modify `@SerialName` in `MovieDto`. The rest of the app (`Movie`, ViewModels, UI) remains completely untouched.
2. **Nullability & Clean Types**: API responses often contain nulls or types that are inconvenient for the UI. We can clean these up during mapping (e.g., converting a raw string date to a formatted date, or handling default fallback values for null URLs).
3. **Library Independence**: The `domain` layer should not depend on networking libraries (like `kotlinx.serialization` or `retrofit`). The domain layer represents pure business logic.

### Implementation Example

**1. Domain Model (`domain.model.Movie`)**:
```kotlin
data class Movie(
    val id: Int,
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String,
    val voteAverage: Double,
    val voteCount: Int
)
```

**2. Data Transfer Object (`data.model.MovieDto`)**:
```kotlin
@Serializable
data class MovieDto(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("overview") val overview: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0
) {
    // Mapper function
    fun toDomain(): Movie {
        return Movie(
            id = id,
            title = title,
            overview = overview,
            posterPath = posterPath,
            backdropPath = backdropPath,
            releaseDate = releaseDate,
            voteAverage = voteAverage,
            voteCount = voteCount
        )
    }
}
```

---

## 4. Best Practices Summary
- **Always configure `ignoreUnknownKeys = true`** in your JSON configuration. If you don't, your app will crash the moment TMDb introduces a new API field.
- **Provide default arguments** (e.g., `voteAverage: Double = 0.0`) in DTO constructors for safety against missing JSON keys.
- **Keep network interfaces thin** and focus on returning native Kotlin models (`suspend` functions rather than `Call<T>`).
