package com.streambert.tv.ui.navigation

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.streambert.tv.MainActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streambert.tv.data.model.MediaType
import com.streambert.tv.ui.components.LoadingIndicator
import com.streambert.tv.di.AppContainer
import com.streambert.tv.ui.browse.CatalogBrowseScreen
import com.streambert.tv.ui.detail.DetailScreen
import com.streambert.tv.ui.detail.DetailViewModel
import com.streambert.tv.ui.genre.GenreViewModel
import com.streambert.tv.ui.home.HomeScreen
import com.streambert.tv.ui.home.HomeViewModel
import com.streambert.tv.ui.person.PersonViewModel
import com.streambert.tv.ui.player.PlayerScreen
import com.streambert.tv.ui.player.PlayerViewModel
import com.streambert.tv.ui.search.SearchScreen
import com.streambert.tv.ui.search.SearchViewModel
import com.streambert.tv.ui.service.ServiceViewModel
import com.streambert.tv.ui.settings.SettingsScreen
import com.streambert.tv.ui.settings.SettingsViewModel
import com.streambert.tv.ui.trailer.TrailerScreen
import com.streambert.tv.ui.setup.SetupScreen

@Composable
fun AppNavigation(
    container: AppContainer,
    isConfigured: Boolean?,
    modifier: Modifier = Modifier
) {
    // Splash while DataStore loads the first value.
    if (isConfigured == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val start = if (isConfigured) Routes.HOME else Routes.SETUP

    // Long-pressing BACK (handled in MainActivity) jumps straight to Home; a
    // short press falls through to the normal one-step-back behaviour.
    val context = LocalContext.current
    DisposableEffect(navController) {
        val activity = context.findMainActivity()
        activity?.onNavigateHome = {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
        onDispose { activity?.onNavigateHome = null }
    }

    NavHost(
        navController = navController,
        startDestination = start,
        modifier = modifier
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                settings = container.settingsRepository,
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        HomeViewModel(
                            repo = container.tmdbRepository,
                            progress = container.progressRepository,
                            myList = container.myListRepository,
                            watched = container.watchedRepository,
                            trakt = container.traktRepository,
                            traktAuth = container.traktAuthRepository,
                            ai = container.aiCatalog,
                            mdblist = container.mdbListRepository,
                            youTubeExtractor = container.youTubeExtractor,
                            settings = container.settingsRepository
                        )
                    }
                }
            )
            HomeScreen(
                viewModel = vm,
                onSelect = { item -> navController.navigate(Routes.detail(item.type, item.id)) },
                onResume = { p ->
                    // Replay the exact source watched from. Prefer the torrent
                    // hash (re-resolves a fresh, non-expiring URL for the SAME
                    // source); fall back to the saved URL; else auto-resolve.
                    val savedHash = p.streamHash.orEmpty()
                    val savedUrl = if (savedHash.isNotBlank()) "" else p.streamUrl.orEmpty()
                    navController.navigate(
                        Routes.player(
                            type = p.mediaType,
                            id = p.tmdbId,
                            season = p.season,
                            episode = p.episode,
                            title = p.title,
                            streamUrl = savedUrl,
                            posterUrl = p.posterUrl.orEmpty(),
                            backdropUrl = p.backdropUrl.orEmpty(),
                            hash = savedHash
                        )
                    )
                },
                onOpenService = { svc -> navController.navigate(Routes.service(svc.providerId, svc.name)) },
                onOpenGenre = { g, media ->
                    navController.navigate(Routes.genre(g.name, g.movieGenreId ?: 0, g.tvGenreId ?: 0, media))
                },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SearchViewModel(
                            container.tmdbRepository,
                            container.aiCatalog,
                            container.searchHistoryRepository
                        )
                    }
                }
            )
            SearchScreen(
                viewModel = vm,
                onSelect = { item -> navController.navigate(Routes.detail(item.type, item.id)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("id") { type = NavType.IntType }
            )
        ) { entry ->
            val type = MediaType.from(entry.arguments?.getString("type"))
            val id = entry.arguments?.getInt("id") ?: 0
            val vm: DetailViewModel = viewModel(
                key = "detail_$type$id",
                factory = viewModelFactory {
                    initializer {
                        DetailViewModel(
                            repo = container.tmdbRepository,
                            streams = container.streamRepository,
                            myList = container.myListRepository,
                            omdb = container.omdbRepository,
                            mdblist = container.mdbListRepository,
                            watchedRepo = container.watchedRepository,
                            settings = container.settingsRepository,
                            badges = container.badgeRepository,
                            type = type,
                            id = id
                        )
                    }
                }
            )
            DetailScreen(
                viewModel = vm,
                onPlayAuto = { season, episode, title, poster, backdrop ->
                    navController.navigate(Routes.player(type, id, season, episode, title, "", poster, backdrop))
                },
                onPlayStream = { title, url, hash, poster, backdrop, debrid ->
                    navController.navigate(
                        Routes.player(
                            type = type,
                            id = id,
                            season = -1,
                            episode = -1,
                            title = title,
                            streamUrl = url,
                            posterUrl = poster,
                            backdropUrl = backdrop,
                            hash = hash,
                            debrid = debrid
                        )
                    )
                },
                onSelectRelated = { item -> navController.navigate(Routes.detail(item.type, item.id)) },
                onOpenPerson = { person -> navController.navigate(Routes.person(person.id, person.name)) },
                onOpenTrailer = { key -> navController.navigate(Routes.trailer(key)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("id") { type = NavType.IntType },
                navArgument("season") { type = NavType.IntType },
                navArgument("episode") { type = NavType.IntType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("stream") { type = NavType.StringType; defaultValue = "" },
                navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                navArgument("backdrop") { type = NavType.StringType; defaultValue = "" },
                navArgument("hash") { type = NavType.StringType; defaultValue = "" },
                navArgument("debrid") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val type = MediaType.from(entry.arguments?.getString("type"))
            val id = entry.arguments?.getInt("id") ?: 0
            val season = entry.arguments?.getInt("season") ?: -1
            val episode = entry.arguments?.getInt("episode") ?: -1
            val title = entry.arguments?.getString("title").orEmpty()
            val streamUrl = entry.arguments?.getString("stream").orEmpty()
            val poster = entry.arguments?.getString("poster").orEmpty()
            val backdrop = entry.arguments?.getString("backdrop").orEmpty()
            val hash = entry.arguments?.getString("hash").orEmpty()
            val debrid = entry.arguments?.getString("debrid").orEmpty()
            val vm: PlayerViewModel = viewModel(
                key = "player_$type${id}_${season}_${episode}_${streamUrl.hashCode()}_${hash.hashCode()}_${debrid.hashCode()}",
                factory = viewModelFactory {
                    initializer {
                        PlayerViewModel(
                            tmdb = container.tmdbRepository,
                            stream = container.streamRepository,
                            progress = container.progressRepository,
                            settings = container.settingsRepository,
                            subtitles = container.subtitleRepository,
                            type = type,
                            id = id,
                            season = season,
                            episode = episode,
                            title = title,
                            posterUrl = poster,
                            backdropUrl = backdrop,
                            directUrl = streamUrl,
                            directHash = hash,
                            directDebrid = debrid
                        )
                    }
                }
            )
            PlayerScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onNextEpisode = if (type == MediaType.TV && episode > 0) {
                    {
                        navController.navigate(
                            Routes.player(type, id, season, episode + 1, "", "", poster, backdrop)
                        )
                    }
                } else null
            )
        }

        composable(
            route = Routes.SERVICE,
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { entry ->
            val pid = entry.arguments?.getInt("id") ?: 0
            val name = entry.arguments?.getString("name").orEmpty()
            val vm: ServiceViewModel = viewModel(
                key = "service_$pid",
                factory = viewModelFactory {
                    initializer { ServiceViewModel(container.tmdbRepository, pid, name) }
                }
            )
            val s by vm.state.collectAsState()
            CatalogBrowseScreen(
                state = s,
                onSelect = { item -> navController.navigate(Routes.detail(item.type, item.id)) },
                onBack = { navController.popBackStack() },
                onRetry = { vm.load() }
            )
        }

        composable(
            route = Routes.PERSON,
            arguments = listOf(
                navArgument("id") { type = NavType.IntType },
                navArgument("name") { type = NavType.StringType }
            )
        ) { entry ->
            val pid = entry.arguments?.getInt("id") ?: 0
            val name = entry.arguments?.getString("name").orEmpty()
            val vm: PersonViewModel = viewModel(
                key = "person_$pid",
                factory = viewModelFactory {
                    initializer { PersonViewModel(container.tmdbRepository, pid, name) }
                }
            )
            val s by vm.state.collectAsState()
            CatalogBrowseScreen(
                state = s,
                onSelect = { item -> navController.navigate(Routes.detail(item.type, item.id)) },
                onBack = { navController.popBackStack() },
                onRetry = { vm.load() }
            )
        }

        composable(
            route = Routes.GENRE,
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
                navArgument("movieId") { type = NavType.IntType },
                navArgument("tvId") { type = NavType.IntType },
                navArgument("media") { type = NavType.StringType; defaultValue = "all" }
            )
        ) { entry ->
            val name = entry.arguments?.getString("name").orEmpty()
            val movieId = entry.arguments?.getInt("movieId") ?: 0
            val tvId = entry.arguments?.getInt("tvId") ?: 0
            val media = entry.arguments?.getString("media") ?: "all"
            val vm: GenreViewModel = viewModel(
                key = "genre_${name}_$media",
                factory = viewModelFactory {
                    initializer { GenreViewModel(container.tmdbRepository, name, movieId, tvId, media) }
                }
            )
            val s by vm.state.collectAsState()
            CatalogBrowseScreen(
                state = s,
                onSelect = { item -> navController.navigate(Routes.detail(item.type, item.id)) },
                onBack = { navController.popBackStack() },
                onRetry = { vm.load() }
            )
        }

        composable(
            route = Routes.TRAILER,
            arguments = listOf(navArgument("key") { type = NavType.StringType })
        ) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            val vm: com.streambert.tv.ui.trailer.TrailerViewModel = viewModel(
                key = "trailer_$key",
                factory = viewModelFactory {
                    initializer { com.streambert.tv.ui.trailer.TrailerViewModel(container.youTubeExtractor, key) }
                }
            )
            TrailerScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            container.settingsRepository,
                            container.addonRepository,
                            container.traktAuthRepository
                        )
                    }
                }
            )
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}


/** Unwraps the hosting [MainActivity] from a (possibly wrapped) Context. */
private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
