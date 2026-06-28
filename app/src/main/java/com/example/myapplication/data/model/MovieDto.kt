package com.example.myapplication.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.example.myapplication.domain.model.Movie

/*
 * INTERVIEW | Networking Q3: Kotlinx Serialization vs Gson?
 * Kotlinx Serialization uses a compiler plugin — code is generated at compile time, making it
 * fast with zero runtime reflection. Gson uses reflection at runtime and can inject null into
 * non-nullable Kotlin properties (via UnsafeAllocator), causing NullPointerExceptions.
 * Kotlinx Serialization respects Kotlin's type system and supports Kotlin Multiplatform (KMP).
 */
@Serializable
data class MovieDto(
    @SerialName("id") val id: Int,
    @SerialName("title") val title: String,
    @SerialName("overview") val overview: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("runtime") val runtime: Int? = null,
    @SerialName("genres") val genres: List<GenreDto>? = null
) {
    /*
     * INTERVIEW | Networking Q1: Why separate DTOs from Domain models?
     * 1. SRP: DTOs mirror the API schema; Domain models mirror app logic — changes in one don't
     *    break the other.
     * 2. Resilience: if the API renames a field we only update @SerialName here, not the UI.
     * 3. toDomain() is an Anti-Corruption Layer (ACL) — it parses raw values (nullable paths,
     *    date strings) into safe, strongly-typed Kotlin types for the domain/presentation layers.
     * 4. Decoupling: Domain models are pure Kotlin with no Retrofit/Room/Serialization annotations.
     */
    fun toDomain(): Movie {
        return Movie(
            id = id,
            title = title,
            overview = overview,
            posterPath = posterPath,
            backdropPath = backdropPath,
            releaseDate = releaseDate,
            voteAverage = voteAverage,
            voteCount = voteCount,
            runtime = runtime,
            genres = genres?.map { it.toDomain() }
        )
    }
}
