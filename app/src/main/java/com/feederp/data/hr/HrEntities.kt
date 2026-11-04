package com.feederp.data.hr

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * HR এন্টিটি। নিয়ম: সব টেবিলের নাম `hr_` দিয়ে শুরু (সিঙ্ক এজেন্ট এগুলো শুধু HR/Owner ডিভাইসে পাঠায়)।
 * টাকা = Long পয়সা। তারিখ = Long epoch millis; দিনভিত্তিক রেকর্ডে `dayKey` = yyyyMMdd (Int), মাস = yyyyMM (Int)।
 */

enum class EmploymentType { PERMANENT, TEMPORARY, DAILY, CONTRACT }

enum class EmployeeStatus { ACTIVE, RESIGNED, TERMINATED }

/** PRESENT, ABSENT, LEAVE (অনুমোদিত ছুটি), HOLIDAY (সরকারি/সাপ্তাহিক ছুটি কিন্তু উপস্থিত নয়)। */
enum class AttendanceStatus { PRESENT, ABSENT, LEAVE, HOLIDAY }

enum class LeaveStatus { PENDING, APPROVED, REJECTED, CANCELLED }

/** MONTHLY = মাসিক বেতন, DAILY = দৈনিক মজুরি × উপস্থিতি, PIECE = উৎপাদন/পিস-রেট। */
enum class PayBasis { MONTHLY, DAILY, PIECE }

enum class PayrollStatus { DRAFT, APPROVED, PAID }

/** ADVANCE = অগ্রিম, LOAN = ঋণ। */
enum class LoanKind { ADVANCE, LOAN }

enum class LoanStatus { ACTIVE, CLEARED }

@Entity(
    tableName = "hr_employees",
    indices = [Index("empNo", unique = true), Index("department"), Index("status"), Index("employmentType")],
)
data class HrEmployeeEntity(
    @PrimaryKey val id: String,
    val empNo: String,
    val name: String,
    val department: String,
    val designation: String,
    val joinDate: Long,
    val employmentType: EmploymentType,
    val status: EmployeeStatus = EmployeeStatus.ACTIVE,
    val nid: String? = null,
    val phone: String? = null,
    /** ব্যাংক/মোবাইল ব্যাংকিং অ্যাকাউন্ট নম্বর। */
    val payAccount: String? = null,
    /** BANK | MOBILE_BANKING | CASH */
    val payMethod: String = "CASH",
    val emergencyContact: String? = null,
    val photoPath: String? = null,
    /** চুক্তি/অস্থায়ী কর্মীর মেয়াদ শেষের তারিখ (অ্যালার্টের জন্য)। */
    val contractEndAt: Long? = null,
    val exitAt: Long? = null,
    val exitReason: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** কর্মীর কাগজপত্র (ফাইল পাথ) ও লাইসেন্স/সনদের মেয়াদ। */
@Entity(tableName = "hr_employee_docs", indices = [Index("employeeId"), Index("expiryAt")])
data class HrEmployeeDocEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val title: String,
    val filePath: String? = null,
    val expiryAt: Long? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** দৈনিক হাজিরা: কর্মী+দিন প্রতি একটি রেকর্ড। */
@Entity(
    tableName = "hr_attendance",
    indices = [Index(value = ["employeeId", "dayKey"], unique = true), Index("dayKey")],
)
data class HrAttendanceEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val dayKey: Int,
    val status: AttendanceStatus,
    val shift: String = "DAY",
    val lateMinutes: Int = 0,
    val overtimeMinutes: Int = 0,
    /** ইমপোর্ট উৎস: MANUAL | PHONE | DEVICE */
    val source: String = "MANUAL",
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "hr_leave_types", indices = [Index("name", unique = true)])
data class HrLeaveTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val defaultAnnualDays: Double,
    /** সবেতন ছুটি হলে বেতন কাটা যাবে না। */
    val paid: Boolean = true,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** কর্মী-বছর-ছুটির ধরন অনুযায়ী প্রাপ্য দিন। ব্যবহৃত দিন অনুমোদিত আবেদন থেকে হিসাব হয়। */
@Entity(
    tableName = "hr_leave_balances",
    indices = [Index(value = ["employeeId", "leaveTypeId", "year"], unique = true)],
)
data class HrLeaveBalanceEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val leaveTypeId: String,
    val year: Int,
    val entitledDays: Double,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "hr_leave_requests",
    indices = [Index("employeeId"), Index("status"), Index("fromDay"), Index("leaveTypeId")],
)
data class HrLeaveRequestEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val leaveTypeId: String,
    val fromDay: Int,
    val toDay: Int,
    /** কার্যদিবস গণনা (সাপ্তাহিক ও সরকারি ছুটি বাদ)। */
    val days: Double,
    val reason: String? = null,
    val status: LeaveStatus = LeaveStatus.PENDING,
    val requestedBy: String,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val decisionNote: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** সরকারি/প্রতিষ্ঠান ছুটির ক্যালেন্ডার। সাপ্তাহিক ছুটি কোডে (শুক্রবার) ধরা হয়। */
@Entity(tableName = "hr_holidays", indices = [Index("dayKey", unique = true)])
data class HrHolidayEntity(
    @PrimaryKey val id: String,
    val dayKey: Int,
    val name: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** বেতন কাঠামো; কর্মীর সবচেয়ে সাম্প্রতিক `effectiveFrom` (<= মাসের শেষ) ব্যবহৃত হয়। */
@Entity(tableName = "hr_salary_structures", indices = [Index("employeeId"), Index("effectiveFrom")])
data class HrSalaryStructureEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val effectiveFrom: Long,
    val payBasis: PayBasis,
    val basicPaisa: Long = 0,
    val houseAllowancePaisa: Long = 0,
    val medicalAllowancePaisa: Long = 0,
    val transportAllowancePaisa: Long = 0,
    val otherAllowancePaisa: Long = 0,
    /** ওভারটাইম রেট পয়সা/ঘণ্টা; 0 হলে মূল বেতন থেকে ডিফল্ট হিসাব। */
    val overtimeRatePaisaPerHour: Long = 0,
    /** দৈনিক মজুরি (পয়সা/দিন), DAILY ভিত্তির জন্য। */
    val dailyWagePaisa: Long = 0,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** উৎসব বোনাস ইত্যাদি; `payMonth` (yyyyMM) এর পে-রোলে যুক্ত হয়। */
@Entity(tableName = "hr_bonuses", indices = [Index("employeeId"), Index("payMonth")])
data class HrBonusEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val payMonth: Int,
    val title: String,
    val amountPaisa: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** জরিমানা/অন্যান্য কর্তন; `payMonth` এর পে-রোল থেকে কাটা হয়। */
@Entity(tableName = "hr_deductions", indices = [Index("employeeId"), Index("payMonth")])
data class HrDeductionEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val payMonth: Int,
    /** FINE | OTHER */
    val kind: String,
    val reason: String,
    val amountPaisa: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** উৎপাদন/পিস-রেট কাজের রেকর্ড; PIECE ভিত্তির কর্মীর মজুরি ও প্রোডাকশন শ্রম-খরচের উৎস। */
@Entity(tableName = "hr_piece_work", indices = [Index("employeeId"), Index("dayKey"), Index("department")])
data class HrPieceWorkEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val dayKey: Int,
    val department: String,
    val quantity: Double,
    val ratePaisaPerUnit: Long,
    val amountPaisa: Long,
    val unitLabel: String = "কেজি",
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "hr_payroll_runs", indices = [Index("month", unique = true), Index("status")])
data class HrPayrollRunEntity(
    @PrimaryKey val id: String,
    val month: Int,
    val status: PayrollStatus = PayrollStatus.DRAFT,
    val totalGrossPaisa: Long = 0,
    val totalDeductionPaisa: Long = 0,
    val totalNetPaisa: Long = 0,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
    val accrualVoucherId: String? = null,
    val paidAt: Long? = null,
    val paymentVoucherIds: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "hr_payslips",
    indices = [Index("runId"), Index("employeeId"), Index("month"), Index(value = ["runId", "employeeId"], unique = true)],
)
data class HrPayslipEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val employeeId: String,
    val month: Int,
    val department: String,
    val payBasis: PayBasis,
    val daysInMonth: Int,
    val workingDays: Int,
    val presentDays: Double,
    val paidLeaveDays: Double,
    val absentDays: Double,
    val lateCount: Int,
    val overtimeMinutes: Int,
    val basicPaisa: Long,
    val allowancesPaisa: Long,
    val overtimePaisa: Long,
    val bonusPaisa: Long,
    val pieceWagePaisa: Long,
    val grossPaisa: Long,
    val absentDeductionPaisa: Long,
    val loanDeductionPaisa: Long,
    val advanceDeductionPaisa: Long,
    val fineDeductionPaisa: Long,
    val totalDeductionPaisa: Long,
    val netPaisa: Long,
    val paidAt: Long? = null,
    val payMethod: String = "CASH",
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** অগ্রিম/ঋণ। ব্যালেন্স = principalPaisa − কিস্তি পরিশোধের যোগফল (hr_loan_repayments)। */
@Entity(tableName = "hr_loans", indices = [Index("employeeId"), Index("status")])
data class HrLoanEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val kind: LoanKind,
    val principalPaisa: Long,
    val installmentPaisa: Long,
    val issuedAt: Long,
    val method: String = "CASH",
    val voucherId: String? = null,
    val status: LoanStatus = LoanStatus.ACTIVE,
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

/** অ্যাপেন্ড-অনলি: বেতন অনুমোদনে কাটা কিস্তি। */
@Entity(tableName = "hr_loan_repayments", indices = [Index("loanId"), Index("payslipId")])
data class HrLoanRepaymentEntity(
    @PrimaryKey val id: String,
    val loanId: String,
    val payslipId: String,
    val amountPaisa: Long,
    val createdAt: Long,
    val deviceId: String,
    val createdBy: String,
)

/** kind: TRAINING | SAFETY */
@Entity(tableName = "hr_trainings", indices = [Index("conductedAt")])
data class HrTrainingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val conductedAt: Long,
    val durationHours: Double = 0.0,
    val trainer: String? = null,
    val note: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(
    tableName = "hr_training_attendees",
    indices = [Index("trainingId"), Index("employeeId"), Index(value = ["trainingId", "employeeId"], unique = true)],
)
data class HrTrainingAttendeeEntity(
    @PrimaryKey val id: String,
    val trainingId: String,
    val employeeId: String,
    /** সেফটি সনদ নবায়নের তারিখ (ঐচ্ছিক)। */
    val certificateExpiryAt: Long? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)
