package com.example.myapplication.data.remote

import com.example.myapplication.data.model.MovieDto
import com.example.myapplication.data.model.MovieResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/*
 * INTERVIEW | Networking Q2: How does Retrofit execute `suspend` functions?
 * Retrofit 2.6+ natively supports coroutines. When it sees a `suspend` modifier, it checks
 * for a `Continuation` parameter and uses `suspendCancellableCoroutine` under the hood —
 * registering an OkHttp callback and calling `continuation.resume(response)` on success or
 * `continuation.resumeWithException(e)` on failure.
 * You do NOT need `withContext(Dispatchers.IO)` — Retrofit handles thread dispatch internally.
 */
interface TmdbApi {

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("page") page: Int = 1
    ): MovieResponse

    @GET("search/movie")
    suspend fun searchMovies(
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): MovieResponse

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") id: Int
    ): MovieDto

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3/"
        const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
        const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w780"
    }
}
