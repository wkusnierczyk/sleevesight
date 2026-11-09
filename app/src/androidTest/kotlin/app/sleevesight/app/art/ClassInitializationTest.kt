package app.sleevesight.app.art

import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every class of the app's own code initialized once on Android's runtime (#1114): the classes the
 * debug APK's dex files define under `app.sleevesight.`, every module's that ships, each loaded and
 * initialized, its static initializer and an `object`'s constructor run. An initializer that throws
 * on ART but not on the JVM, as a regex ICU rejects did (#1113), fails here by construction, though
 * the JVM's tests pass.
 */
class ClassInitializationTest {
    @Test
    fun everyClassOfTheAppInitializesOnArt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = classesIn(context.packageCodePath).filter { it.startsWith(PREFIX) }
        // The APK read: its classes found, not an empty list that would pass with nothing loaded.
        assertTrue("only ${names.size} classes under $PREFIX in ${context.packageCodePath}", names.size > MIN_CLASSES)
        val failures =
            names.mapNotNull { name ->
                try {
                    Class.forName(name, true, context.classLoader)
                    null
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    "$name: ${generateSequence(e) { it.cause }.joinToString(" <- ")}"
                }
            }
        assertEquals("classes that failed to initialize on ART", "", failures.joinToString("\n"))
    }

    private companion object {
        const val PREFIX = "app.sleevesight."

        // Far fewer than the app has (thousands); enough to tell the APK was read.
        const val MIN_CLASSES = 500

        // The class names in every dex file of the APK at [path]. `DexFile` is deprecated for
        // loading code, not for listing it, and no other API lists an APK's classes.
        @Suppress("DEPRECATION")
        fun classesIn(path: String): List<String> {
            val dex = DexFile(path)
            return try {
                dex.entries().toList()
            } finally {
                dex.close()
            }
        }
    }
}
