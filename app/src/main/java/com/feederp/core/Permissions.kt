package com.feederp.core

import com.feederp.data.Role

enum class Permission {
    MASTER_VIEW, MASTER_EDIT,
    PURCHASE, INVENTORY, INVENTORY_ISSUE_MEDICINE,
    FORMULA_VIEW, FORMULA_EDIT, PRODUCTION, QC,
    SALES, SALES_OWN_ONLY, DELIVERY_DISPATCH, DELIVERY_VIEW,
    ACCOUNTS, REPORTS, PRICE_EDIT, APPROVE,
    MAINTENANCE, MAINTENANCE_REPORT, DISTRIBUTION,
    HR, HR_SALARY, RND,
    USERS, SYNC, BACKUP,
}

object Permissions {
    private val all = Permission.values().toSet()

    private val byRole: Map<Role, Set<Permission>> = mapOf(
        Role.OWNER to all,
        Role.MANAGER to all - setOf(Permission.USERS, Permission.BACKUP, Permission.HR_SALARY),
        Role.SALES to setOf(
            Permission.MASTER_VIEW, Permission.SALES, Permission.SALES_OWN_ONLY,
            Permission.DELIVERY_VIEW, Permission.MAINTENANCE_REPORT, Permission.SYNC,
        ),
        Role.STORE to setOf(
            Permission.MASTER_VIEW, Permission.PURCHASE, Permission.INVENTORY, Permission.QC,
            Permission.DELIVERY_DISPATCH, Permission.DELIVERY_VIEW, Permission.MAINTENANCE_REPORT,
            Permission.SYNC,
        ),
        Role.MAINTENANCE to setOf(
            Permission.MASTER_VIEW, Permission.MAINTENANCE, Permission.MAINTENANCE_REPORT,
            Permission.INVENTORY, Permission.SYNC,
        ),
        Role.DISTRIBUTION to setOf(
            Permission.MASTER_VIEW, Permission.DISTRIBUTION, Permission.DELIVERY_VIEW,
            Permission.DELIVERY_DISPATCH, Permission.MAINTENANCE_REPORT, Permission.SYNC,
        ),
        Role.HR to setOf(
            Permission.MASTER_VIEW, Permission.HR, Permission.HR_SALARY, Permission.SYNC,
        ),
        Role.RND to setOf(
            Permission.MASTER_VIEW, Permission.FORMULA_VIEW, Permission.FORMULA_EDIT,
            Permission.RND, Permission.QC, Permission.SYNC,
        ),
    )

    fun of(role: Role): Set<Permission> = byRole[role].orEmpty()
    fun has(role: Role, permission: Permission): Boolean = permission in of(role)
}
