package com.example.myapplication.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.model.Movie
import com.example.myapplication.domain.repository.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovieDetailState(
    val isLoading: Boolean = false,
    val movieDetail: Movie? = null,
    val error: String? = null
)

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val repository: MovieRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(MovieDetailState())
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()

    init {
        savedStateHandle.get<Int>("movie_id")?.let { id ->
            loadMovieDetails(id)
        }
    }

    fun loadMovieDetails(id: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            
            // 1. Fetch from Room cache first so UI renders instantly
            val cachedMovie = repository.getMovieFromCache(id)
            if (cachedMovie != null) {
                _state.update { it.copy(movieDetail = cachedMovie) }
            }
            
            // 2. Fetch full details from TMDB (runtime, genres)
            try {
                val detail = repository.getMovieDetails(id)
                _state.update { it.copy(isLoading = false, movieDetail = detail) }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Failed to load movie details"
                    ) 
                }
            }
        }
    }
}
