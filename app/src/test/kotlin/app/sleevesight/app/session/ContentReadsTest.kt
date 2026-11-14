package app.sleevesight.app.session

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.ArtLoaderTest.PlayerArt
import app.sleevesight.app.session.Sessions.bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The bounds [ContentReads] puts on `ArtLoader`'s `content://` reads (#1246). */
@RunWith(RobolectricTestRunner::class)
class ContentReadsTest {
    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
    private val fetched = Collections.synchronizedList(mutableListOf<String>())
    private val http =
        HttpArt { uri ->
            fetched += uri
            null
        }

    /** Every URI the provider was asked for, in order. */
    private val asked = Collections.synchronizedList(mutableListOf<String>())
    private val entered = Semaphore(0)
    private val release = CountDownLatch(1)

    /** One permit for each read whose thread has returned. */
    private val returned = Semaphore(0)

    /** Reads handed to [executor], counted before their thread starts. */
    private val handed = AtomicInteger()
    private val executor =
        Executor { task ->
            handed.incrementAndGet()
            thread(isDaemon = true) {
                try {
                    task.run()
                } finally {
                    returned.release()
                }
            }
        }

    /**
     * Providers at [AUTHORITY] and [OTHER] that hang on every open until [release] (bounded, so a
     * stuck test ends), except at [serving], which serves a green image at once.
     */
    private fun provideHanging(serving: String? = OTHER) {
        PlayerArt.open = { uri: Uri, _: CancellationSignal? ->
            asked += uri.toString()
            if (uri.authority == serving) {
                green()
            } else {
                entered.release()
                release.await(30, TimeUnit.SECONDS)
                null
            }
        }
        Robolectric.setupContentProvider(PlayerArt::class.java, AUTHORITY)
        Robolectric.setupContentProvider(PlayerArt::class.java, OTHER)
    }

    private fun green(): ParcelFileDescriptor {
        val png =
            ByteArrayOutputStream().also { bitmap(Color.GREEN, 8).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val file =
            File.createTempFile("art", ".png").apply {
                deleteOnExit()
                writeBytes(png.toByteArray())
            }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun loader(cap: Int) = ArtLoader(resolver, http, Dispatchers.Unconfined, ContentReads(cap, executor))

    private fun sources(
        path: String,
        authority: String = AUTHORITY,
    ) = listOf(ArtSource.Content("content://$authority/$path"), ArtSource.Http("https://cdn/$path"))

    @After
    fun forgetProvider() {
        release.countDown()
        PlayerArt.open = { _, _ -> null }
    }

    @Test
    fun withEveryReadSlotHeldTheContentUriIsSkippedForTheNextSource() =
        runTest {
            provideHanging(serving = null)
            val loader = loader(cap = 3)
            // Three loads cancelled while their reads hang, as when the track changes: their threads
            // stay held, and nothing is marked stuck, since none timed out.
            val held =
                listOf(sources("a1"), sources("a2"), sources("b1", OTHER)).map { async { loader.load(it) } }
            runCurrent()
            assertTrue("the three reads reach the providers", entered.tryAcquire(3, 10, TimeUnit.SECONDS))
            held.forEach { it.cancel() }
            runCurrent()

            assertNull(loader.load(sources("next", THIRD)))
            assertEquals("no fourth read starts", 3, handed.get())
            assertEquals("the next source is tried", listOf("https://cdn/next"), fetched)
        }

    @Test
    fun oneProvidersHangingReadsLeaveASlotForAnotherProvider() =
        runTest {
            provideHanging()
            val loader = loader(cap = 3)
            // Track changes before the timeout: cancelled reads of one provider, still hanging.
            val held = List(3) { async { loader.load(sources("a$it")) } }
            runCurrent()
            assertTrue("two reads reach the provider", entered.tryAcquire(2, 10, TimeUnit.SECONDS))
            held.forEach { it.cancel() }
            runCurrent()
            assertEquals("the third read of that provider is not started", 2, handed.get())

            // In real time: the read answers on its own thread, which virtual time would not wait for.
            val art = withContext(Dispatchers.Default) { loader.load(sources("b", OTHER)) }
            assertNotNull("the other provider's art is read", art)
            assertEquals(Color.GREEN, art?.getPixel(0, 0))
        }

    @Test
    fun aProviderWhoseReadTimedOutIsNotAskedAgainWhileThatReadIsStuck() =
        runTest {
            provideHanging()
            val loader = loader(cap = 3)
            val first = async { loader.load(sources("1")) }
            runCurrent()
            assertTrue("the provider is asked", entered.tryAcquire(10, TimeUnit.SECONDS))
            // Virtual time passes the timeout: the read is left stuck on its thread.
            assertNull(first.await())

            assertNull(loader.load(sources("2")))
            assertEquals("the stuck provider is not asked again", 1, handed.get())
            assertEquals(listOf("https://cdn/1", "https://cdn/2"), fetched)
        }

    @Test
    fun aProviderIsAskedAgainOnceItsStuckReadReturns() =
        runTest {
            provideHanging()
            val loader = loader(cap = 3)
            val first = async { loader.load(sources("1")) }
            runCurrent()
            assertTrue("the provider is asked", entered.tryAcquire(10, TimeUnit.SECONDS))
            assertNull(first.await())

            release.countDown()
            assertTrue("the stuck read returns", returned.tryAcquire(10, TimeUnit.SECONDS))
            val second = async { loader.load(sources("2")) }
            runCurrent()
            assertTrue("the provider is asked again", entered.tryAcquire(10, TimeUnit.SECONDS))
            second.await()
            assertEquals(listOf("content://$AUTHORITY/1", "content://$AUTHORITY/2"), asked.toList())
        }

    private companion object {
        const val AUTHORITY = "player.art"
        const val OTHER = "other.art"
        const val THIRD = "third.art"
    }
}
