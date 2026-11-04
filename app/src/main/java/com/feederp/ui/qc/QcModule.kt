package com.feederp.ui.qc

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** QC: অপেক্ষমাণ লটের তালিকা, টেস্ট এন্ট্রি, পাস/হোল্ড/বাতিলের সিদ্ধান্ত। */
object QcModule : ModuleEntry {
    private const val GROUP = "মান নিয়ন্ত্রণ"
    const val QUEUE = "qc/queue"
    const val LOT = "qc/lot"

    override val menu: List<MenuItem> = listOf(
        MenuItem(QUEUE, "QC লট", "QC", Permission.QC, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(QUEUE) {
            QcQueueScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("$LOT/$id") })
        }
        builder.composable(
            route = "$LOT/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) {
            QcLotScreen(onBack = { nav.popBackStack() })
        }
    }
}
