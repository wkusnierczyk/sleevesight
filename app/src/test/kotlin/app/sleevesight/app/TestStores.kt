package app.sleevesight.app

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretKeySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * DataStores over files in [dir], as `AppGraph` makes them (the same corruption handler), each on
 * a scope of its own, so a test can close one and open the file again, as a new process would.
 */
class TestStores(
    private val dir: File,
) : AutoCloseable {
    private val jobs = mutableListOf<Job>()

    fun file(name: String): File = File(dir, "$name.preferences_pb")

    fun open(name: String): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file(name) },
        )
    }

    /** Closes every store opened so far, so their files can be opened again. */
    override fun close() {
        runBlocking { jobs.forEach { it.cancel() } }
        runBlocking { jobs.forEach { it.join() } }
        jobs.clear()
    }
}

/** A software AES key, standing in for the Keystore's on the JVM. */
fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

/**
 * A software key that, like the Keystore's, stays the same until it is deleted, and is made afresh
 * only when [create] is called.
 */
open class SoftwareKeySource(
    private var current: SecretKey? = softwareKey(),
) : SecretKeySource {
    var deletions = 0
    var creations = 0

    override fun find(): SecretKey? = current

    override fun create(): SecretKey {
        creations++
        return softwareKey().also { current = it }
    }

    override fun forget() = Unit

    override fun delete() {
        deletions++
        current = null
    }
}

/** A cipher with a fixed software key, as the Keystore's key is fixed for the app's life. */
fun testCipher(key: SecretKey = softwareKey()): SecretCipher = SecretCipher(SoftwareKeySource(key))

/**
 * A DataStore over [store] whose reads fail with an I/O error while [failures] is above zero, each
 * failing read taking one, as a file that cannot be read for a while would.
 */
class FlakyDataStore<T>(
    private val store: DataStore<T>,
) : DataStore<T> {
    val failures = AtomicInteger()
    val reads = AtomicInteger()

    override val data: Flow<T> =
        flow {
            reads.incrementAndGet()
            if (failures.getAndUpdate { maxOf(it - 1, 0) } > 0) throw IOException("cannot read")
            emitAll(store.data)
        }

    override suspend fun updateData(transform: suspend (t: T) -> T): T = store.updateData(transform)
}
