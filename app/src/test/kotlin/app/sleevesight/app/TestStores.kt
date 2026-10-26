package app.sleevesight.app

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretKeySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    fun open(
        name: String,
        migrations: List<DataMigration<Preferences>> = emptyList(),
    ): DataStore<Preferences> {
        val job = Job()
        jobs += job
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            migrations = migrations,
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

/**
 * A DataStore held in memory, for a test that needs the settings but not their file: an update is
 * done, and seen by [data]'s collectors, on the caller's thread, with no thread of DataStore's own
 * to wait on, so a wait for it does not depend on how busy the machine is.
 */
class MemoryStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val writes = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writes.withLock { transform(state.value).also { state.value = it } }
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
 * failing read taking one, as a file that cannot be read for a while would. While
 * [failuresAfterAValue] is above zero, a read instead emits the stored value and then fails. While
 * [writeFailures] is above zero, a write (`edit`, `updateData`) fails with an I/O error and stores
 * nothing, each failing write taking one, as a file that cannot be written would. While [writeGate]
 * is set, a write waits for it before it starts; [writes] counts the writes asked for.
 */
class FlakyDataStore<T>(
    private val store: DataStore<T>,
) : DataStore<T> {
    val failures = AtomicInteger()
    val failuresAfterAValue = AtomicInteger()
    val reads = AtomicInteger()
    val writeFailures = AtomicInteger()
    val writes = AtomicInteger()

    @Volatile var writeGate: Deferred<Unit>? = null

    override val data: Flow<T> =
        flow {
            reads.incrementAndGet()
            if (failures.getAndUpdate { maxOf(it - 1, 0) } > 0) throw IOException("cannot read")
            if (failuresAfterAValue.getAndUpdate { maxOf(it - 1, 0) } > 0) {
                emit(store.data.first())
                throw IOException("cannot read")
            }
            emitAll(store.data)
        }

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        writes.incrementAndGet()
        writeGate?.await()
        if (writeFailures.getAndUpdate { maxOf(it - 1, 0) } > 0) throw IOException("cannot write")
        return store.updateData(transform)
    }
}
