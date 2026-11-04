package com.feederp.data

import com.feederp.accounts.ChartOfAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedDataTest {
    @Test fun unitIdsAreUniqueAndFactorsPositive() {
        val ids = SeedData.units.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SeedData.units.all { it.gramsPerUnit > 0.0 })
    }

    @Test fun gramIsBaseUnitAndKgTonConsistent() {
        val byId = SeedData.units.associateBy { it.id }
        assertEquals(1.0, byId.getValue("unit-g").gramsPerUnit, 0.0)
        assertEquals(1_000.0, byId.getValue("unit-kg").gramsPerUnit, 0.0)
        assertEquals(1_000.0, byId.getValue("unit-ton").gramsPerUnit / byId.getValue("unit-kg").gramsPerUnit, 0.0)
        assertEquals(25.0, byId.getValue("unit-bag25").gramsPerUnit / byId.getValue("unit-kg").gramsPerUnit, 0.0)
    }

    @Test fun exactlyOneMedicineWarehouse() {
        assertEquals(1, SeedData.warehouses.count { it.isMedicineStore })
        val ids = SeedData.warehouses.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun accountCodesUniqueSoDeterministicIdsNeverCollide() {
        val ids = ChartOfAccounts.defaults.map { SeedData.accountId(it.code) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
