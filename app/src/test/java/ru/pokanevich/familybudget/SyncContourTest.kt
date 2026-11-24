package ru.pokanevich.familybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.pokanevich.familybudget.data.sync.SyncContour

class SyncContourTest {

    @Test
    fun `contours use strictly distinct root collections`() {
        assertNotEquals(SyncContour.FAMILY.rootCollection, SyncContour.GRANDMA_SOLO.rootCollection)
        assertEquals("zfold_family_spaces", SyncContour.FAMILY.rootCollection)
        assertEquals("zfold_grandma_spaces", SyncContour.GRANDMA_SOLO.rootCollection)
    }

    @Test
    fun `grandma solo contour locks spaceKey and rejects POKANE-FAMILY`() {
        assertEquals(
            "GRANDMA-SOLO",
            SyncContour.validateAndNormalizeSpaceKey("POKANE-FAMILY", SyncContour.GRANDMA_SOLO, isSpaceCodeLocked = true)
        )
        assertEquals(
            "GRANDMA-SOLO",
            SyncContour.validateAndNormalizeSpaceKey("POKANE-FAMILY", SyncContour.GRANDMA_SOLO, isSpaceCodeLocked = false)
        )
        assertEquals(
            "GRANDMA-SOLO",
            SyncContour.validateAndNormalizeSpaceKey("ab", SyncContour.GRANDMA_SOLO, isSpaceCodeLocked = false)
        )
    }

    @Test
    fun `family contour normalizes valid space keys`() {
        assertEquals(
            "POKANE-FAMILY",
            SyncContour.validateAndNormalizeSpaceKey(" pokane-family ", SyncContour.FAMILY, isSpaceCodeLocked = false)
        )
        assertEquals(
            "TEST_SPACE_1",
            SyncContour.validateAndNormalizeSpaceKey("test space 1", SyncContour.FAMILY, isSpaceCodeLocked = false)
        )
    }
}
