package com.example.myapplication.data.repository

import androidx.room.withTransaction
import com.example.myapplication.data.local.MovieDatabase
import com.example.myapplication.data.local.MovieEntity
import com.example.myapplication.data.remote.TmdbApi
import com.example.myapplication.domain.model.Movie
import com.example.myapplication.domain.repository.MovieRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MovieRepositoryImpl(
    private val api: TmdbApi,
    private val db: MovieDatabase
) : MovieRepository {

    private val dao = db.movieDao

    override fun getPopularMovies(): Flow<List<Movie>> {
        return dao.getPopularMovies().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun refreshPopularMovies(page: Int) {
        val remoteMoviesResponse = api.getPopularMovies(page)
        val entities = remoteMoviesResponse.results.map { 
            MovieEntity(
                id = it.id,
                title = it.title,
                overview = it.overview,
                posterPath = it.posterPath,
                backdropPath = it.backdropPath,
                releaseDate = it.releaseDate,
                voteAverage = it.voteAverage,
                voteCount = it.voteCount
            )
        }
        
        db.withTransaction {
            if (page == 1) {
                dao.clearAllMovies()
            }
            dao.insertMovies(entities)
        }
    }

    override suspend fun searchMovies(query: String, page: Int): List<Movie> {
        return api.searchMovies(query, page).results.map { it.toDomain() }
    }

    override suspend fun getMovieDetails(id: Int): Movie {
        return api.getMovieDetails(id).toDomain()
    }

    override suspend fun getMovieFromCache(id: Int): Movie? {
        return dao.getMovieById(id)?.toDomain()
    }
}
