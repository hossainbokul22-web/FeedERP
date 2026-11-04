package com.feederp.ui.hr

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.feederp.core.MenuItem
import com.feederp.core.ModuleEntry
import com.feederp.core.Permission

/** HR: কর্মী, বেতন কাঠামো, হাজিরা, অগ্রিম/ঋণ, বোনাস/জরিমানা/পিস-কাজ, মাসিক বেতন (খসড়া → অনুমোদন → পরিশোধ)। */
object HrModule : ModuleEntry {
    private const val GROUP = "এইচআর"
    const val EMPLOYEES = "hr/employees"
    const val EMPLOYEE = "hr/employee"
    const val ATTENDANCE = "hr/attendance"
    const val PAYROLL = "hr/payroll"
    const val RUN = "hr/run"
    const val LOANS = "hr/loans"
    const val ADJUST = "hr/adjust"
    const val ADMIN = "hr/admin"

    override val menu: List<MenuItem> = listOf(
        MenuItem(EMPLOYEES, "কর্মী ও বেতন", "Employees & Payroll", Permission.HR, GROUP),
    )

    override fun register(builder: NavGraphBuilder, nav: NavHostController) {
        builder.composable(EMPLOYEES) {
            EmployeeListScreen(
                onBack = { nav.popBackStack() }, onOpen = { nav.navigate("$EMPLOYEE/$it") }, onNew = { nav.navigate("$EMPLOYEE/new") },
                onAttendance = { nav.navigate(ATTENDANCE) }, onPayroll = { nav.navigate(PAYROLL) },
                onLoans = { nav.navigate(LOANS) }, onAdjust = { nav.navigate(ADJUST) }, onAdmin = { nav.navigate(ADMIN) },
            )
        }
        builder.composable("$EMPLOYEE/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            EmployeeEditScreen(onBack = { nav.popBackStack() })
        }
        builder.composable(ATTENDANCE) { AttendanceScreen(onBack = { nav.popBackStack() }) }
        builder.composable(PAYROLL) { PayrollListScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("$RUN/$it") }) }
        builder.composable("$RUN/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            PayrollRunScreen(onBack = { nav.popBackStack() })
        }
        builder.composable(LOANS) { LoansScreen(onBack = { nav.popBackStack() }) }
        builder.composable(ADJUST) { AdjustmentsScreen(onBack = { nav.popBackStack() }) }
        builder.composable(ADMIN) { HrAdminScreen(onBack = { nav.popBackStack() }) }
    }
}
