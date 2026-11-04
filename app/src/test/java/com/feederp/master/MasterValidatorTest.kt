package com.feederp.master

import com.feederp.data.ItemEntity
import com.feederp.data.ItemType
import com.feederp.data.PartyType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MasterValidatorTest {
    private fun item(
        code: String = "RM-001", name: String = "Maize", type: ItemType = ItemType.RAW_MATERIAL,
        unitId: String = "u1", activeIngredient: String? = null, minDose: Double? = null, maxDose: Double? = null,
        protein: Double? = null, concentration: Double? = null, trackLot: Boolean = true, trackExpiry: Boolean = true,
        minStock: Double = 0.0, withdrawalDays: Int? = null,
    ) = ItemEntity(
        id = "i1", code = code, name = name, type = type, unitId = unitId, minStockGrams = minStock,
        trackLot = trackLot, trackExpiry = trackExpiry, activeIngredient = activeIngredient,
        minDoseGPerTon = minDose, maxDoseGPerTon = maxDose, proteinPct = protein, concentrationPct = concentration,
        withdrawalDays = withdrawalDays, updatedAt = 0L, deviceId = "d",
    )

    @Test fun validItem_ok() = assertNull(MasterValidator.validateItem(item()))
    @Test fun blankCode_rejected() = assertNotNull(MasterValidator.validateItem(item(code = " ")))
    @Test fun blankName_rejected() = assertNotNull(MasterValidator.validateItem(item(name = "")))
    @Test fun blankUnit_rejected() = assertNotNull(MasterValidator.validateItem(item(unitId = "")))
    @Test fun negativeMinStock_rejected() = assertNotNull(MasterValidator.validateItem(item(minStock = -1.0)))
    @Test fun expiryWithoutLot_rejected() = assertNotNull(MasterValidator.validateItem(item(trackLot = false, trackExpiry = true)))
    @Test fun medicineNeedsActiveIngredient() {
        assertNotNull(MasterValidator.validateItem(item(type = ItemType.MEDICINE)))
        assertNull(MasterValidator.validateItem(item(type = ItemType.MEDICINE, activeIngredient = "Amoxicillin")))
    }
    @Test fun doseRange_minAboveMax_rejected() = assertNotNull(MasterValidator.validateItem(item(minDose = 10.0, maxDose = 5.0)))
    @Test fun doseRange_valid() = assertNull(MasterValidator.validateItem(item(minDose = 5.0, maxDose = 10.0)))
    @Test fun negativeDose_rejected() = assertNotNull(MasterValidator.validateItem(item(minDose = -1.0)))
    @Test fun nutrientPctOver100_rejected() = assertNotNull(MasterValidator.validateItem(item(protein = 120.0)))
    @Test fun concentrationOver100_rejected() = assertNotNull(MasterValidator.validateItem(item(concentration = 101.0)))
    @Test fun negativeWithdrawal_rejected() = assertNotNull(MasterValidator.validateItem(item(withdrawalDays = -2)))

    @Test fun unit_validation() {
        assertNull(MasterValidator.validateUnit("কেজি", "kg", 1000.0))
        assertNotNull(MasterValidator.validateUnit("", "kg", 1000.0))
        assertNotNull(MasterValidator.validateUnit("কেজি", "", 1000.0))
        assertNotNull(MasterValidator.validateUnit("কেজি", "kg", 0.0))
        assertNotNull(MasterValidator.validateUnit("কেজি", "kg", Double.NaN))
    }

    @Test fun party_validation() {
        assertNull(MasterValidator.validateParty("রহিম", "01711-223344", PartyType.DEALER, 500_000))
        assertNotNull(MasterValidator.validateParty("", null, PartyType.DEALER, 0))
        assertNotNull(MasterValidator.validateParty("রহিম", "abc", PartyType.DEALER, 0))
        assertNotNull(MasterValidator.validateParty("রহিম", null, PartyType.DEALER, -1))
        assertNotNull(MasterValidator.validateParty("সাপ্লায়ার", null, PartyType.SUPPLIER, 100))
        assertNull(MasterValidator.validateParty("সাপ্লায়ার", null, PartyType.SUPPLIER, 0))
    }

    @Test fun warehouse_validation() {
        assertNull(MasterValidator.validateWarehouse("মূল গুদাম"))
        assertNotNull(MasterValidator.validateWarehouse("  "))
    }
}
