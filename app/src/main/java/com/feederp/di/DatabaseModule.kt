package com.feederp.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.feederp.core.DbKeyProvider
import com.feederp.data.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton
import com.feederp.core.NumberCounterDao
import com.feederp.data.AuditLogDao
import com.feederp.data.ItemDao
import com.feederp.data.PartyDao
import com.feederp.data.StockMovementDao
import com.feederp.data.UnitDao
import com.feederp.data.UserDao
import com.feederp.data.WarehouseDao
import com.feederp.data.accounts.AccountDao
import com.feederp.data.accounts.ExpenseDao
import com.feederp.data.accounts.LedgerDao
import com.feederp.data.distribution.DealerAreaDao
import com.feederp.data.distribution.DeliveryDao
import com.feederp.data.distribution.DistFinanceDao
import com.feederp.data.distribution.DriverDao
import com.feederp.data.distribution.TerritoryDao
import com.feederp.data.distribution.TripDao
import com.feederp.data.distribution.VehicleDao
import com.feederp.data.formulation.FormulaDao
import com.feederp.data.formulation.RestrictedItemDao
import com.feederp.data.hr.HrAttendanceDao
import com.feederp.data.hr.HrBonusDao
import com.feederp.data.hr.HrDeductionDao
import com.feederp.data.hr.HrEmployeeDao
import com.feederp.data.hr.HrEmployeeDocDao
import com.feederp.data.hr.HrHolidayDao
import com.feederp.data.hr.HrLeaveBalanceDao
import com.feederp.data.hr.HrLeaveRequestDao
import com.feederp.data.hr.HrLeaveTypeDao
import com.feederp.data.hr.HrLoanDao
import com.feederp.data.hr.HrPayrollRunDao
import com.feederp.data.hr.HrPayslipDao
import com.feederp.data.hr.HrPieceWorkDao
import com.feederp.data.hr.HrSalaryStructureDao
import com.feederp.data.hr.HrTrainingDao
import com.feederp.data.inventory.LotDao
import com.feederp.data.maintenance.CalibrationLogDao
import com.feederp.data.maintenance.MachineDao
import com.feederp.data.maintenance.MachineDepreciationEntryDao
import com.feederp.data.maintenance.MachineRunLogDao
import com.feederp.data.maintenance.MaintPurchaseRequestDao
import com.feederp.data.maintenance.MaintenanceScheduleDao
import com.feederp.data.maintenance.MeterReadingDao
import com.feederp.data.maintenance.SparePartRequisitionDao
import com.feederp.data.maintenance.VehicleServiceReminderDao
import com.feederp.data.maintenance.WorkOrderChecklistDao
import com.feederp.data.maintenance.WorkOrderDao
import com.feederp.data.production.ProductionDao
import com.feederp.data.purchase.GoodsReceiptDao
import com.feederp.data.purchase.PurchaseOrderDao
import com.feederp.data.purchase.SupplierPaymentDao
import com.feederp.data.qc.QcDao
import com.feederp.data.rnd.FeedTrialDao
import com.feederp.data.rnd.FieldFeedbackDao
import com.feederp.data.rnd.FormulaTrialDao
import com.feederp.data.sales.CollectionDao
import com.feederp.data.sales.ItemPriceDao
import com.feederp.data.sales.SalesInvoiceDao
import com.feederp.data.sales.SalesReturnDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun appDatabase(@ApplicationContext context: Context, keys: DbKeyProvider): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "feederp.db")
            .openHelperFactory(SupportOpenHelperFactory(keys.passphrase()))
            .build()

    /** সিঙ্ক/ব্যাকআপ জেনেরিক টেবিল-অ্যাক্সেসের জন্য। */
    @Provides @Singleton fun roomDatabase(db: AppDatabase): RoomDatabase = db
}

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {
    @Provides fun numberCounterDao(db: AppDatabase): NumberCounterDao = db.numberCounterDao()
    @Provides fun auditLogDao(db: AppDatabase): AuditLogDao = db.auditLogDao()
    @Provides fun itemDao(db: AppDatabase): ItemDao = db.itemDao()
    @Provides fun partyDao(db: AppDatabase): PartyDao = db.partyDao()
    @Provides fun stockMovementDao(db: AppDatabase): StockMovementDao = db.stockMovementDao()
    @Provides fun unitDao(db: AppDatabase): UnitDao = db.unitDao()
    @Provides fun userDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun warehouseDao(db: AppDatabase): WarehouseDao = db.warehouseDao()
    @Provides fun accountDao(db: AppDatabase): AccountDao = db.accountDao()
    @Provides fun expenseDao(db: AppDatabase): ExpenseDao = db.expenseDao()
    @Provides fun ledgerDao(db: AppDatabase): LedgerDao = db.ledgerDao()
    @Provides fun dealerAreaDao(db: AppDatabase): DealerAreaDao = db.dealerAreaDao()
    @Provides fun deliveryDao(db: AppDatabase): DeliveryDao = db.deliveryDao()
    @Provides fun distFinanceDao(db: AppDatabase): DistFinanceDao = db.distFinanceDao()
    @Provides fun driverDao(db: AppDatabase): DriverDao = db.driverDao()
    @Provides fun territoryDao(db: AppDatabase): TerritoryDao = db.territoryDao()
    @Provides fun tripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun vehicleDao(db: AppDatabase): VehicleDao = db.vehicleDao()
    @Provides fun formulaDao(db: AppDatabase): FormulaDao = db.formulaDao()
    @Provides fun restrictedItemDao(db: AppDatabase): RestrictedItemDao = db.restrictedItemDao()
    @Provides fun hrAttendanceDao(db: AppDatabase): HrAttendanceDao = db.hrAttendanceDao()
    @Provides fun hrBonusDao(db: AppDatabase): HrBonusDao = db.hrBonusDao()
    @Provides fun hrDeductionDao(db: AppDatabase): HrDeductionDao = db.hrDeductionDao()
    @Provides fun hrEmployeeDao(db: AppDatabase): HrEmployeeDao = db.hrEmployeeDao()
    @Provides fun hrEmployeeDocDao(db: AppDatabase): HrEmployeeDocDao = db.hrEmployeeDocDao()
    @Provides fun hrHolidayDao(db: AppDatabase): HrHolidayDao = db.hrHolidayDao()
    @Provides fun hrLeaveBalanceDao(db: AppDatabase): HrLeaveBalanceDao = db.hrLeaveBalanceDao()
    @Provides fun hrLeaveRequestDao(db: AppDatabase): HrLeaveRequestDao = db.hrLeaveRequestDao()
    @Provides fun hrLeaveTypeDao(db: AppDatabase): HrLeaveTypeDao = db.hrLeaveTypeDao()
    @Provides fun hrLoanDao(db: AppDatabase): HrLoanDao = db.hrLoanDao()
    @Provides fun hrPayrollRunDao(db: AppDatabase): HrPayrollRunDao = db.hrPayrollRunDao()
    @Provides fun hrPayslipDao(db: AppDatabase): HrPayslipDao = db.hrPayslipDao()
    @Provides fun hrPieceWorkDao(db: AppDatabase): HrPieceWorkDao = db.hrPieceWorkDao()
    @Provides fun hrSalaryStructureDao(db: AppDatabase): HrSalaryStructureDao = db.hrSalaryStructureDao()
    @Provides fun hrTrainingDao(db: AppDatabase): HrTrainingDao = db.hrTrainingDao()
    @Provides fun lotDao(db: AppDatabase): LotDao = db.lotDao()
    @Provides fun calibrationLogDao(db: AppDatabase): CalibrationLogDao = db.calibrationLogDao()
    @Provides fun machineDao(db: AppDatabase): MachineDao = db.machineDao()
    @Provides fun machineDepreciationEntryDao(db: AppDatabase): MachineDepreciationEntryDao = db.machineDepreciationEntryDao()
    @Provides fun machineRunLogDao(db: AppDatabase): MachineRunLogDao = db.machineRunLogDao()
    @Provides fun maintPurchaseRequestDao(db: AppDatabase): MaintPurchaseRequestDao = db.maintPurchaseRequestDao()
    @Provides fun maintenanceScheduleDao(db: AppDatabase): MaintenanceScheduleDao = db.maintenanceScheduleDao()
    @Provides fun meterReadingDao(db: AppDatabase): MeterReadingDao = db.meterReadingDao()
    @Provides fun sparePartRequisitionDao(db: AppDatabase): SparePartRequisitionDao = db.sparePartRequisitionDao()
    @Provides fun vehicleServiceReminderDao(db: AppDatabase): VehicleServiceReminderDao = db.vehicleServiceReminderDao()
    @Provides fun workOrderChecklistDao(db: AppDatabase): WorkOrderChecklistDao = db.workOrderChecklistDao()
    @Provides fun workOrderDao(db: AppDatabase): WorkOrderDao = db.workOrderDao()
    @Provides fun productionDao(db: AppDatabase): ProductionDao = db.productionDao()
    @Provides fun goodsReceiptDao(db: AppDatabase): GoodsReceiptDao = db.goodsReceiptDao()
    @Provides fun purchaseOrderDao(db: AppDatabase): PurchaseOrderDao = db.purchaseOrderDao()
    @Provides fun supplierPaymentDao(db: AppDatabase): SupplierPaymentDao = db.supplierPaymentDao()
    @Provides fun qcDao(db: AppDatabase): QcDao = db.qcDao()
    @Provides fun feedTrialDao(db: AppDatabase): FeedTrialDao = db.feedTrialDao()
    @Provides fun fieldFeedbackDao(db: AppDatabase): FieldFeedbackDao = db.fieldFeedbackDao()
    @Provides fun formulaTrialDao(db: AppDatabase): FormulaTrialDao = db.formulaTrialDao()
    @Provides fun collectionDao(db: AppDatabase): CollectionDao = db.collectionDao()
    @Provides fun itemPriceDao(db: AppDatabase): ItemPriceDao = db.itemPriceDao()
    @Provides fun salesInvoiceDao(db: AppDatabase): SalesInvoiceDao = db.salesInvoiceDao()
    @Provides fun salesReturnDao(db: AppDatabase): SalesReturnDao = db.salesReturnDao()
}
