package com.example.myapplication.presentation.movies

import com.example.myapplication.domain.model.Movie

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
