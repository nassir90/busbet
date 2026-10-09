package iompar.mpts.ie.screenshots

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import iompar.mpts.ie.BASE_TILES
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiles for screenshot tests, answered synchronously so a map is complete on the frame that
 * gets captured.
 *
 * Reads `src/test/screenshot-tiles/<z>/<x>/<y>.png`, which is committed: a run on any machine, or
 * with no network, renders the same map. A tile not yet in the cache is fetched once from
 * OpenStreetMap and written there. That keeps within the OSM tile policy the app itself follows —
 * a distinct User-Agent, only the tiles a rendered view actually shows, each fetched once and kept
 * (https://operations.osmfoundation.org/policies/tiles/). Never point this at a bulk area.
 */
class CachedTileProvider(private val context: Context) : MapTileProviderBase(BASE_TILES) {

    override fun getMapTile(pMapTileIndex: Long): Drawable? {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        val file = File(CACHE, "$z/$x/$y.png")
        if (!file.exists() && !fetch(z, x, y, file)) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    override fun getMinimumZoomLevel(): Int = BASE_TILES.minimumZoomLevel
    override fun getMaximumZoomLevel(): Int = BASE_TILES.maximumZoomLevel
    override fun getTileWriter(): IFilesystemCache? = null
    override fun getQueueSize(): Long = 0

    companion object {
        /** Relative to the module directory, which is the test JVM's working directory. */
        val CACHE = File("src/test/screenshot-tiles")
        private const val USER_AGENT = "iompar.mpts.ie-screenshot-tests/1 (+https://iompar.mpts.ie)"

        /** Fetched on its own thread and joined: the caller is the main looper, mid-draw. */
        private fun fetch(z: Int, x: Int, y: Int, into: File): Boolean {
            var ok = false
            val t = Thread {
                runCatching {
                    val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    if (conn.responseCode == 200) {
                        into.parentFile?.mkdirs()
                        conn.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
                        ok = true
                    }
                }
            }
            t.start()
            t.join()
            return ok
        }
    }
}
