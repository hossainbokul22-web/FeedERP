package com.feederp.ui.formulation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** ফিড ফর্মুলা: তালিকা, এডিটর (লাইভ নিউট্রিয়েন্ট/খরচ), ভার্সন ও অনুমোদন। */
object FormulationModule : ModuleEntry {
    private const val GROUP = "ফর্মুলা ও প্রোডাকশন"
    const val LIST = "formulation/list"
    const val EDIT = "formulation/edit"

    override val menu: List<MenuItem> = listOf(
        MenuItem(LIST, "ফর্মুলা", "Formulas", Permission.FORMULA_VIEW, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(LIST) {
            FormulaListScreen(
                onBack = { nav.popBackStack() },
                onNew = { nav.navigate(EDIT) },
                onOpen = { id -> nav.navigate("$EDIT?id=$id") },
            )
        }
        builder.composable(
            route = "$EDIT?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) {
            FormulaEditorScreen(
                onBack = { nav.popBackStack() },
                // নতুন ভার্সন খুললে বর্তমান পর্দা বদলে নতুনটা দেখাই, যাতে "ফিরে যাও" তালিকায় যায়।
                onOpenOther = { id ->
                    nav.navigate("$EDIT?id=$id") { popUpTo(LIST) }
                },
            )
        }
    }
}
