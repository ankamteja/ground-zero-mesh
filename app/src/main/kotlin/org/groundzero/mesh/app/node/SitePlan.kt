package org.groundzero.mesh.app.node

/**
 * The map a trapped person marks themselves on.
 *
 * ### Why a site plan and not a real map
 *
 * A slippy world map needs tiles, and tiles need either a network or hundreds of megabytes of
 * pre-cached archive. This mesh exists precisely for the hours when there is no network, and a
 * victim's phone is the one device in the system nobody can recharge or re-provision. Neither
 * is a good place to put a map library.
 *
 * A disaster deployment does not need the world anyway. It needs *this site*: the collapsed
 * block, its wings, its stairwells. That is a handful of labelled rectangles, which is small
 * enough to ship as text, draw with no dependency at all, and hand out to every phone before
 * anyone walks in.
 *
 * ### One tap answers two questions
 *
 * Tapping a zone yields both a coordinate — via [georeference] — and the zone's name. The
 * coordinate goes out as a [org.groundzero.mesh.propagation.FixSource.SELF_REPORTED] position;
 * the name goes out as `Envelope.addressZone`, which until now no real phone ever set. A
 * responder therefore gets "block-a-north" *and* a point to walk to, from one press by someone
 * who may have only seconds to give it.
 *
 * ### The projection, and why a linear one is honest here
 *
 * [georeference] is two corners: a pixel and a latitude/longitude for each. Everything between
 * them is linear interpolation. That ignores the Earth's curvature and the convergence of
 * meridians, which over a site of a few hundred metres costs centimetres — far below the error
 * in a frightened person pointing at a rectangle. Over tens of kilometres it would not be
 * honest, and the plan is not for that.
 */
data class SitePlan(
    val name: String,
    val georeference: Georeference,
    val zones: List<Zone>,
    /** Plan-space size, in the same units the zones use. */
    val width: Float,
    val height: Float,
) {

    /** A named rectangle on the plan: a wing, a floor plate, a courtyard. */
    data class Zone(
        val name: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    ) {
        fun contains(px: Float, py: Float): Boolean =
            px >= x && px <= x + width && py >= y && py <= y + height

        val centreX: Float get() = x + width / 2f
        val centreY: Float get() = y + height / 2f
    }

    /**
     * Two known points, opposite corners, tying plan pixels to the world. Both are required:
     * one point would fix an offset but leave scale and orientation unknown.
     */
    data class Georeference(
        val x0: Float,
        val y0: Float,
        val lat0: Double,
        val lon0: Double,
        val x1: Float,
        val y1: Float,
        val lat1: Double,
        val lon1: Double,
    ) {
        /**
         * Plan point to world point.
         *
         * Note latitude runs *up* while plan y runs *down*, which the reference points encode
         * on their own — `lat1` being south of `lat0` makes the interpolation come out with
         * the right sign, so there is no axis flip hidden in here to get wrong.
         */
        fun toLatLon(px: Float, py: Float): Pair<Double, Double> {
            val fx = if (x1 == x0) 0.0 else (px - x0).toDouble() / (x1 - x0)
            val fy = if (y1 == y0) 0.0 else (py - y0).toDouble() / (y1 - y0)
            return (lat0 + (lat1 - lat0) * fy) to (lon0 + (lon1 - lon0) * fx)
        }
    }

    /** The zone under a plan-space point, or null for a tap on open ground. */
    fun zoneAt(px: Float, py: Float): Zone? = zones.firstOrNull { it.contains(px, py) }

    companion object {

        /**
         * Parses the bundled plan.
         *
         * A hand-written line format rather than JSON because Android's `org.json` is a stub
         * under JVM unit tests — it would make every rule in here untestable — and pulling a
         * parser in for four kinds of line is not a trade worth making on a victim's phone.
         * Unknown lines are a hard error, not a shrug: a plan that silently drops the wing
         * someone is trapped in is worse than one that refuses to load.
         *
         * ```
         * # comments and blank lines are ignored
         * name    Amrita block A
         * size    1000 800
         * georef  0 0      9.09350 76.49000
         * georef  1000 800 9.09200 76.49150
         * zone    block-a-north  40 60 380 220
         * ```
         */
        fun parse(text: String): SitePlan {
            var name = "site"
            var width = 0f
            var height = 0f
            val refs = ArrayList<FloatArray>()
            val latLons = ArrayList<DoubleArray>()
            val zones = ArrayList<Zone>()

            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                fun bad(why: String): Nothing =
                    throw IllegalArgumentException("site plan line ${index + 1}: $why -- '$raw'")
                when (parts[0]) {
                    "name" -> {
                        if (parts.size < 2) bad("name needs a value")
                        name = parts.drop(1).joinToString(" ")
                    }
                    "size" -> {
                        if (parts.size != 3) bad("size needs width and height")
                        width = parts[1].toFloatOrNull() ?: bad("width is not a number")
                        height = parts[2].toFloatOrNull() ?: bad("height is not a number")
                    }
                    "georef" -> {
                        if (parts.size != 5) bad("georef needs x y lat lon")
                        refs.add(
                            floatArrayOf(
                                parts[1].toFloatOrNull() ?: bad("x is not a number"),
                                parts[2].toFloatOrNull() ?: bad("y is not a number"),
                            ),
                        )
                        latLons.add(
                            doubleArrayOf(
                                parts[3].toDoubleOrNull() ?: bad("lat is not a number"),
                                parts[4].toDoubleOrNull() ?: bad("lon is not a number"),
                            ),
                        )
                    }
                    "zone" -> {
                        if (parts.size != 6) bad("zone needs name x y width height")
                        zones.add(
                            Zone(
                                name = parts[1],
                                x = parts[2].toFloatOrNull() ?: bad("x is not a number"),
                                y = parts[3].toFloatOrNull() ?: bad("y is not a number"),
                                width = parts[4].toFloatOrNull() ?: bad("width is not a number"),
                                height = parts[5].toFloatOrNull() ?: bad("height is not a number"),
                            ),
                        )
                    }
                    else -> bad("unknown keyword '${parts[0]}'")
                }
            }

            require(refs.size == 2) { "site plan needs exactly 2 georef lines, got ${refs.size}" }
            require(width > 0f && height > 0f) { "site plan needs a positive size, got ${width}x$height" }
            // A zone name rides in Envelope.addressZone, which is capped on the wire. Catching
            // it here means a bad plan fails when it is loaded rather than when someone in a
            // stairwell presses the button.
            zones.forEach {
                require(it.name.length <= org.groundzero.mesh.propagation.Envelope.MAX_ADDRESS_ZONE_CHARS) {
                    "zone name '${it.name}' is longer than " +
                        "${org.groundzero.mesh.propagation.Envelope.MAX_ADDRESS_ZONE_CHARS} chars"
                }
            }

            return SitePlan(
                name = name,
                georeference = Georeference(
                    x0 = refs[0][0], y0 = refs[0][1], lat0 = latLons[0][0], lon0 = latLons[0][1],
                    x1 = refs[1][0], y1 = refs[1][1], lat1 = latLons[1][0], lon1 = latLons[1][1],
                ),
                zones = zones,
                width = width,
                height = height,
            )
        }

        /** Where a deployment drops its own plan; see `tools/field/README.md`. */
        const val ASSET_PATH = "siteplan/plan.txt"
    }
}
