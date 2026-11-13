package app.sleevesight.app.session

import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.ArtLoaderTest.PlayerArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
    private val executor =
        Executor { task ->
            thread(isDaemon = true) {
                try {
                    task.run()
                } finally {
                    returned.release()
                }
            }
        }

    /** A provider that hangs on every open until [release]; bounded so a stuck test ends. */
    private fun provideHanging() {
        PlayerArt.open = { uri: Uri, _: CancellationSignal? ->
            asked += uri.toString()
            entered.release()
            release.await(30, TimeUnit.SECONDS)
            null as ParcelFileDescriptor?
        }
        Robolectric.setupContentProvider(PlayerArt::class.java, AUTHORITY)
    }

    private fun loader(cap: Int) = ArtLoader(resolver, http, Dispatchers.Unconfined, ContentReads(cap, executor))

    private fun sources(path: String) =
        listOf(ArtSource.Content("content://$AUTHORITY/$path"), ArtSource.Http("https://cdn/$path"))

    @After
    fun forgetProvider() {
        release.countDown()
        PlayerArt.open = { _, _ -> null }
    }

    @Test
    fun withEveryReadSlotHeldTheContentUriIsSkippedForTheNextSource() =
        runTest {
            provideHanging()
            val loader = loader(cap = 2)
            // Two loads cancelled while their reads hang, as when the track changes: their threads
            // stay held, and nothing is marked stuck, since neither timed out.
            val held = List(2) { async { loader.load(sources("held$it")) } }
            runCurrent()
            assertTrue("both reads reach the provider", entered.tryAcquire(2, 10, TimeUnit.SECONDS))
            held.forEach { it.cancel() }
            runCurrent()

            assertNull(loader.load(sources("next")))
            assertEquals(
                "no third read starts",
                listOf("content://$AUTHORITY/held0", "content://$AUTHORITY/held1"),
                asked.sorted(),
            )
            assertEquals("the next source is tried", listOf("https://cdn/next"), fetched)
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
            assertEquals("the stuck provider is not asked again", listOf("content://$AUTHORITY/1"), asked.toList())
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
    }
}
