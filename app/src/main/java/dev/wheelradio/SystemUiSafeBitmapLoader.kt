package dev.wheelradio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.roundToInt

/**
 * Works around an Android 10 SystemUI bug that crash-loops "System UI keeps stopping" for as long as our
 * station is the active media session.
 *
 * Android 10's `MediaArtworkProcessor` scales the session artwork so its longer side is
 * `max(screenW, screenH) / 6` (320 px on the 1920x1080 head unit). When the artwork already has exactly
 * that size, `Bitmap.createScaledBitmap` returns the artwork itself, which SystemUI then recycles, and the
 * next pass fails with "cannot use a recycled source in createBitmap". Seen with Radio Marshall's 320x87
 * logo. Artwork whose longer side is exactly that size is scaled up by one pixel, so SystemUI always gets
 * a copy it can recycle. Every other image is passed through untouched.
 */
@OptIn(UnstableApi::class)
class SystemUiSafeBitmapLoader(
    private val delegate: BitmapLoader,
    /** The longer side SystemUI scales artwork to; see [systemUiArtworkSize]. */
    private val systemUiSize: Int,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String) = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = guard(delegate.decodeBitmap(data))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = guard(delegate.loadBitmap(uri))

    private fun guard(future: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> =
        Futures.transform(future, { safeForSystemUi(it, systemUiSize) }, MoreExecutors.directExecutor())

    companion object {
        /** `MediaArtworkProcessor.DOWNSAMPLE` in Android 10 SystemUI. */
        private const val SYSTEM_UI_DOWNSAMPLE = 6

        /** The size Android 10 SystemUI scales media artwork to on this device's main screen. */
        fun systemUiArtworkSize(context: Context): Int {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return 0
            val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
            return maxOf(size.x / SYSTEM_UI_DOWNSAMPLE, size.y / SYSTEM_UI_DOWNSAMPLE)
        }

        /** [bitmap] itself, or a copy one pixel larger when its longer side equals [systemUiSize]. */
        fun safeForSystemUi(bitmap: Bitmap, systemUiSize: Int): Bitmap {
            val longer = maxOf(bitmap.width, bitmap.height)
            if (systemUiSize <= 0 || longer != systemUiSize) return bitmap
            val scale = (longer + 1f) / longer
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
    }
}
