package pl.simpay.android.checkout.mobile.internal.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal logo loader for channel icons.
 *
 * The channels endpoint currently serves SVG assets, which Android cannot decode
 * natively. Anything that fails to decode simply leaves the placeholder in place, so a
 * missing logo never breaks the list.
 */
internal object ChannelLogoLoader {

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun load(scope: CoroutineScope, url: String?, target: ImageView) {
        if (url.isNullOrBlank()) {
            return
        }

        cache[url]?.let { cached ->
            target.setImageBitmap(cached)
            return
        }

        // The tile is small, so the full sized asset is never kept in memory.
        val targetPx = target.layoutParams?.width?.takeIf { it > 0 }
            ?: target.context.resources.displayMetrics.density.times(44).toInt()

        target.tag = url
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url, targetPx) } ?: return@launch
            cache[url] = bitmap
            if (target.tag == url) {
                target.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? {
        val bytes = runCatching { download(url) }.getOrNull() ?: return null

        // Two passes: measure first, then decode subsampled. Bank logos are several
        // hundred pixels wide and would otherwise be decoded at full size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Not a decodable bitmap, for example an SVG asset.
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) {
            return 1
        }
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "SimPayAndroidSDK")
        }
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
