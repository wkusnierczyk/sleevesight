package app.sleevesight.core.settings

import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The effort a text's credit names (#1162, [Settings.creditedEffort]): none at the default, the
 * provider's own (none sent) or its scale's, the level *Settings* chooses until another is set;
 * any other level as it is; a provider with no scale passes its effort through.
 */
class CreditedEffortTest {
    @Test
    fun noEffortIsNoneNamed() {
        for (provider in ProviderId.entries) assertNull(Settings.creditedEffort(provider, null), "$provider")
    }

    @Test
    fun eachScalesDefaultIsNotNamed() {
        for ((provider, scale) in Settings.EFFORT_SCALES) {
            assertNull(Settings.creditedEffort(provider, scale.default), "$provider")
        }
    }

    @Test
    fun anyOtherLevelIsNamed() {
        for ((provider, scale) in Settings.EFFORT_SCALES) {
            for (level in scale.levels - scale.default) {
                assertEquals(level, Settings.creditedEffort(provider, level), "$provider $level")
            }
        }
    }

    @Test
    fun aProviderWithNoScalePassesItsEffortThrough() {
        val unscaled = ProviderId.entries.filter { it !in Settings.EFFORT_SCALES }
        check(unscaled.isNotEmpty()) { "every provider has a scale: nothing to check" }
        for (provider in unscaled) {
            for (level in ReasoningEffort.entries) {
                assertEquals(level, Settings.creditedEffort(provider, level), "$provider $level")
            }
        }
    }
}
