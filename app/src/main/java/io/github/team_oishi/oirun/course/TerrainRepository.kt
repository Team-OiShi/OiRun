package io.github.team_oishi.oirun.course

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches the terrain tiles a track crosses. Tiles are cached on disk (they never change) and
 * kept decoded in memory; a course of a few km touches only a handful at zoom 14.
 */
class TerrainRepository(context: Context) {

    private val dir = File(context.cacheDir, "terrain").apply { mkdirs() }
    private val loaded = ConcurrentHashMap<TileKey, TerrainTile>()
    private val failedAt = ConcurrentHashMap<TileKey, Long>()

    /**
     * Blocking; call off the main thread. Returns a lookup over every tile [points] touch, or
     * null when one of them can't be had (offline and not cached), since a course measured
     * half by terrain and half by GPS would step where the two meet.
     */
    fun lookupFor(points: List<TrackPoint>): ElevationLookup? {
        val keys = points.mapTo(LinkedHashSet()) { Terrarium.keyOf(it.latitude, it.longitude) }
        for (key in keys) {
            if (loaded.containsKey(key)) continue
            // Fixes arrive every second; don't hammer a tile that just failed.
            failedAt[key]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null }
            val tile = runCatching { load(key) }.getOrNull()
            if (tile == null) {
                failedAt[key] = System.currentTimeMillis()
                return null
            }
            failedAt.remove(key)
            loaded[key] = tile
        }
        return TileSet(keys.map { loaded.getValue(it) })
    }

    private fun load(key: TileKey): TerrainTile {
        val file = File(dir, "${key.z}_${key.x}_${key.y}.png")
        if (!file.exists()) download(key, file)
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPremultiplied = false })
            ?: run {
                file.delete()
                throw IOException("Undecodable terrain tile $key")
            }
        val pixels = IntArray(Terrarium.SIZE * Terrarium.SIZE)
        bitmap.getPixels(pixels, 0, Terrarium.SIZE, 0, 0, Terrarium.SIZE, Terrarium.SIZE)
        bitmap.recycle()
        return TerrainTile(key, FloatArray(pixels.size) { Terrarium.decode(pixels[it]) })
    }

    private fun download(key: TileKey, file: File) {
        val connection = URL(Terrarium.url(key)).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Terrain tile $key: HTTP ${connection.responseCode}")
            }
            val partial = File(dir, file.name + ".part")
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            partial.renameTo(file)
        } finally {
            connection.disconnect()
        }
    }
}

private const val RETRY_MS = 30_000L
