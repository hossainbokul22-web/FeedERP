package com.feederp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// সব এন্টিটিতে UUID আইডি, সিঙ্কের জন্য updatedAt (HLC) ও deviceId, এবং সফট ডিলিট।

enum class Role { OWNER, MANAGER, SALES, STORE, MAINTENANCE, DISTRIBUTION, HR, RND }

enum class ItemType { RAW_MATERIAL, PREMIX, MEDICINE, ADDITIVE, PACKAGING, FINISHED_GOOD }

enum class PartyType { SUPPLIER, CUSTOMER, DEALER }

@Entity(tableName = "users", indices = [Index("username", unique = true)])
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
    val role: Role,
    val pinHash: String,
    val pinSalt: String,
    val active: Boolean = true,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "units")
data class UnitEntity(
    @PrimaryKey val id: String,
    val name: String,          // কেজি, টন, বস্তা, গ্রাম
    val symbol: String,
    val toBaseFactor: Double,  // বেস ইউনিট (গ্রাম) এ রূপান্তর গুণক
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "items", indices = [Index("code", unique = true), Index("type")])
data class ItemEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val nameBn: String? = null,
    val type: ItemType,
    val unitId: String,
    val minStockGrams: Double = 0.0,
    val trackLot: Boolean = true,
    val trackExpiry: Boolean = true,
    val requiresQc: Boolean = true,   // গুডস রিসিভে QC লাগলে লট প্রথমে PENDING থাকে
    // ওষুধ / প্রিমিক্স / অ্যাডিটিভ সংক্রান্ত
    val activeIngredient: String? = null,
    val concentrationPct: Double? = null,
    val minDoseGPerTon: Double? = null,
    val maxDoseGPerTon: Double? = null,
    val withdrawalDays: Int? = null,
    val isControlled: Boolean = false,
    val storageNote: String? = null,
    // নিউট্রিয়েন্ট (প্রতি ১০০ গ্রামে)
    val proteinPct: Double? = null,
    val energyKcalPerKg: Double? = null,
    val fatPct: Double? = null,
    val fibrePct: Double? = null,
    val moisturePct: Double? = null,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "parties", indices = [Index("type")])
data class PartyEntity(
    @PrimaryKey val id: String,
    val type: PartyType,
    val name: String,
    val phone: String? = null,
    val address: String? = null,
    val creditLimitPaisa: Long = 0L,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

@Entity(tableName = "warehouses")
data class WarehouseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isMedicineStore: Boolean = false,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
)

// স্টক কখনও সরাসরি বদলানো হয় না; শুধু মুভমেন্ট যোগ হয় (সিঙ্ক-নিরাপদ)।
enum class MovementType { PURCHASE_IN, PRODUCTION_OUT, PRODUCTION_IN, SALE_OUT, RETURN_IN, TRANSFER, ADJUSTMENT }

@Entity(tableName = "stock_movements", indices = [Index("itemId"), Index("lotId"), Index("warehouseId")])
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val lotId: String?,
    val warehouseId: String,
    val type: MovementType,
    val qtyGrams: Double,      // ইন = ধনাত্মক, আউট = ঋণাত্মক
    val refType: String?,
    val refId: String?,
    val createdBy: String,
    val createdAt: Long,
    val deviceId: String,
    val unitCostPaisaPerKg: Long? = null,   // ইনকামিং মুভমেন্টে কেজি প্রতি খরচ (ওয়েটেড-অ্যাভারেজের জন্য)
    val note: String? = null,
)

@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val action: String,
    val entity: String,
    val entityId: String?,
    val detail: String?,
    val createdAt: Long,
    val deviceId: String,
)
