package org.groundzero.mesh.app.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorNormalisationTest {

    @Test
    fun `a phone at rest reports no shock`() {
        val still = SensorNormalisation.magnitude(0.0, 0.0, SensorNormalisation.GRAVITY)
        assertEquals(0.0, SensorNormalisation.shock(still), 1e-9)
    }

    @Test
    fun `free fall and impact both count as shock`() {
        val freeFall = SensorNormalisation.shock(0.0)
        val impact = SensorNormalisation.shock(40.0)
        assertTrue("free fall is evidence, not silence", freeFall > 0.0)
        assertTrue(impact > freeFall)
        assertEquals(1.0, impact, 1e-9)
    }

    @Test
    fun `a flat still phone reads as pinned and a tumbling one does not`() {
        val flat = SensorNormalisation.pinned(0.0, 0.0, SensorNormalisation.GRAVITY)
        // Magnitude well above gravity: the phone is being accelerated, not resting.
        val tumbling = SensorNormalisation.pinned(12.0, 9.0, 14.0)
        assertEquals(1.0, flat, 1e-6)
        assertTrue("a phone being thrown around is not pinned, got $tumbling", tumbling < 0.2)
    }

    @Test
    fun `a phone lying tilted but still is only partly pinned`() {
        // Magnitude is gravity (at rest) but only a third of it is on the flat axis.
        val tilted = SensorNormalisation.pinned(6.0, 7.0, 3.0)
        assertTrue("a tilted resting phone is partial evidence, got $tilted", tilted in 0.2..0.4)
    }

    @Test
    fun `an upright phone in a pocket is not pinned`() {
        // All of gravity on the y axis: the phone is standing, not lying flat.
        assertTrue(SensorNormalisation.pinned(0.0, SensorNormalisation.GRAVITY, 0.0) < 0.1)
    }

    @Test
    fun `darkness is separated from daylight on a log scale`() {
        assertEquals(0.0, SensorNormalisation.ambientLight(0.0), 1e-9)
        val void = SensorNormalisation.ambientLight(0.5)
        val dimRoom = SensorNormalisation.ambientLight(10.0)
        val outdoors = SensorNormalisation.ambientLight(10_000.0)

        assertTrue("a sealed void must read near dark, got $void", void < 0.15)
        assertTrue(dimRoom > void)
        assertEquals(1.0, outdoors, 1e-6)
        // The informative part of the range is the dark end, not the bright end.
        assertTrue(dimRoom - void > SensorNormalisation.ambientLight(9_000.0) - SensorNormalisation.ambientLight(5_000.0))
    }

    @Test
    fun `every output stays inside the range core requires`() {
        val samples = listOf(-100.0, 0.0, 0.7, 9.81, 50.0, 1e6)
        for (m in samples) assertTrue(SensorNormalisation.shock(m) in 0.0..1.0)
        for (lux in samples) assertTrue(SensorNormalisation.ambientLight(lux) in 0.0..1.0)
        for (a in samples) assertTrue(SensorNormalisation.pinned(a, a, a) in 0.0..1.0)
    }
}
