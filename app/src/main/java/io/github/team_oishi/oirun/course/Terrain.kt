package io.github.team_oishi.oirun.course

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** Ground elevation in meters above sea level, or null where no terrain data is loaded. */
fun interface ElevationLookup {
    fun elevationAt(latitude: Double, longitude: Double): Double?
}

data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Terrarium-encoded elevation tiles (Mapzen Terrain Tiles on AWS Open Data): 256 px web-mercator
 * tiles whose pixels hold `R * 256 + G + B / 256 - 32768` meters. In Korea the source is SRTM
 * at ~30 m, so zoom 14 (~7 m per pixel at 37°N) already oversamples it.
 */
object Terrarium {
    const val ZOOM = 14
    const val SIZE = 256

    fun url(key: TileKey) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${key.z}/${key.x}/${key.y}.png"

    fun decode(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r * 256f + g + b / 256f - 32768f
    }

    /** Position in tile units at [zoom]; the integer part is the tile, the fraction the spot in it. */
    fun position(latitude: Double, longitude: Double, zoom: Int = ZOOM): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val x = (longitude + 180) / 360 * n
        val y = (1 - asinh(tan(Math.toRadians(latitude))) / PI) / 2 * n
        return x to y
    }

    fun keyOf(latitude: Double, longitude: Double, zoom: Int = ZOOM): TileKey {
        val (x, y) = position(latitude, longitude, zoom)
        return TileKey(zoom, floor(x).toInt(), floor(y).toInt())
    }
}

/** One decoded tile, row-major meters. */
class TerrainTile(val key: TileKey, private val meters: FloatArray) {
    init {
        require(meters.size == Terrarium.SIZE * Terrarium.SIZE)
    }

    /** Bilinear between pixel centers; the outermost half pixel holds the edge value. */
    fun elevationAt(latitude: Double, longitude: Double): Double {
        val (tx, ty) = Terrarium.position(latitude, longitude, key.z)
        val last = Terrarium.SIZE - 1
        val px = ((tx - key.x) * Terrarium.SIZE - 0.5).coerceIn(0.0, last.toDouble())
        val py = ((ty - key.y) * Terrarium.SIZE - 0.5).coerceIn(0.0, last.toDouble())
        val x0 = px.toInt().coerceAtMost(last - 1)
        val y0 = py.toInt().coerceAtMost(last - 1)
        val fx = px - x0
        val fy = py - y0
        fun at(x: Int, y: Int) = meters[y * Terrarium.SIZE + x].toDouble()
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bottom * fy
    }
}

/** Looks up whichever loaded tile covers a point. */
class TileSet(tiles: Collection<TerrainTile>) : ElevationLookup {
    private val byKey = tiles.associateBy { it.key }

    override fun elevationAt(latitude: Double, longitude: Double): Double? =
        byKey[Terrarium.keyOf(latitude, longitude)]?.elevationAt(latitude, longitude)
}
