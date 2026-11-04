package com.feederp.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.feederp.ui.auth.LoginScreen
import com.feederp.ui.home.HomeScreen

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoggedIn = { nav.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } } },
                onOpenSync = { nav.navigate(com.feederp.ui.sync.SyncModule.MAIN) },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onNavigate = { route -> nav.navigate(route) },
                onLoggedOut = {
                    nav.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                },
            )
        }
        AppModules.all.forEach { it.register(this, nav) }
    }
}
