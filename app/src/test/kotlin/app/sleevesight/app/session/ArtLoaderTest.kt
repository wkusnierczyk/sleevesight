package app.sleevesight.app.session

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.Sessions.bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtLoaderTest {
    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val fetched = mutableListOf<String>()
    private var served: ByteArray? = null
    private val http =
        HttpArt { uri ->
            fetched += uri
            served
        }

    // Each test its own reads: a read a test leaves stuck must not hold another test's slots.
    private val loader = ArtLoader(resolver, http, Dispatchers.Unconfined, ContentReads())

    private fun png(
        color: Int,
        size: Int = 8,
    ): ByteArray =
        ByteArrayOutputStream()
            .also {
                bitmap(color, size).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()

    private fun load(vararg sources: ArtSource): Bitmap? = runBlocking { loader.load(sources.toList()) }

    /** A player's art provider: [open] answers each `openFile`. */
    class PlayerArt : ContentProvider() {
        override fun openFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor? = open(uri, signal)

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor? = open(uri, null)

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        companion object {
            var open: (Uri, CancellationSignal?) -> ParcelFileDescriptor? = { _, _ -> null }
        }
    }

    private fun provide(open: (Uri, CancellationSignal?) -> ParcelFileDescriptor?) {
        PlayerArt.open = open
        Robolectric.setupContentProvider(PlayerArt::class.java, AUTHORITY)
    }

    private fun served(bytes: ByteArray): ParcelFileDescriptor =
        File
            .createTempFile("art", ".png")
            .apply {
                deleteOnExit()
                writeBytes(bytes)
            }.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    @After
    fun forgetProvider() {
        PlayerArt.open = { _, _ -> null }
    }

    @Test
    fun theSessionsBitmapIsUsedAsItIsAndNothingIsFetched() {
        val art = bitmap(Color.RED)
        assertSame(art, load(ArtSource.Image(art), ArtSource.Http("https://cdn/1")))
        assertEquals(emptyList<String>(), fetched)
    }

    @Test
    fun aReadableContentUriIsDecoded() {
        val uri = Uri.parse("content://$AUTHORITY/v1/image/1")
        provide { _, _ -> served(png(Color.GREEN)) }
        val art = load(ArtSource.Content(uri.toString()), ArtSource.Http("https://cdn/1"))
        assertEquals(Color.GREEN, art?.getPixel(0, 0))
        assertEquals(emptyList<String>(), fetched)
    }

    @Test
    fun aContentUriThePlayerDoesNotShareFallsThroughToHttp() {
        val uri = Uri.parse("content://$AUTHORITY/art/1")
        provide { _, _ -> throw SecurityException("no grant") }
        served = png(Color.BLUE)
        val art = load(ArtSource.Content(uri.toString()), ArtSource.Http("https://cdn/1"))
        assertEquals(Color.BLUE, art?.getPixel(0, 0))
        assertEquals(listOf("https://cdn/1"), fetched)
    }

    @Test
    fun aProviderThatNeverAnswersIsGivenUpAndTheNextSourceTried() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val providerReturned = AtomicBoolean(false)
        provide { _, _ ->
            entered.countDown()
            // Bounded, so a loader that waits for the provider fails here rather than hanging.
            release.await(30, TimeUnit.SECONDS)
            providerReturned.set(true)
            null
        }
        served = png(Color.BLUE)
        try {
            runTest {
                val art =
                    async {
                        loader.load(
                            listOf(ArtSource.Content("content://$AUTHORITY/art/1"), ArtSource.Http("https://cdn/1")),
                        )
                    }
                runCurrent()
                // The provider has the request and hangs; virtual time then passes the timeout at once.
                assertTrue("the provider is asked", entered.await(10, TimeUnit.SECONDS))
                assertEquals(Color.BLUE, art.await()?.getPixel(0, 0))
                assertFalse("the load waited for the provider", providerReturned.get())
            }
            assertEquals(listOf("https://cdn/1"), fetched)
        } finally {
            release.countDown()
        }
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

    private companion object {
        const val AUTHORITY = "player.art"
    }
}
