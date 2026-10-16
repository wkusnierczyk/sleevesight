package app.sleevesight.app.session

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.Sessions.bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtLoaderTest {
    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val fetched = mutableListOf<String>()
    private var served: ByteArray? = null
    private val loader =
        ArtLoader(resolver, { uri ->
            fetched += uri
            served
        }, Dispatchers.Unconfined)

    private fun png(
        color: Int,
        size: Int = 8,
    ): ByteArray =
        ByteArrayOutputStream()
            .also {
                bitmap(color, size).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()

    private fun load(vararg sources: ArtSource): Bitmap? = runBlocking { loader.load(sources.toList()) }

    @Test
    fun theSessionsBitmapIsUsedAsItIsAndNothingIsFetched() {
        val art = bitmap(Color.RED)
        assertSame(art, load(ArtSource.Image(art), ArtSource.Http("https://cdn/1")))
        assertEquals(emptyList<String>(), fetched)
    }

    @Test
    fun aReadableContentUriIsDecoded() {
        val uri = Uri.parse("content://com.spotify.mobile.android.mediaapi/v1/image/1")
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream(png(Color.GREEN)))
        val art = load(ArtSource.Content(uri.toString()), ArtSource.Http("https://cdn/1"))
        assertEquals(Color.GREEN, art?.getPixel(0, 0))
        assertEquals(emptyList<String>(), fetched)
    }

    @Test
    fun aContentUriThePlayerDoesNotShareFallsThroughToHttp() {
        val uri = Uri.parse("content://player.private/art/1")
        shadowOf(resolver).registerInputStreamSupplier(uri) { throw SecurityException("no grant") }
        served = png(Color.BLUE)
        val art = load(ArtSource.Content(uri.toString()), ArtSource.Http("https://cdn/1"))
        assertEquals(Color.BLUE, art?.getPixel(0, 0))
        assertEquals(listOf("https://cdn/1"), fetched)
    }

    @Test
    fun noArtWhenNoSourceGivesAnImage() {
        served = "<html>".encodeToByteArray()
        assertNull(load(ArtSource.Http("https://cdn/1"), ArtSource.Http("https://cdn/2")))
        assertEquals(listOf("https://cdn/1", "https://cdn/2"), fetched)
        assertNull(load())
    }

    @Test
    fun largeArtIsScaledDown() {
        served = png(Color.RED, size = 3000)
        val art = load(ArtSource.Http("https://cdn/big"))
        assertEquals(750, art?.width)
    }
}
