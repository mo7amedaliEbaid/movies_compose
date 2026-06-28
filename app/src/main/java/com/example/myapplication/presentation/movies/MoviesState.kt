package com.example.myapplication.presentation.movies

import com.example.myapplication.domain.model.Movie

/*
 * INTERVIEW | Compose Q2: @Stable vs @Immutable
 * @Immutable: promises ALL properties are val and will never mutate after construction. Compose
 *   can skip recomposition if the instance reference hasn't changed.
 * @Stable: promises the object may be mutable but Compose will be notified of any change
 *   (e.g. via MutableState). Compose can still skip recomposition when values are equal.
 *
 * GOTCHA: Standard Kotlin List<Movie> is considered UNSTABLE by the Compose compiler because
 * ArrayList (the default impl) is mutable. Passing List<Movie> to a Composable can prevent
 * that Composable from ever skipping recomposition. Fix: use kotlinx.collections.immutable or
 * annotate the wrapper state class with @Stable / @Immutable.
 */
data class MoviesState(
    val isLoading: Boolean = false,
    val movies: List<Movie> = emptyList(),
    val error: String? = null,
    
    // Search and Pagination States
    val searchQuery: String = "",
    val isFetchingNextPage: Boolean = false,
    val hasReachedEnd: Boolean = false,
    val currentPage: Int = 1
)
