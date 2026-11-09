package app.sleevesight.app.art

import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexFile
import org.junit.Assert.assertEquals
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
        // The APK read whole: a class of each shipped module found, not a list that would pass with
        // part of the code, or none of it, loaded.
        val missing = MODULE_CLASSES - names.toSet()
        val read = "${names.size} classes read from ${context.packageCodePath}"
        assertEquals("module classes not found among the $read", emptySet<String>(), missing)
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

        // One class of each module the APK ships, by name: `:net`'s may not be named in `:app`'s code.
        val MODULE_CLASSES =
            setOf(
                "app.sleevesight.core.normalizer.Normalizer",
                "app.sleevesight.net.NetConfig",
                "app.sleevesight.sources.wikipedia.WikipediaClient",
                "app.sleevesight.ai.openai.OpenAiProvider",
                "app.sleevesight.wiring.PipelineWiring",
                "app.sleevesight.store.SynthesisCache",
                "app.sleevesight.app.MainActivity",
            )

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
