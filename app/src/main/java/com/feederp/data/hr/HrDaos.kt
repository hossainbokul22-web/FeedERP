package com.feederp.data.hr

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HrEmployeeDao {
    @Query("SELECT * FROM hr_employees WHERE deletedAt IS NULL ORDER BY empNo")
    fun observeAll(): Flow<List<HrEmployeeEntity>>

    @Query("SELECT * FROM hr_employees WHERE deletedAt IS NULL ORDER BY empNo")
    suspend fun all(): List<HrEmployeeEntity>

    @Query("SELECT * FROM hr_employees WHERE deletedAt IS NULL AND status = 'ACTIVE' ORDER BY empNo")
    suspend fun allActive(): List<HrEmployeeEntity>

    @Query("SELECT * FROM hr_employees WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HrEmployeeEntity?

    @Query("SELECT * FROM hr_employees WHERE empNo = :empNo LIMIT 1")
    suspend fun getByEmpNo(empNo: String): HrEmployeeEntity?

    @Query("SELECT COUNT(*) FROM hr_employees")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(employee: HrEmployeeEntity)
}

@Dao
interface HrEmployeeDocDao {
    @Query("SELECT * FROM hr_employee_docs WHERE employeeId = :employeeId AND deletedAt IS NULL ORDER BY title")
    fun observeForEmployee(employeeId: String): Flow<List<HrEmployeeDocEntity>>

    /** মেয়াদ শেষের অ্যালার্টের জন্য: [untilMillis] এর মধ্যে মেয়াদ শেষ হচ্ছে (বা হয়ে গেছে) এমন কাগজ। */
    @Query("SELECT * FROM hr_employee_docs WHERE deletedAt IS NULL AND expiryAt IS NOT NULL AND expiryAt <= :untilMillis ORDER BY expiryAt")
    suspend fun expiringUntil(untilMillis: Long): List<HrEmployeeDocEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: HrEmployeeDocEntity)
}

@Dao
interface HrAttendanceDao {
    @Query("SELECT * FROM hr_attendance WHERE dayKey = :dayKey AND deletedAt IS NULL")
    fun observeDay(dayKey: Int): Flow<List<HrAttendanceEntity>>

    @Query("SELECT * FROM hr_attendance WHERE dayKey = :dayKey AND deletedAt IS NULL")
    suspend fun forDay(dayKey: Int): List<HrAttendanceEntity>

    @Query("SELECT * FROM hr_attendance WHERE employeeId = :employeeId AND dayKey BETWEEN :fromDay AND :toDay AND deletedAt IS NULL ORDER BY dayKey")
    suspend fun forEmployeeRange(employeeId: String, fromDay: Int, toDay: Int): List<HrAttendanceEntity>

    @Query("SELECT * FROM hr_attendance WHERE dayKey BETWEEN :fromDay AND :toDay AND deletedAt IS NULL ORDER BY dayKey")
    suspend fun inRange(fromDay: Int, toDay: Int): List<HrAttendanceEntity>

    @Query("SELECT * FROM hr_attendance WHERE employeeId = :employeeId AND dayKey = :dayKey LIMIT 1")
    suspend fun find(employeeId: String, dayKey: Int): HrAttendanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: HrAttendanceEntity)
}

@Dao
interface HrLeaveTypeDao {
    @Query("SELECT * FROM hr_leave_types WHERE deletedAt IS NULL ORDER BY name")
    fun observeAll(): Flow<List<HrLeaveTypeEntity>>

    @Query("SELECT * FROM hr_leave_types WHERE deletedAt IS NULL ORDER BY name")
    suspend fun all(): List<HrLeaveTypeEntity>

    @Query("SELECT * FROM hr_leave_types WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HrLeaveTypeEntity?

    @Query("SELECT COUNT(*) FROM hr_leave_types")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(type: HrLeaveTypeEntity)
}

@Dao
interface HrLeaveBalanceDao {
    @Query("SELECT * FROM hr_leave_balances WHERE employeeId = :employeeId AND year = :year AND deletedAt IS NULL")
    suspend fun forEmployeeYear(employeeId: String, year: Int): List<HrLeaveBalanceEntity>

    @Query("SELECT * FROM hr_leave_balances WHERE year = :year AND deletedAt IS NULL")
    suspend fun forYear(year: Int): List<HrLeaveBalanceEntity>

    @Query("SELECT * FROM hr_leave_balances WHERE employeeId = :employeeId AND leaveTypeId = :leaveTypeId AND year = :year LIMIT 1")
    suspend fun find(employeeId: String, leaveTypeId: String, year: Int): HrLeaveBalanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(balance: HrLeaveBalanceEntity)
}

@Dao
interface HrLeaveRequestDao {
    @Query("SELECT * FROM hr_leave_requests WHERE deletedAt IS NULL ORDER BY fromDay DESC")
    fun observeAll(): Flow<List<HrLeaveRequestEntity>>

    @Query("SELECT * FROM hr_leave_requests WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HrLeaveRequestEntity?

    @Query("SELECT * FROM hr_leave_requests WHERE employeeId = :employeeId AND deletedAt IS NULL AND status IN ('PENDING','APPROVED')")
    suspend fun activeForEmployee(employeeId: String): List<HrLeaveRequestEntity>

    @Query("SELECT * FROM hr_leave_requests WHERE employeeId = :employeeId AND status = 'APPROVED' AND deletedAt IS NULL AND fromDay BETWEEN :fromDay AND :toDay")
    suspend fun approvedStartingBetween(employeeId: String, fromDay: Int, toDay: Int): List<HrLeaveRequestEntity>

    @Query("SELECT * FROM hr_leave_requests WHERE status = 'APPROVED' AND deletedAt IS NULL AND toDay >= :fromDay AND fromDay <= :toDay")
    suspend fun approvedOverlapping(fromDay: Int, toDay: Int): List<HrLeaveRequestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: HrLeaveRequestEntity)
}

@Dao
interface HrHolidayDao {
    @Query("SELECT * FROM hr_holidays WHERE deletedAt IS NULL ORDER BY dayKey")
    fun observeAll(): Flow<List<HrHolidayEntity>>

    @Query("SELECT * FROM hr_holidays WHERE deletedAt IS NULL AND dayKey BETWEEN :fromDay AND :toDay ORDER BY dayKey")
    suspend fun inRange(fromDay: Int, toDay: Int): List<HrHolidayEntity>

    @Query("SELECT * FROM hr_holidays WHERE dayKey = :dayKey LIMIT 1")
    suspend fun findByDay(dayKey: Int): HrHolidayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(holiday: HrHolidayEntity)
}

@Dao
interface HrSalaryStructureDao {
    @Query("SELECT * FROM hr_salary_structures WHERE employeeId = :employeeId AND deletedAt IS NULL ORDER BY effectiveFrom DESC")
    fun observeForEmployee(employeeId: String): Flow<List<HrSalaryStructureEntity>>

    @Query("SELECT * FROM hr_salary_structures WHERE employeeId = :employeeId AND deletedAt IS NULL ORDER BY effectiveFrom DESC")
    suspend fun forEmployee(employeeId: String): List<HrSalaryStructureEntity>

    @Query("SELECT * FROM hr_salary_structures WHERE deletedAt IS NULL")
    suspend fun all(): List<HrSalaryStructureEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(structure: HrSalaryStructureEntity)
}

@Dao
interface HrBonusDao {
    @Query("SELECT * FROM hr_bonuses WHERE payMonth = :payMonth AND deletedAt IS NULL")
    suspend fun forMonth(payMonth: Int): List<HrBonusEntity>

    @Query("SELECT * FROM hr_bonuses WHERE payMonth = :payMonth AND deletedAt IS NULL")
    fun observeForMonth(payMonth: Int): Flow<List<HrBonusEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bonus: HrBonusEntity)
}

@Dao
interface HrDeductionDao {
    @Query("SELECT * FROM hr_deductions WHERE payMonth = :payMonth AND deletedAt IS NULL")
    suspend fun forMonth(payMonth: Int): List<HrDeductionEntity>

    @Query("SELECT * FROM hr_deductions WHERE payMonth = :payMonth AND deletedAt IS NULL")
    fun observeForMonth(payMonth: Int): Flow<List<HrDeductionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(deduction: HrDeductionEntity)
}

@Dao
interface HrPieceWorkDao {
    @Query("SELECT * FROM hr_piece_work WHERE dayKey BETWEEN :fromDay AND :toDay AND deletedAt IS NULL ORDER BY dayKey DESC")
    suspend fun inRange(fromDay: Int, toDay: Int): List<HrPieceWorkEntity>

    @Query("SELECT * FROM hr_piece_work WHERE dayKey BETWEEN :fromDay AND :toDay AND deletedAt IS NULL ORDER BY dayKey DESC")
    fun observeRange(fromDay: Int, toDay: Int): Flow<List<HrPieceWorkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(work: HrPieceWorkEntity)
}

@Dao
interface HrPayrollRunDao {
    @Query("SELECT * FROM hr_payroll_runs WHERE deletedAt IS NULL ORDER BY month DESC")
    fun observeAll(): Flow<List<HrPayrollRunEntity>>

    @Query("SELECT * FROM hr_payroll_runs WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HrPayrollRunEntity?

    @Query("SELECT * FROM hr_payroll_runs WHERE month = :month LIMIT 1")
    suspend fun getByMonth(month: Int): HrPayrollRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: HrPayrollRunEntity)
}

@Dao
interface HrPayslipDao {
    @Query("SELECT * FROM hr_payslips WHERE runId = :runId AND deletedAt IS NULL ORDER BY department, employeeId")
    fun observeForRun(runId: String): Flow<List<HrPayslipEntity>>

    @Query("SELECT * FROM hr_payslips WHERE runId = :runId AND deletedAt IS NULL")
    suspend fun forRun(runId: String): List<HrPayslipEntity>

    /** ডিলিটেড সহ (আইডি পুনর্ব্যবহারের জন্য, কারণ runId+employeeId ইউনিক)। */
    @Query("SELECT * FROM hr_payslips WHERE runId = :runId")
    suspend fun forRunIncludingDeleted(runId: String): List<HrPayslipEntity>

    @Query("SELECT * FROM hr_payslips WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HrPayslipEntity?

    @Query("SELECT * FROM hr_payslips WHERE employeeId = :employeeId AND deletedAt IS NULL ORDER BY month DESC")
    fun observeForEmployee(employeeId: String): Flow<List<HrPayslipEntity>>

    @Query("SELECT * FROM hr_payslips WHERE month BETWEEN :fromMonth AND :toMonth AND deletedAt IS NULL")
    suspend fun inMonths(fromMonth: Int, toMonth: Int): List<HrPayslipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slip: HrPayslipEntity)
}

@Dao
interface HrLoanDao {
    @Query("SELECT * FROM hr_loans WHERE deletedAt IS NULL ORDER BY issuedAt DESC")
    fun observeAll(): Flow<List<HrLoanEntity>>

    @Query("SELECT * FROM hr_loans WHERE deletedAt IS NULL AND status = 'ACTIVE'")
    suspend fun allActive(): List<HrLoanEntity>

    @Query("SELECT * FROM hr_loans WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): HrLoanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(loan: HrLoanEntity)

    @Query("SELECT * FROM hr_loan_repayments")
    fun observeRepayments(): Flow<List<HrLoanRepaymentEntity>>

    @Query("SELECT * FROM hr_loan_repayments")
    suspend fun allRepayments(): List<HrLoanRepaymentEntity>

    @Query("SELECT COALESCE(SUM(amountPaisa), 0) FROM hr_loan_repayments WHERE loanId = :loanId")
    suspend fun repaidPaisa(loanId: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRepayment(repayment: HrLoanRepaymentEntity)
}

@Dao
interface HrTrainingDao {
    @Query("SELECT * FROM hr_trainings WHERE deletedAt IS NULL ORDER BY conductedAt DESC")
    fun observeAll(): Flow<List<HrTrainingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(training: HrTrainingEntity)

    @Query("SELECT * FROM hr_training_attendees WHERE trainingId = :trainingId AND deletedAt IS NULL")
    suspend fun attendees(trainingId: String): List<HrTrainingAttendeeEntity>

    @Query("SELECT * FROM hr_training_attendees WHERE trainingId = :trainingId AND employeeId = :employeeId LIMIT 1")
    suspend fun findAttendee(trainingId: String, employeeId: String): HrTrainingAttendeeEntity?

    @Query("SELECT * FROM hr_training_attendees WHERE deletedAt IS NULL AND certificateExpiryAt IS NOT NULL AND certificateExpiryAt <= :untilMillis ORDER BY certificateExpiryAt")
    suspend fun certificatesExpiringUntil(untilMillis: Long): List<HrTrainingAttendeeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttendee(attendee: HrTrainingAttendeeEntity)
}
