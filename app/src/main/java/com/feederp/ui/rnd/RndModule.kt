package com.feederp.ui.rnd

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** R&D: ফর্মুলা ট্রায়াল (FCR সহ) ও অনুমোদন প্রবাহ, মাঠ-পর্যায়ের ফিডব্যাক। */
object RndModule : ModuleEntry {
    private const val GROUP = "R&D"
    const val TRIALS = "rnd/trials"
    const val NEW = "rnd/new"
    const val TRIAL = "rnd/trial"
    const val FEEDBACK = "rnd/feedback"

    override val menu: List<MenuItem> = listOf(
        MenuItem(TRIALS, "ফর্মুলা ট্রায়াল", "Formula Trials", Permission.RND, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(TRIALS) {
            TrialListScreen(onBack = { nav.popBackStack() }, onNew = { nav.navigate(NEW) }, onOpen = { nav.navigate("$TRIAL/$it") }, onFeedback = { nav.navigate(FEEDBACK) })
        }
        builder.composable(NEW) { NewTrialScreen(onBack = { nav.popBackStack() }, onCreated = { id -> nav.navigate("$TRIAL/$id") { popUpTo(TRIALS) } }) }
        builder.composable("$TRIAL/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { TrialDetailScreen(onBack = { nav.popBackStack() }) }
        builder.composable(FEEDBACK) { FeedbackScreen(onBack = { nav.popBackStack() }) }
    }
}
