package com.feederp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.feederp.core.NumberCounterDao
import com.feederp.core.NumberCounterEntity
import com.feederp.data.accounts.AccountDao
import com.feederp.data.accounts.AccountEntity
import com.feederp.data.accounts.ExpenseDao
import com.feederp.data.accounts.ExpenseEntity
import com.feederp.data.accounts.LedgerDao
import com.feederp.data.accounts.LedgerEntryEntity
import com.feederp.data.distribution.CashReconciliationEntity
import com.feederp.data.distribution.DealerAreaDao
import com.feederp.data.distribution.DealerAreaEntity
import com.feederp.data.distribution.DeliveryAllocationEntity
import com.feederp.data.distribution.DeliveryCollectionEntity
import com.feederp.data.distribution.DeliveryDao
import com.feederp.data.distribution.DeliveryLineEntity
import com.feederp.data.distribution.DeliveryNoteEntity
import com.feederp.data.distribution.DeliveryPodEntity
import com.feederp.data.distribution.DeliveryReturnEntity
import com.feederp.data.distribution.DistFinanceDao
import com.feederp.data.distribution.DriverDao
import com.feederp.data.distribution.DriverEntity
import com.feederp.data.distribution.LocalGpsPointEntity
import com.feederp.data.distribution.TerritoryDao
import com.feederp.data.distribution.TerritoryEntity
import com.feederp.data.distribution.TransportCostEntity
import com.feederp.data.distribution.TripDao
import com.feederp.data.distribution.TripEntity
import com.feederp.data.distribution.TripStopEntity
import com.feederp.data.distribution.VehicleDao
import com.feederp.data.distribution.VehicleEntity
import com.feederp.data.formulation.FormulaDao
import com.feederp.data.formulation.FormulaEntity
import com.feederp.data.formulation.FormulaLineEntity
import com.feederp.data.formulation.RestrictedItemDao
import com.feederp.data.formulation.RestrictedItemEntity
import com.feederp.data.hr.HrAttendanceDao
import com.feederp.data.hr.HrAttendanceEntity
import com.feederp.data.hr.HrBonusDao
import com.feederp.data.hr.HrBonusEntity
import com.feederp.data.hr.HrDeductionDao
import com.feederp.data.hr.HrDeductionEntity
import com.feederp.data.hr.HrEmployeeDao
import com.feederp.data.hr.HrEmployeeDocDao
import com.feederp.data.hr.HrEmployeeDocEntity
import com.feederp.data.hr.HrEmployeeEntity
import com.feederp.data.hr.HrHolidayDao
import com.feederp.data.hr.HrHolidayEntity
import com.feederp.data.hr.HrLeaveBalanceDao
import com.feederp.data.hr.HrLeaveBalanceEntity
import com.feederp.data.hr.HrLeaveRequestDao
import com.feederp.data.hr.HrLeaveRequestEntity
import com.feederp.data.hr.HrLeaveTypeDao
import com.feederp.data.hr.HrLeaveTypeEntity
import com.feederp.data.hr.HrLoanDao
import com.feederp.data.hr.HrLoanEntity
import com.feederp.data.hr.HrLoanRepaymentEntity
import com.feederp.data.hr.HrPayrollRunDao
import com.feederp.data.hr.HrPayrollRunEntity
import com.feederp.data.hr.HrPayslipDao
import com.feederp.data.hr.HrPayslipEntity
import com.feederp.data.hr.HrPieceWorkDao
import com.feederp.data.hr.HrPieceWorkEntity
import com.feederp.data.hr.HrSalaryStructureDao
import com.feederp.data.hr.HrSalaryStructureEntity
import com.feederp.data.hr.HrTrainingAttendeeEntity
import com.feederp.data.hr.HrTrainingDao
import com.feederp.data.hr.HrTrainingEntity
import com.feederp.data.inventory.LotDao
import com.feederp.data.inventory.LotEntity
import com.feederp.data.maintenance.CalibrationLogDao
import com.feederp.data.maintenance.CalibrationLogEntity
import com.feederp.data.maintenance.MachineDao
import com.feederp.data.maintenance.MachineDepreciationEntryDao
import com.feederp.data.maintenance.MachineDepreciationEntryEntity
import com.feederp.data.maintenance.MachineEntity
import com.feederp.data.maintenance.MachineRunLogDao
import com.feederp.data.maintenance.MachineRunLogEntity
import com.feederp.data.maintenance.MaintPurchaseRequestDao
import com.feederp.data.maintenance.MaintPurchaseRequestEntity
import com.feederp.data.maintenance.MaintenanceScheduleDao
import com.feederp.data.maintenance.MaintenanceScheduleEntity
import com.feederp.data.maintenance.MeterReadingDao
import com.feederp.data.maintenance.MeterReadingEntity
import com.feederp.data.maintenance.SparePartRequisitionDao
import com.feederp.data.maintenance.SparePartRequisitionEntity
import com.feederp.data.maintenance.VehicleServiceReminderDao
import com.feederp.data.maintenance.VehicleServiceReminderEntity
import com.feederp.data.maintenance.WorkOrderChecklistDao
import com.feederp.data.maintenance.WorkOrderChecklistItemEntity
import com.feederp.data.maintenance.WorkOrderDao
import com.feederp.data.maintenance.WorkOrderEntity
import com.feederp.data.production.BatchConsumptionEntity
import com.feederp.data.production.BatchMachineRunEntity
import com.feederp.data.production.CleaningChecklistItemEntity
import com.feederp.data.production.ProductionBatchEntity
import com.feederp.data.production.ProductionDao
import com.feederp.data.purchase.GoodsReceiptDao
import com.feederp.data.purchase.GoodsReceiptEntity
import com.feederp.data.purchase.GoodsReceiptLineEntity
import com.feederp.data.purchase.PurchaseOrderDao
import com.feederp.data.purchase.PurchaseOrderEntity
import com.feederp.data.purchase.PurchaseOrderLineEntity
import com.feederp.data.purchase.SupplierPaymentDao
import com.feederp.data.purchase.SupplierPaymentEntity
import com.feederp.data.qc.QcDao
import com.feederp.data.qc.QcTestEntity
import com.feederp.data.rnd.FeedTrialDao
import com.feederp.data.rnd.FeedTrialRecordEntity
import com.feederp.data.rnd.FieldFeedbackDao
import com.feederp.data.rnd.FieldFeedbackEntity
import com.feederp.data.rnd.FormulaTrialDao
import com.feederp.data.rnd.FormulaTrialEntity
import com.feederp.data.sales.CollectionDao
import com.feederp.data.sales.CollectionEntity
import com.feederp.data.sales.ItemPriceDao
import com.feederp.data.sales.ItemPriceEntity
import com.feederp.data.sales.SalesInvoiceDao
import com.feederp.data.sales.SalesInvoiceEntity
import com.feederp.data.sales.SalesInvoiceLineEntity
import com.feederp.data.sales.SalesReturnDao
import com.feederp.data.sales.SalesReturnEntity
import com.feederp.data.sales.SalesReturnLineEntity

/**
* অ্যাপের একমাত্র Room ডাটাবেস (SQLCipher দিয়ে এনক্রিপ্টেড)। এই ফাইলটি সব @Entity ও @Dao থেকে তৈরি;
* নতুন এন্টিটি/DAO যোগ করলে এখানে যোগ করে `version` বাড়াতে হবে এবং Migration লিখতে হবে।
*/
@Database(
    entities = [
        NumberCounterEntity::class,
        AuditLogEntity::class,
        ItemEntity::class,
        PartyEntity::class,
        StockMovementEntity::class,
        UnitEntity::class,
        UserEntity::class,
        WarehouseEntity::class,
        AccountEntity::class,
        ExpenseEntity::class,
        LedgerEntryEntity::class,
        CashReconciliationEntity::class,
        DealerAreaEntity::class,
        DeliveryAllocationEntity::class,
        DeliveryCollectionEntity::class,
        DeliveryLineEntity::class,
        DeliveryNoteEntity::class,
        DeliveryPodEntity::class,
        DeliveryReturnEntity::class,
        DriverEntity::class,
        LocalGpsPointEntity::class,
        TerritoryEntity::class,
        TransportCostEntity::class,
        TripEntity::class,
        TripStopEntity::class,
        VehicleEntity::class,
        FormulaEntity::class,
        FormulaLineEntity::class,
        RestrictedItemEntity::class,
        HrAttendanceEntity::class,
        HrBonusEntity::class,
        HrDeductionEntity::class,
        HrEmployeeDocEntity::class,
        HrEmployeeEntity::class,
        HrHolidayEntity::class,
        HrLeaveBalanceEntity::class,
        HrLeaveRequestEntity::class,
        HrLeaveTypeEntity::class,
        HrLoanEntity::class,
        HrLoanRepaymentEntity::class,
        HrPayrollRunEntity::class,
        HrPayslipEntity::class,
        HrPieceWorkEntity::class,
        HrSalaryStructureEntity::class,
        HrTrainingAttendeeEntity::class,
        HrTrainingEntity::class,
        LotEntity::class,
        CalibrationLogEntity::class,
        MachineDepreciationEntryEntity::class,
        MachineEntity::class,
        MachineRunLogEntity::class,
        MaintPurchaseRequestEntity::class,
        MaintenanceScheduleEntity::class,
        MeterReadingEntity::class,
        SparePartRequisitionEntity::class,
        VehicleServiceReminderEntity::class,
        WorkOrderChecklistItemEntity::class,
        WorkOrderEntity::class,
        BatchConsumptionEntity::class,
        BatchMachineRunEntity::class,
        CleaningChecklistItemEntity::class,
        ProductionBatchEntity::class,
        GoodsReceiptEntity::class,
        GoodsReceiptLineEntity::class,
        PurchaseOrderEntity::class,
        PurchaseOrderLineEntity::class,
        SupplierPaymentEntity::class,
        QcTestEntity::class,
        FeedTrialRecordEntity::class,
        FieldFeedbackEntity::class,
        FormulaTrialEntity::class,
        CollectionEntity::class,
        ItemPriceEntity::class,
        SalesInvoiceEntity::class,
        SalesInvoiceLineEntity::class,
        SalesReturnEntity::class,
        SalesReturnLineEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun numberCounterDao(): NumberCounterDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun itemDao(): ItemDao
    abstract fun partyDao(): PartyDao
    abstract fun stockMovementDao(): StockMovementDao
    abstract fun unitDao(): UnitDao
    abstract fun userDao(): UserDao
    abstract fun warehouseDao(): WarehouseDao
    abstract fun accountDao(): AccountDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun dealerAreaDao(): DealerAreaDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun distFinanceDao(): DistFinanceDao
    abstract fun driverDao(): DriverDao
    abstract fun territoryDao(): TerritoryDao
    abstract fun tripDao(): TripDao
    abstract fun vehicleDao(): VehicleDao
    abstract fun formulaDao(): FormulaDao
    abstract fun restrictedItemDao(): RestrictedItemDao
    abstract fun hrAttendanceDao(): HrAttendanceDao
    abstract fun hrBonusDao(): HrBonusDao
    abstract fun hrDeductionDao(): HrDeductionDao
    abstract fun hrEmployeeDao(): HrEmployeeDao
    abstract fun hrEmployeeDocDao(): HrEmployeeDocDao
    abstract fun hrHolidayDao(): HrHolidayDao
    abstract fun hrLeaveBalanceDao(): HrLeaveBalanceDao
    abstract fun hrLeaveRequestDao(): HrLeaveRequestDao
    abstract fun hrLeaveTypeDao(): HrLeaveTypeDao
    abstract fun hrLoanDao(): HrLoanDao
    abstract fun hrPayrollRunDao(): HrPayrollRunDao
    abstract fun hrPayslipDao(): HrPayslipDao
    abstract fun hrPieceWorkDao(): HrPieceWorkDao
    abstract fun hrSalaryStructureDao(): HrSalaryStructureDao
    abstract fun hrTrainingDao(): HrTrainingDao
    abstract fun lotDao(): LotDao
    abstract fun calibrationLogDao(): CalibrationLogDao
    abstract fun machineDao(): MachineDao
    abstract fun machineDepreciationEntryDao(): MachineDepreciationEntryDao
    abstract fun machineRunLogDao(): MachineRunLogDao
    abstract fun maintPurchaseRequestDao(): MaintPurchaseRequestDao
    abstract fun maintenanceScheduleDao(): MaintenanceScheduleDao
    abstract fun meterReadingDao(): MeterReadingDao
    abstract fun sparePartRequisitionDao(): SparePartRequisitionDao
    abstract fun vehicleServiceReminderDao(): VehicleServiceReminderDao
    abstract fun workOrderChecklistDao(): WorkOrderChecklistDao
    abstract fun workOrderDao(): WorkOrderDao
    abstract fun productionDao(): ProductionDao
    abstract fun goodsReceiptDao(): GoodsReceiptDao
    abstract fun purchaseOrderDao(): PurchaseOrderDao
    abstract fun supplierPaymentDao(): SupplierPaymentDao
    abstract fun qcDao(): QcDao
    abstract fun feedTrialDao(): FeedTrialDao
    abstract fun fieldFeedbackDao(): FieldFeedbackDao
    abstract fun formulaTrialDao(): FormulaTrialDao
    abstract fun collectionDao(): CollectionDao
    abstract fun itemPriceDao(): ItemPriceDao
    abstract fun salesInvoiceDao(): SalesInvoiceDao
    abstract fun salesReturnDao(): SalesReturnDao
}
