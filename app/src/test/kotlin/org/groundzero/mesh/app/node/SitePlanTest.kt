package org.groundzero.mesh.app.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePlanTest {

    private val text = """
        # a comment
        name    Test site
        size    1000 800
        georef  0    0    9.09350  76.49000
        georef  1000 800  9.09200  76.49150
        zone    block-a-north  40  60  380  220
        zone    courtyard      420 180 120  440
    """.trimIndent()

    private val plan = SitePlan.parse(text)

    @Test
    fun `parses name size and zones`() {
        assertEquals("Test site", plan.name)
        assertEquals(1000f, plan.width, 0f)
        assertEquals(2, plan.zones.size)
        assertEquals("block-a-north", plan.zones[0].name)
    }

    @Test
    fun `the reference corners map to their own coordinates`() {
        val (lat0, lon0) = plan.georeference.toLatLon(0f, 0f)
        assertEquals(9.09350, lat0, 1e-6)
        assertEquals(76.49000, lon0, 1e-6)
        val (lat1, lon1) = plan.georeference.toLatLon(1000f, 800f)
        assertEquals(9.09200, lat1, 1e-6)
        assertEquals(76.49150, lon1, 1e-6)
    }

    @Test
    fun `the centre of the plan is the midpoint of the corners`() {
        val (lat, lon) = plan.georeference.toLatLon(500f, 400f)
        assertEquals((9.09350 + 9.09200) / 2, lat, 1e-6)
        assertEquals((76.49000 + 76.49150) / 2, lon, 1e-6)
    }

    /** Latitude decreases going down the page — an axis flip here would put north south. */
    @Test
    fun `going down the plan goes south`() {
        val (north, _) = plan.georeference.toLatLon(500f, 0f)
        val (south, _) = plan.georeference.toLatLon(500f, 800f)
        assertTrue("expected $north to be north of $south", north > south)
    }

    @Test
    fun `a tap inside a zone names it, and open ground names nothing`() {
        assertEquals("block-a-north", plan.zoneAt(100f, 100f)?.name)
        assertNull(plan.zoneAt(980f, 780f))
    }

    @Test
    fun `an unknown keyword fails loudly rather than being skipped`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SitePlan.parse("name x\nsize 10 10\ngeoref 0 0 1 1\ngeoref 10 10 2 2\nwibble nonsense\n")
        }
        assertTrue(e.message!!.contains("unknown keyword"))
    }

    @Test
    fun `a plan without two reference corners is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SitePlan.parse("name x\nsize 10 10\ngeoref 0 0 1 1\n")
        }
    }

    /** A zone name becomes Envelope.addressZone, which is capped on the wire. */
    @Test
    fun `an over-long zone name is rejected when the plan loads`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SitePlan.parse(
                "name x\nsize 10 10\ngeoref 0 0 1 1\ngeoref 10 10 2 2\n" +
                    "zone ${"z".repeat(40)} 0 0 5 5\n",
            )
        }
        assertTrue(e.message!!.contains("longer than"))
    }

    @Test
    fun `the bundled plan parses`() {
        val bundled = java.io.File("src/main/assets/siteplan/plan.txt")
        assertTrue("bundled plan is missing at ${bundled.absolutePath}", bundled.isFile)
        val loaded = SitePlan.parse(bundled.readText())
        assertTrue("bundled plan has no zones", loaded.zones.isNotEmpty())
    }
}
