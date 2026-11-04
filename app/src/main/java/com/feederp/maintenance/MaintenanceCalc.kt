package com.feederp.maintenance

import com.feederp.data.maintenance.ApprovalStatus
import com.feederp.data.maintenance.MeterType
import com.feederp.data.maintenance.ScheduleBasis
import com.feederp.data.maintenance.WorkOrderStatus
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** পরবর্তী সার্ভিসের হিসাব (রান আওয়ার বা তারিখ ভিত্তিক)। */
data class NextService(
    val basis: ScheduleBasis,
    val dueAt: Long?,
    val dueRunHours: Double?,
    val remainingDays: Long?,
    val remainingHours: Double?,
    val overdue: Boolean,
    val dueSoon: Boolean,
) {
    /** প্রিভেন্টিভ ওয়ার্ক অর্ডার তৈরির সময় হয়েছে কিনা। */
    val isDueForGeneration: Boolean get() = overdue || dueSoon
}

/** গাড়ির সার্ভিস রিমাইন্ডারের অবস্থা (কিমি বা তারিখ, যেটা আগে আসে)। */
data class VehicleServiceStatus(
    val dueKm: Int?,
    val dueAt: Long?,
    val remainingKm: Int?,
    val remainingDays: Long?,
    val overdue: Boolean,
    val dueSoon: Boolean,
)

data class Oee(
    val availabilityPct: Double,
    /** আউটপুট/ক্ষমতা জানা না থাকলে null */
    val performancePct: Double?,
    val qualityPct: Double,
    /** পারফরম্যান্স জানা না থাকলে null */
    val oeePct: Double?,
)

data class PmRecord(val dueAt: Long?, val completedAt: Long?)

data class HourlyCost(val electricityPaisa: Long, val maintenancePaisa: Long, val depreciationPaisa: Long) {
    val totalPaisa: Long get() = electricityPaisa + maintenancePaisa + depreciationPaisa
}

data class Reliability(
    val failures: Int,
    val operatingHours: Double,
    val mtbrHours: Double?,
    val mttrHours: Double?,
    val downtimeMinutes: Long,
)

data class WorkloadInput(val assignedTo: String?, val status: WorkOrderStatus, val downtimeMinutes: Int = 0)

data class Workload(
    val assignee: String?,
    val open: Int,
    val inProgress: Int,
    val waitingParts: Int,
    val completed: Int,
) {
    val active: Int get() = open + inProgress + waitingParts
}

/** Android/Room-নিরপেক্ষ বিশুদ্ধ রক্ষণাবেক্ষণ লজিক (ইউনিট টেস্টযোগ্য)। */
object MaintenanceCalc {
    const val MINUTE_MS = 60_000L
    const val DAY_MS = 86_400_000L

    /** এর বেশি খরচে Manager অনুমোদন লাগে: ৳5,000 (পয়সায)। */
    const val DEFAULT_APPROVAL_LIMIT_PAISA = 500_000L

    /** বিদ্যুতের ডিফল্ট দর: ৳10 প্রতি kWh (পয়সায)। */
    const val DEFAULT_TARIFF_PAISA_PER_KWH = 1_000L

    const val DEFAULT_LEAD_DAYS = 3
    const val DEFAULT_VEHICLE_LEAD_KM = 500
    const val DEFAULT_VEHICLE_LEAD_DAYS = 7

    // ------------------------------------------------------------ ডাউনটাইম / MTBR / MTTR

    /** দুই সময়ের মধ্যে পূর্ণ মিনিট; শেষ <= শুরু হলে 0। */
    fun downtimeMinutes(fromAt: Long, toAt: Long): Int {
        if (toAt <= fromAt) return 0
        return ((toAt - fromAt) / MINUTE_MS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** সম্পন্ন ব্রেকডাউনে সংরক্ষিত মান; চলমান ব্রেকডাউনে রিপোর্ট থেকে এখন পর্যন্ত। */
    fun breakdownDowntimeMinutes(reportedAt: Long, completedAt: Long?, now: Long): Int =
        downtimeMinutes(reportedAt, completedAt ?: now)

    /** MTBR (ঘণ্টা) = চালু ঘণ্টা / ব্রেকডাউন সংখ্যা। ব্রেকডাউন বা চালু ঘণ্টা না থাকলে null। */
    fun mtbrHours(operatingHours: Double, failureCount: Int): Double? =
        if (failureCount <= 0 || operatingHours <= 0.0) null else operatingHours / failureCount

    /** MTTR (ঘণ্টা) = ব্রেকডাউনগুলোর গড় মেরামত (রিপোর্ট থেকে চালু) সময়। */
    fun mttrHours(repairMinutes: List<Int>): Double? =
        if (repairMinutes.isEmpty()) null
        else repairMinutes.sumOf { it.toLong() } / 60.0 / repairMinutes.size

    fun reliability(operatingHours: Double, breakdownMinutes: List<Int>, completedRepairMinutes: List<Int>): Reliability =
        Reliability(
            failures = breakdownMinutes.size,
            operatingHours = operatingHours,
            mtbrHours = mtbrHours(operatingHours, breakdownMinutes.size),
            mttrHours = mttrHours(completedRepairMinutes),
            downtimeMinutes = breakdownMinutes.sumOf { it.toLong() },
        )

    // ------------------------------------------------------------ OEE

    /** প্রাপ্যতা % = চালু / (চালু + ডাউনটাইম)। দুটোই 0 হলে null। */
    fun availabilityPct(runMinutes: Long, downtimeMinutes: Long): Double? {
        val planned = runMinutes + downtimeMinutes
        return if (planned <= 0L || runMinutes < 0L) null else runMinutes * 100.0 / planned
    }

    /**
     * OEE = প্রাপ্যতা × পারফরম্যান্স × গুণমান।
     * পারফরম্যান্স = প্রকৃত আউটপুট / (ক্ষমতা কেজি/ঘণ্টা × চালু ঘণ্টা) (সর্বোচ্চ 100%)।
     * গুণমান: [goodOutputGrams] না দিলে 100% ধরা হয়। আউটপুট/ক্ষমতা জানা না থাকলে পারফরম্যান্স ও OEE null।
     */
    fun oee(
        runMinutes: Long,
        downtimeMinutes: Long,
        outputGrams: Double,
        capacityKgPerHour: Double,
        goodOutputGrams: Double? = null,
    ): Oee? {
        val availability = availabilityPct(runMinutes, downtimeMinutes) ?: return null
        val runHours = runMinutes / 60.0
        val performance: Double? =
            if (capacityKgPerHour <= 0.0 || runHours <= 0.0 || outputGrams <= 0.0) null
            else min(100.0, (outputGrams / 1000.0) / (capacityKgPerHour * runHours) * 100.0)
        val quality: Double =
            if (goodOutputGrams == null || outputGrams <= 0.0) 100.0
            else (goodOutputGrams / outputGrams * 100.0).coerceIn(0.0, 100.0)
        val oee = performance?.let { availability * it * quality / 10_000.0 }
        return Oee(availability, performance, quality, oee)
    }

    // ------------------------------------------------------------ প্রিভেন্টিভ কমপ্লায়েন্স

    /**
     * কমপ্লায়েন্স % = সময়মতো সম্পন্ন / (সম্পন্ন + সময়-পেরোনো অসম্পন্ন)।
     * [PmRecord.dueAt] না থাকলে (রান আওয়ার ভিত্তিক) সম্পন্ন হলেই সময়মতো ধরা হয়। যোগ্য রেকর্ড না থাকলে null।
     */
    fun preventiveCompliancePct(records: List<PmRecord>, now: Long): Double? {
        val eligible = records.filter { r ->
            r.completedAt != null || (r.dueAt != null && r.dueAt < now)
        }
        if (eligible.isEmpty()) return null
        val onTime = eligible.count { r ->
            r.completedAt != null && (r.dueAt == null || r.completedAt <= r.dueAt)
        }
        return onTime * 100.0 / eligible.size
    }

    // ------------------------------------------------------------ পরবর্তী সার্ভিস

    fun leadHoursFor(intervalHours: Double): Double = min(10.0, intervalHours * 0.2)

    /** মোট চালু ঘণ্টা = প্রারম্ভিক + রান লগের মিনিট + ব্যাচ রান ঘণ্টা। */
    fun totalRunHours(openingHours: Double, runMinutes: Long, batchRunHours: Double): Double =
        openingHours + runMinutes / 60.0 + batchRunHours

    fun nextService(
        basis: ScheduleBasis,
        intervalHours: Double,
        intervalDays: Int,
        baselineAt: Long,
        lastDoneAt: Long?,
        lastDoneRunHours: Double,
        currentRunHours: Double,
        now: Long,
        leadDays: Int = DEFAULT_LEAD_DAYS,
    ): NextService = when (basis) {
        ScheduleBasis.HOURS -> {
            if (intervalHours <= 0.0) {
                NextService(basis, null, null, null, null, overdue = false, dueSoon = false)
            } else {
                val due = lastDoneRunHours + intervalHours
                val remaining = due - currentRunHours
                val overdue = remaining <= 0.0
                val soon = !overdue && remaining <= leadHoursFor(intervalHours)
                NextService(basis, null, due, null, remaining, overdue, soon)
            }
        }
        ScheduleBasis.DATE -> {
            if (intervalDays <= 0) {
                NextService(basis, null, null, null, null, overdue = false, dueSoon = false)
            } else {
                val due = (lastDoneAt ?: baselineAt) + intervalDays * DAY_MS
                val overdue = now >= due
                val soon = !overdue && (due - now) <= leadDays * DAY_MS
                NextService(basis, due, null, Math.floorDiv(due - now, DAY_MS), null, overdue, soon)
            }
        }
    }

    // ------------------------------------------------------------ গাড়ির সার্ভিস রিমাইন্ডার

    fun vehicleServiceStatus(
        intervalKm: Int,
        intervalDays: Int,
        lastServiceKm: Int,
        lastServiceAt: Long?,
        baselineAt: Long,
        currentKm: Int?,
        now: Long,
        leadKm: Int = DEFAULT_VEHICLE_LEAD_KM,
        leadDays: Int = DEFAULT_VEHICLE_LEAD_DAYS,
    ): VehicleServiceStatus {
        var overdue = false
        var soon = false
        var dueKm: Int? = null
        var remainingKm: Int? = null
        if (intervalKm > 0) {
            dueKm = lastServiceKm + intervalKm
            if (currentKm != null) {
                val rem = dueKm - currentKm
                remainingKm = rem
                if (rem <= 0) overdue = true else if (rem <= leadKm) soon = true
            }
        }
        var dueAt: Long? = null
        var remainingDays: Long? = null
        if (intervalDays > 0) {
            val due = (lastServiceAt ?: baselineAt) + intervalDays * DAY_MS
            dueAt = due
            remainingDays = Math.floorDiv(due - now, DAY_MS)
            if (now >= due) overdue = true else if (due - now <= leadDays * DAY_MS) soon = true
        }
        return VehicleServiceStatus(dueKm, dueAt, remainingKm, remainingDays, overdue, soon && !overdue)
    }

    // ------------------------------------------------------------ খরচ ও অনুমোদন

    fun totalCost(partsPaisa: Long, laborPaisa: Long, otherPaisa: Long): Long = partsPaisa + laborPaisa + otherPaisa

    fun requiresApproval(costPaisa: Long, limitPaisa: Long): Boolean = costPaisa > limitPaisa

    /** খরচ বদলালে অনুমোদনের অবস্থা: সীমা পেরোলে PENDING; সীমার নিচে নামলে PENDING/REJECTED মুছে যায়। APPROVED বহাল থাকে। */
    fun nextApprovalStatus(current: ApprovalStatus, totalCostPaisa: Long, limitPaisa: Long): ApprovalStatus =
        if (requiresApproval(totalCostPaisa, limitPaisa)) {
            when (current) {
                ApprovalStatus.APPROVED -> ApprovalStatus.APPROVED
                else -> ApprovalStatus.PENDING
            }
        } else {
            when (current) {
                ApprovalStatus.PENDING, ApprovalStatus.REJECTED -> ApprovalStatus.NOT_REQUIRED
                else -> current
            }
        }

    /**
     * মেশিনের ঘণ্টা-খরচ (বিদ্যুৎ + রক্ষণাবেক্ষণ + ডেপ্রিসিয়েশন)। সব মান একই সময়সীমার;
     * [runHours] <= 0 হলে null (ভাগ করা যায় না)।
     */
    fun hourlyCost(
        runHours: Double,
        kwhConsumed: Double,
        tariffPaisaPerKwh: Long,
        maintenanceCostPaisa: Long,
        depreciationPaisa: Long,
    ): HourlyCost? {
        if (runHours <= 0.0) return null
        val electricity = (kwhConsumed.coerceAtLeast(0.0) * tariffPaisaPerKwh / runHours).roundToLong()
        val maintenance = (maintenanceCostPaisa.coerceAtLeast(0L) / runHours).roundToLong()
        val depreciation = (depreciationPaisa.coerceAtLeast(0L) / runHours).roundToLong()
        return HourlyCost(electricity, maintenance, depreciation)
    }

    // ------------------------------------------------------------ ক্যালিব্রেশন

    /** বিচ্যুতি % = (মাপা − রেফারেন্স) / রেফারেন্স × 100। */
    fun deviationPct(referenceGrams: Double, measuredGrams: Double): Double {
        require(referenceGrams > 0.0) { "রেফারেন্স ওজন ০ এর বেশি হতে হবে" }
        return (measuredGrams - referenceGrams) / referenceGrams * 100.0
    }

    fun isWithinTolerance(deviationPct: Double, tolerancePct: Double): Boolean =
        abs(deviationPct) <= tolerancePct + 1e-9

    // ------------------------------------------------------------ মিটার

    /** বিদ্যুৎ/পানি/জ্বালানি ক্রমযোজিত মিটার; গ্রিজ/তেল ব্যবহৃত পরিমাণ। */
    fun isCumulative(type: MeterType): Boolean = when (type) {
        MeterType.ELECTRICITY, MeterType.WATER, MeterType.FUEL -> true
        MeterType.GREASE, MeterType.LUBRICANT_OIL -> false
    }

    /** ভুল হলে বাংলা ত্রুটি, নইলে null। [previous] একই ধরন/মেশিনের আগের রিডিং। */
    fun validateMeterReading(type: MeterType, previous: Double?, reading: Double): String? {
        if (reading.isNaN() || reading.isInfinite() || reading < 0.0) return "রিডিং ঋণাত্মক বা অবৈধ হতে পারে না"
        if (isCumulative(type) && previous != null && reading < previous) {
            return "রিডিং আগের রিডিং (" + String.format(Locale.US, "%.2f", previous) + ") এর কম হতে পারে না"
        }
        return null
    }

    /**
     * [readingsAsc] সময়ক্রমে সাজানো। ক্রমযোজিত হলে শেষ − প্রথম (অন্তত দুটি রিডিং লাগে); নইলে সব ব্যবহৃত পরিমাণের যোগফল।
     */
    fun consumption(type: MeterType, readingsAsc: List<Double>): Double =
        if (isCumulative(type)) {
            if (readingsAsc.size < 2) 0.0 else (readingsAsc.last() - readingsAsc.first()).coerceAtLeast(0.0)
        } else {
            readingsAsc.sum()
        }

    // ------------------------------------------------------------ ওয়ার্ক অর্ডার প্রবাহ

    fun allowedNext(from: WorkOrderStatus): List<WorkOrderStatus> = when (from) {
        WorkOrderStatus.OPEN -> listOf(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.WAITING_PARTS)
        WorkOrderStatus.IN_PROGRESS -> listOf(WorkOrderStatus.WAITING_PARTS, WorkOrderStatus.DONE)
        WorkOrderStatus.WAITING_PARTS -> listOf(WorkOrderStatus.IN_PROGRESS)
        WorkOrderStatus.DONE -> listOf(WorkOrderStatus.CLOSED, WorkOrderStatus.IN_PROGRESS)
        WorkOrderStatus.CLOSED -> emptyList()
    }

    fun canTransition(from: WorkOrderStatus, to: WorkOrderStatus): Boolean = to in allowedNext(from)

    /** টেকনিশিয়ান-ভিত্তিক ওয়ার্কলোড; সক্রিয় কাজ বেশি আগে। */
    fun technicianWorkload(items: List<WorkloadInput>): List<Workload> =
        items.groupBy { it.assignedTo }
            .map { (who, list) ->
                Workload(
                    assignee = who,
                    open = list.count { it.status == WorkOrderStatus.OPEN },
                    inProgress = list.count { it.status == WorkOrderStatus.IN_PROGRESS },
                    waitingParts = list.count { it.status == WorkOrderStatus.WAITING_PARTS },
                    completed = list.count { it.status == WorkOrderStatus.DONE || it.status == WorkOrderStatus.CLOSED },
                )
            }
            .sortedByDescending { it.active }

    /** চেকলিস্ট লেখা (প্রতি লাইনে এক ধাপ) থেকে ধাপের তালিকা। */
    fun parseChecklist(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // ------------------------------------------------------------ ডেপ্রিসিয়েশন সময়কাল

    /** "yyyy-MM" */
    fun periodOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return String.format(Locale.US, "%04d-%02d", d.year, d.monthValue)
    }

    /** ক্রয়ের মাস = ১ম মাস। period ক্রয়ের আগের হলে ০ বা ঋণাত্মক। */
    fun depreciationMonthNumber(purchaseAt: Long, period: String, zone: ZoneId = ZoneId.systemDefault()): Int {
        val parts = period.split("-")
        require(parts.size == 2) { "সময়কাল yyyy-MM ফরম্যাটে হতে হবে" }
        val y = parts[0].toIntOrNull()
        val m = parts[1].toIntOrNull()
        require(y != null && m != null && m in 1..12) { "সময়কাল yyyy-MM ফরম্যাটে হতে হবে" }
        val p = Instant.ofEpochMilli(purchaseAt).atZone(zone)
        return (y - p.year) * 12 + (m - p.monthValue) + 1
    }
}
