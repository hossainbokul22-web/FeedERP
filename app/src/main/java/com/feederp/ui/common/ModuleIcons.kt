package com.feederp.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** মেনুর ইংরেজি নাম দেখে আইকন বেছে নেয় (না মিললে সাধারণ অ্যাপস আইকন)। */
fun moduleIcon(titleEn: String): ImageVector {
    val t = titleEn.lowercase()
    return when {
        "lots" in t || "expiry" in t -> Icons.Filled.Event
        "movement" in t -> Icons.Filled.SwapHoriz
        "adjust" in t && "stock" in t -> Icons.Filled.Tune
        "transfer" in t -> Icons.Filled.CompareArrows
        "alert" in t -> Icons.Filled.Warning
        "stock" in t -> Icons.Filled.Inventory2
        "warehouse" in t -> Icons.Filled.Warehouse
        "unit" in t -> Icons.Filled.Straighten
        "item" in t -> Icons.Filled.Category
        "part" in t && "ledger" !in t -> Icons.Filled.Groups
        "qc" in t -> Icons.Filled.VerifiedUser
        "trial" in t -> Icons.Filled.Biotech
        "formula" in t -> Icons.Filled.Science
        "production" in t -> Icons.Filled.Factory
        "purchase" in t -> Icons.Filled.ShoppingCart
        "goods receipt" in t -> Icons.Filled.MoveToInbox
        "receipts" in t -> Icons.Filled.Description
        "supplier" in t -> Icons.Filled.Payments
        "sales" in t -> Icons.Filled.ReceiptLong
        "delivery" in t -> Icons.Filled.LocalShipping
        "trip" in t -> Icons.Filled.DirectionsCar
        "employee" in t || "payroll" in t -> Icons.Filled.Badge
        "machine" in t -> Icons.Filled.Build
        "work order" in t -> Icons.Filled.Construction
        "expense" in t -> Icons.Filled.AccountBalanceWallet
        "ledger" in t -> Icons.Filled.MenuBook
        "aging" in t -> Icons.Filled.Schedule
        "cash" in t -> Icons.Filled.AccountBalance
        "report" in t -> Icons.Filled.Assessment
        "sync" in t -> Icons.Filled.Sync
        "backup" in t -> Icons.Filled.Backup
        else -> Icons.Filled.Apps
    }
}

private val HexShape = GenericShape { size, _ ->
    val r = size.minDimension / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f
    for (i in 0 until 6) {
        val a = Math.toRadians((60 * i - 90).toDouble())
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** হেক্সাগন আকৃতির গ্রেডিয়েন্ট ব্যাজের ভেতরে আইকন। */
@Composable
fun HexIcon(icon: ImageVector, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .background(Brush.linearGradient(listOf(c.primary, c.tertiary)), HexShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(size * 0.5f))
    }
}
