package com.feederp.data

import com.feederp.accounts.ChartOfAccounts
import com.feederp.core.Stamper
import com.feederp.data.accounts.AccountDao
import com.feederp.data.accounts.AccountEntity
import javax.inject.Inject
import javax.inject.Singleton

/** প্রথম চালুর ডিফল্ট ডাটার বিশুদ্ধ সংজ্ঞা (Android নির্ভরতা নেই, টেস্টযোগ্য)। */
object SeedData {
    data class UnitDef(val id: String, val name: String, val symbol: String, val gramsPerUnit: Double)
    data class WarehouseDef(val id: String, val name: String, val isMedicineStore: Boolean)

    /** আইডি নির্ধারিত: একাধিক ফোনে সিড হলেও সিঙ্কে ডুপ্লিকেট হয় না। বেস ইউনিট = গ্রাম। */
    val units = listOf(
        UnitDef("unit-g", "গ্রাম", "g", 1.0),
        UnitDef("unit-kg", "কেজি", "kg", 1_000.0),
        UnitDef("unit-ton", "টন", "ton", 1_000_000.0),
        UnitDef("unit-bag25", "বস্তা (২৫ কেজি)", "bag25", 25_000.0),
        UnitDef("unit-bag50", "বস্তা (৫০ কেজি)", "bag50", 50_000.0),
    )

    val warehouses = listOf(
        WarehouseDef("wh-main", "কাঁচামাল গুদাম", false),
        WarehouseDef("wh-fg", "ফিনিশড গুডস গুদাম", false),
        WarehouseDef("wh-medicine", "ওষুধ ও প্রিমিক্স গুদাম", true),
    )

    fun accountId(code: String) = "acc-$code"
}

@Singleton
class Seeder @Inject constructor(
    private val units: UnitDao,
    private val warehouses: WarehouseDao,
    private val accounts: AccountDao,
    private val stamper: Stamper,
) {
    /** বারবার ডাকলেও নিরাপদ (idempotent)। */
    suspend fun seedDefaults() {
        val now = stamper.now()
        val device = stamper.deviceId

        SeedData.units.forEach { u ->
            units.insertIgnore(
                UnitEntity(
                    id = u.id, name = u.name, symbol = u.symbol, toBaseFactor = u.gramsPerUnit,
                    updatedAt = now, deviceId = device,
                )
            )
        }
        if (warehouses.count() == 0) {
            SeedData.warehouses.forEach { w ->
                warehouses.upsert(
                    WarehouseEntity(
                        id = w.id, name = w.name, isMedicineStore = w.isMedicineStore,
                        updatedAt = now, deviceId = device,
                    )
                )
            }
        }
        accounts.insertIgnore(
            ChartOfAccounts.defaults.map { a ->
                AccountEntity(
                    id = SeedData.accountId(a.code), code = a.code, nameBn = a.nameBn, nameEn = a.nameEn,
                    type = a.type, updatedAt = now, deviceId = device,
                )
            }
        )
    }
}
