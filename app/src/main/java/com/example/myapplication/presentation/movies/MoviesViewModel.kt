package com.example.myapplication.presentation.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.model.Movie
import com.example.myapplication.domain.repository.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * INTERVIEW | DI Q4: Why @HiltViewModel?
 * Normally, to pass constructor args (like a repository) to a ViewModel you must write a custom
 * ViewModelProvider.Factory. @HiltViewModel tells the Hilt compiler to auto-generate that factory.
 * Hilt wires the ViewModelComponent so all @Inject constructor dependencies are resolved, then
 * you retrieve it via hiltViewModel() in Compose or `by viewModels()` in an Activity.
 *
 * INTERVIEW | DI Q5: StateFlow vs SharedFlow
 * StateFlow: requires an initial value, holds the LATEST state, conflates equal consecutive
 *   values (no re-emit if unchanged). Ideal for UI state holders like MoviesState.
 * SharedFlow: no initial value, emits every value (even duplicates), configurable replay buffer.
 *   Best for one-time events — Snackbar, navigation actions, sound playback.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class MoviesViewModel @Inject constructor(
    private val repository: MovieRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MoviesState())
    val state: StateFlow<MoviesState> = _state.asStateFlow()

    private val _searchQueryFlow = MutableStateFlow("")
    private var cachedMovies: List<Movie> = emptyList()

    init {
        // 1. Observe local Room Database flow
        // INTERVIEW | Caching Q5: Preventing resource leaks from Flow collection in ViewModel
        // .launchIn(viewModelScope) ties the collection to viewModelScope. When the ViewModel is
        // cleared (Activity/Fragment finishes), viewModelScope is automatically cancelled,
        // cancelling the Flow subscription and preventing memory leaks.
        // In Composables, prefer collectAsStateWithLifecycle() (lifecycle-runtime-compose) over
        // collectAsState() — it pauses collection when the lifecycle drops below STARTED (app
        // backgrounds), saving CPU cycles and resuming automatically on foreground.
        repository.getPopularMovies()
            .onEach { popularList ->
                cachedMovies = popularList
                // Only display in state if the user is not actively searching
                if (_state.value.searchQuery.isEmpty()) {
                    _state.update { it.copy(movies = popularList) }
                }
            }
            .launchIn(viewModelScope)

        // 2. Observe search query with 500ms debounce
        // INTERVIEW | Navigation Q3: Input debouncing with Kotlin Flows
        // debounce(500): if a new value arrives before 500ms elapses, the previous is discarded
        //   and the timer resets. Only the final value after a pause triggers the search.
        // distinctUntilChanged(): suppresses re-emission of the same value — e.g., typing "a",
        //   backspacing and re-typing "a" within the debounce window won't fire a duplicate call.
        // Benefits: saves network bandwidth, reduces server load, prevents race conditions where
        //   a slower older query response arrives after a newer one.
        viewModelScope.launch {
            _searchQueryFlow
                .debounce(500)
                .distinctUntilChanged()
                .collect { query ->
                    if (query.isEmpty()) {
                        _state.update { 
                            it.copy(
                                movies = cachedMovies,
                                currentPage = 1,
                                hasReachedEnd = false,
                                error = null
                            ) 
                        }
                    } else {
                        executeSearch(query)
                    }
                }
        }

        // Trigger initial network load
        refreshMovies()
    }

    fun onSearchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        _searchQueryFlow.value = query
    }

    fun refreshMovies() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, currentPage = 1, hasReachedEnd = false) }
            try {
                if (_state.value.searchQuery.isEmpty()) {
                    repository.refreshPopularMovies(page = 1)
                } else {
                    val results = repository.searchMovies(_state.value.searchQuery, page = 1)
                    _state.update { it.copy(movies = results, hasReachedEnd = results.isEmpty()) }
                }
                _state.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Failed to refresh movies cache"
                    ) 
                }
            }
        }
    }

    private fun executeSearch(query: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, currentPage = 1, hasReachedEnd = false, error = null) }
            try {
                val results = repository.searchMovies(query, page = 1)
                _state.update { 
                    it.copy(
                        isLoading = false,
                        movies = results,
                        hasReachedEnd = results.isEmpty()
                    ) 
                }
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Failed to perform search"
                    ) 
                }
            }
        }
    }

    fun loadNextPage() {
        val currentState = _state.value
        if (currentState.isFetchingNextPage || currentState.hasReachedEnd || currentState.isLoading) {
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isFetchingNextPage = true) }
            val nextPage = currentState.currentPage + 1
            try {
                val newMovies = if (currentState.searchQuery.isEmpty()) {
                    // For popular movies, sync next page to database cache
                    repository.refreshPopularMovies(nextPage)
                    emptyList() // Room DB will automatically stream updates to UI State
                } else {
                    // For search results, fetch next page and append in memory
                    repository.searchMovies(currentState.searchQuery, nextPage)
                }

                _state.update { stateVal ->
                    val updatedMoviesList = if (stateVal.searchQuery.isEmpty()) {
                        stateVal.movies // Emitted dynamically by Room Flow
                    } else {
                        stateVal.movies + newMovies
                    }
                    
                    stateVal.copy(
                        isFetchingNextPage = false,
                        currentPage = nextPage,
                        movies = updatedMoviesList,
                        hasReachedEnd = if (stateVal.searchQuery.isEmpty()) false else newMovies.isEmpty()
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isFetchingNextPage = false) }
            }
        }
    }
}
