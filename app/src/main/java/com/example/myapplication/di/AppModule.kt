package com.example.myapplication.di

import android.content.Context
import androidx.room.Room
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.local.MovieDatabase
import com.example.myapplication.data.remote.TmdbApi
import com.example.myapplication.data.repository.MovieRepositoryImpl
import com.example.myapplication.domain.repository.MovieRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/*
 * INTERVIEW | DI Q3: Hilt Scopes — @Singleton vs Unscoped
 * Every @Provides function here is annotated @Singleton, meaning Hilt creates the dependency
 * once inside SingletonComponent (lives as long as the Application) and shares that same
 * instance everywhere it is injected. Without @Singleton, Hilt would create a NEW instance
 * on every injection. Over-using scopes keeps objects in memory longer than needed, so only
 * scope objects that hold shared state or manage expensive resources (DB, network client).
 *
 * INTERVIEW | DI Q2: @Provides vs @Binds
 * We use @Provides (not @Binds) throughout this module because we own the instantiation logic:
 * we configure OkHttp, Json, and Retrofit before creating them. @Binds would be a more
 * efficient alternative for simple interface→implementation bindings (e.g. MovieRepository),
 * but it requires an abstract class and @Inject constructor on the implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        /*
         * INTERVIEW | Networking Q4: API Key Security — local.properties / BuildConfig
         * The key is read from local.properties (git-ignored) and compiled into BuildConfig.
         * LIMITATION: It is stored as a plaintext String inside the APK. A decompiler (JADX)
         * can extract it in seconds.
         * Production mitigations: (1) ProGuard string obfuscation, (2) NDK native .so files,
         * (3) Backend Proxy — the app calls YOUR server which holds the TMDB key server-side.
         */
        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer ${BuildConfig.TMDB_API_KEY}")
                .addHeader("accept", "application/json")
                .build()

            chain.proceed(newRequest)
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor(authInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideTmdbApi(okHttpClient: OkHttpClient): TmdbApi {
        /*
         * INTERVIEW | Networking Q5: ignoreUnknownKeys = true
         * By default Kotlinx Serialization throws SerializationException for any JSON key not
         * declared in the DTO. Setting ignoreUnknownKeys = true makes the parser skip unknown
         * fields silently. This is critical for stability: public APIs like TMDB frequently add
         * new keys, and without this flag a minor backend change would crash all existing app
         * versions in production.
         */
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        return Retrofit.Builder()
            .baseUrl(TmdbApi.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TmdbApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMovieDatabase(@ApplicationContext context: Context): MovieDatabase {
        return Room.databaseBuilder(
            context,
            MovieDatabase::class.java,
            "movie_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideMovieRepository(api: TmdbApi, db: MovieDatabase): MovieRepository {
        return MovieRepositoryImpl(api, db)
    }
}
