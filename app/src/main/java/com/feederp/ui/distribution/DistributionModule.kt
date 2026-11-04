package com.feederp.ui.distribution

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** ডিস্ট্রিবিউশন: গাড়ি/ড্রাইভার, ট্রিপ (লোড → যাত্রা → স্টপে ডেলিভারি → শেষ), পরিবহন খরচ। */
object DistributionModule : ModuleEntry {
    private const val GROUP = "ডিস্ট্রিবিউশন"
    const val TRIPS = "distribution/trips"
    const val NEW = "distribution/new"
    const val TRIP = "distribution/trip"
    const val FLEET = "distribution/fleet"

    override val menu: List<MenuItem> = listOf(
        MenuItem(TRIPS, "ট্রিপ", "Trips", Permission.DELIVERY_VIEW, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(TRIPS) {
            TripListScreen(
                onBack = { nav.popBackStack() }, onNew = { nav.navigate(NEW) },
                onOpen = { id -> nav.navigate("$TRIP/$id") }, onFleet = { nav.navigate(FLEET) },
            )
        }
        builder.composable(NEW) {
            NewTripScreen(onBack = { nav.popBackStack() }, onCreated = { id -> nav.navigate("$TRIP/$id") { popUpTo(TRIPS) } })
        }
        builder.composable(
            route = "$TRIP/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { TripDetailScreen(onBack = { nav.popBackStack() }) }
        builder.composable(FLEET) { FleetScreen(onBack = { nav.popBackStack() }) }
    }
}
