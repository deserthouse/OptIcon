package io.github.deserthouse.opticon.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.deserthouse.opticon.ui.screen.AppDetailScreen
import io.github.deserthouse.opticon.ui.screen.AppListScreen
import io.github.deserthouse.opticon.ui.screen.SettingsScreen
import io.github.deserthouse.opticon.ui.theme.OptIconTheme
import io.github.deserthouse.opticon.util.PreferenceManager
import io.github.deserthouse.opticon.util.TraceLogger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.init(applicationContext)
        TraceLogger.init(applicationContext, if (PreferenceManager.isVerboseLogging()) TraceLogger.DEBUG else TraceLogger.INFO)
        enableEdgeToEdge()
        setContent { OptIconTheme { OptIconNavHost() } }
    }
}

private object Routes {
    const val LIST = "list"; const val DETAIL = "detail/{pkg}"; const val SETTINGS = "settings"
    fun detail(pkg: String) = "detail/${Uri.encode(pkg)}"
}

// Material shared-axis X transitions (M3 motion): short slide (¼ width) +
// slight scale-up on enter + fade, symmetric easing — replaces plain slide.
private const val NAV_DURATION = 320
private val NAV_EASING = EaseOutCubic

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enterPush(): EnterTransition =
    slideInHorizontally(tween(NAV_DURATION, easing = NAV_EASING)) { it / 4 } +
    scaleIn(tween(NAV_DURATION, easing = NAV_EASING), initialScale = 0.94f) +
    fadeIn(tween(NAV_DURATION, easing = NAV_EASING))
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exitPush(): ExitTransition =
    slideOutHorizontally(tween(NAV_DURATION, easing = NAV_EASING)) { -it / 4 } + fadeOut(tween(NAV_DURATION))
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enterPop(): EnterTransition =
    slideInHorizontally(tween(NAV_DURATION, easing = NAV_EASING)) { -it / 4 } +
    scaleIn(tween(NAV_DURATION, easing = NAV_EASING), initialScale = 0.94f) +
    fadeIn(tween(NAV_DURATION, easing = NAV_EASING))
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exitPop(): ExitTransition =
    slideOutHorizontally(tween(NAV_DURATION, easing = NAV_EASING)) { it / 4 } + fadeOut(tween(NAV_DURATION))

@Composable
fun OptIconNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    // Predictive back: navigation-compose 2.8+ NavHost handles the gesture
    // natively (cross-screen preview). Do NOT intercept with a custom
    // PredictiveBackHandler — that opts us out of the system preview.

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        NavHost(
            navController = navController, startDestination = Routes.LIST,
            enterTransition = { enterPush() }, exitTransition = { exitPush() },
            popEnterTransition = { enterPop() }, popExitTransition = { exitPop() }
        ) {
            composable(Routes.LIST) {
                AppListScreen(
                    onNavigateToDetail = { pkg -> navController.navigate(Routes.detail(pkg)) },
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
            composable(route = Routes.DETAIL, arguments = listOf(navArgument("pkg") { type = NavType.StringType })) { entry ->
                val pkg = entry.arguments?.getString("pkg").orEmpty()
                AppDetailScreen(packageName = pkg, onNavigateBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(navController = navController)
            }
        }
    }
}
