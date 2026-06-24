package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.presentation.detail.MovieDetailScreen
import com.example.myapplication.presentation.detail.MovieDetailViewModel
import com.example.myapplication.presentation.movies.MoviesScreen
import com.example.myapplication.presentation.movies.MoviesViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()

                NavHost(
                    navController = navController,
                    startDestination = "movies"
                ) {
                    composable("movies") {
                        val viewModel: MoviesViewModel = hiltViewModel()
                        MoviesScreen(
                            viewModel = viewModel,
                            onMovieClick = { movie ->
                                navController.navigate("detail/${movie.id}")
                            }
                        )
                    }
                    composable(
                        route = "detail/{movie_id}",
                        arguments = listOf(
                            navArgument("movie_id") { type = NavType.IntType }
                        )
                    ) {
                        val viewModel: MovieDetailViewModel = hiltViewModel()
                        MovieDetailScreen(
                            viewModel = viewModel,
                            onBackClick = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}