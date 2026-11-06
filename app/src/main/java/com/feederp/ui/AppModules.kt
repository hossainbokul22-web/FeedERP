package com.feederp.ui

import com.feederp.core.ModuleEntry
import com.feederp.ui.accounts.AccountsModule
import com.feederp.ui.delivery.DeliveryModule
import com.feederp.ui.distribution.DistributionModule
import com.feederp.ui.backup.BackupModule
import com.feederp.ui.hr.HrModule
import com.feederp.ui.rnd.RndModule
import com.feederp.ui.formulation.FormulationModule
import com.feederp.ui.inventory.InventoryModule
import com.feederp.ui.maintenance.MaintenanceModule
import com.feederp.ui.master.MasterModule
import com.feederp.ui.production.ProductionModule
import com.feederp.ui.qc.QcModule
import com.feederp.ui.sales.SalesModule
import com.feederp.ui.sync.SyncModule
import com.feederp.ui.purchase.PurchaseModule
import com.feederp.ui.periodreport.PeriodReportModule

/**
 * সব মডিউলের একটি তালিকা। হোম মেনু ও নেভিগেশন দুটোই এখান থেকে তৈরি হয়।
 * নতুন মডিউল তৈরি হলে শুধু এখানে এক লাইন যোগ করলেই মেনু ও রুট চালু হয়।
 *
 * বর্তমানে চালু: মাস্টার ডাটা, ইনভেন্টরি, ক্রয় (ধাপ ১)।
 * পরের ধাপে যোগ হবে: Formulation, Production, QC, Sales, Delivery (ধাপ ২);
 * Accounts, Reports, Maintenance, Distribution, HR, R&D, Sync (ধাপ ৩)।
 */
object AppModules {
    val all: List<ModuleEntry> = listOf(
        MasterModule,
        InventoryModule,
        PurchaseModule,
        FormulationModule,
        ProductionModule,
        QcModule,
        SalesModule,
        DeliveryModule,
        AccountsModule,
        DistributionModule,
        MaintenanceModule,
        HrModule,
        RndModule,
        SyncModule,
        BackupModule,
        PeriodReportModule,
    )
}
