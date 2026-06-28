package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.Movie
import kotlinx.coroutines.flow.Flow

/*
 * INTERVIEW | DI/Architecture Q1: Dependency Inversion Principle (the D in SOLID)
 * High-level modules should not depend on low-level modules — both should depend on abstractions.
 *
 * Applied here:
 * - The Domain layer (high-level) owns this interface (abstraction). It has ZERO dependencies
 *   on Retrofit, Room, or any Android library — it is pure Kotlin.
 * - The Data layer (low-level) implements MovieRepositoryImpl and depends on this interface,
 *   inverting the dependency arrow: Data → Domain, not Domain → Data.
 *
 * This means the domain/presentation layers can be tested in isolation with a fake repository,
 * and swapping the entire networking or database implementation requires no domain-layer changes.
 */
interface MovieRepository {
    fun getPopularMovies(): Flow<List<Movie>>
    suspend fun refreshPopularMovies(page: Int)
    
    suspend fun searchMovies(query: String, page: Int): List<Movie>
    suspend fun getMovieDetails(id: Int): Movie
    
    suspend fun getMovieFromCache(id: Int): Movie?
}
