package com.pixelnas.household

import com.pixelnas.household.model.SmbAuthMode
import com.pixelnas.household.model.SmbConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class HouseholdModelsTest {

    @Test
    fun `destinationRelativeDir resolves default profile custom folder and member routing`() {
        val saverCfg = SmbConnectionConfig(
            host = "192.168.1.50",
            port = 4445,
            shareName = "PixelNAS",
            baseSubfolder = "Incoming",
            targetProfileFolder = "StorageSaver"
        )
        assertEquals("Incoming\\StorageSaver", saverCfg.destinationRelativeDir())
        assertEquals("\\\\192.168.1.50\\PixelNAS\\Incoming\\StorageSaver", saverCfg.displayUnc)
        assertEquals("smb://192.168.1.50:4445/PixelNAS/Incoming/StorageSaver/", saverCfg.displaySmbUrl)

        val memberCfg = saverCfg.copy(
            port = 445,
            memberName = "Mom",
            routeToMemberSubfolder = true
        )
        assertEquals("Incoming\\StorageSaver\\Mom", memberCfg.destinationRelativeDir())
        assertEquals("smb://192.168.1.50/PixelNAS/Incoming/StorageSaver/Mom/", memberCfg.displaySmbUrl)

        val directShareCfg = SmbConnectionConfig(
            host = "192.168.1.10",
            port = 445,
            shareName = "Incoming",
            baseSubfolder = "Incoming",
            targetProfileFolder = "Family",
            authMode = SmbAuthMode.CREDENTIALS,
            username = "household",
            password = "secret"
        )
        assertEquals("Family", directShareCfg.destinationRelativeDir())
    }
}
