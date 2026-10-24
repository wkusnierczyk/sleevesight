package app.sleevesight.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The diagnostics screen is given skip detection's learning, as the app graph wires them (D15). */
@RunWith(RobolectricTestRunner::class)
class SkipLearningWiringTest {
    private val graph = ApplicationProvider.getApplicationContext<SleevesightApplication>().graph

    @Test
    fun theDiagnosticsShowWhatTheDetectorLearnedAndResetIt() {
        assertSame(graph.skipLearning, graph.diagnostics.skipLearning)
        assertSame(graph.skipDetector.learned, graph.skipLearning.learnedCrossfades)
    }
}
