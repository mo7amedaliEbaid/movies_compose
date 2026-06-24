package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.Movie
import kotlinx.coroutines.flow.Flow

interface MovieRepository {
    fun getPopularMovies(): Flow<List<Movie>>
    suspend fun refreshPopularMovies(page: Int)
    
    suspend fun searchMovies(query: String, page: Int): List<Movie>
    suspend fun getMovieDetails(id: Int): Movie
    
    suspend fun getMovieFromCache(id: Int): Movie?
}
