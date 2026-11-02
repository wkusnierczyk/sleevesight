package app.sleevesight.app.ui.settings

import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What saving keys switches on (#763): the services to enable on *Load keys*, what each choice
 * writes, the summary's services, and the key dialog's offer.
 */
class KeyChoicesTest {
    private val saved = SecretState.SAVED

    /** Settings with nothing keyed on, as a new phone's are once the keys' gate has run. */
    private val none =
        Settings(
            aiProvider = null,
            enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.LASTFM - SourceSwitch.WEB,
            webEngines = emptySet(),
            labelSources = Settings.DEFAULT_LABEL_SOURCES - LabelSource.LASTFM,
        )

    @Test
    fun `one AI key with none saved before is a ticked checkbox, as every source is`() {
        val plan = enablePlan(listOf(SecretId.ANTHROPIC, SecretId.LASTFM, SecretId.EXA), emptyMap(), none)
        assertFalse(plan.radios)
        assertTrue(plan.configured.isEmpty())
        assertEquals(mapOf(SecretId.ANTHROPIC to true, SecretId.LASTFM to true, SecretId.EXA to true), plan.initial)
    }

    @Test
    fun `several AI keys are radios, none selected`() {
        val plan = enablePlan(listOf(SecretId.ANTHROPIC, SecretId.OPENAI, SecretId.LASTFM), emptyMap(), none)
        assertTrue(plan.radios)
        assertEquals(
            mapOf(SecretId.ANTHROPIC to false, SecretId.OPENAI to false, SecretId.LASTFM to true),
            plan.initial,
        )
    }

    @Test
    fun `an AI key saved before is listed as configured, selected while it is the AI service on`() {
        val settings = none.copy(aiProvider = ProviderId.GOOGLE)
        val plan = enablePlan(listOf(SecretId.ANTHROPIC, SecretId.EXA), mapOf(SecretId.GOOGLE to saved), settings)
        assertTrue(plan.radios)
        assertEquals(listOf(SecretId.GOOGLE), plan.configured)
        assertEquals(mapOf(SecretId.ANTHROPIC to false, SecretId.EXA to true, SecretId.GOOGLE to true), plan.initial)
        assertEquals(SecretId.GOOGLE, plan.currentConfigured())

        val off = enablePlan(listOf(SecretId.OPENAI), mapOf(SecretId.GOOGLE to saved), none)
        assertTrue(off.radios)
        assertEquals(mapOf(SecretId.OPENAI to false, SecretId.GOOGLE to false), off.initial)
        assertNull(off.currentConfigured())
    }

    @Test
    fun `a key replacing the AI service's on stays on, and a source replaced starts ticked even if off`() {
        val settings = none.copy(aiProvider = ProviderId.ANTHROPIC)
        val before = mapOf(SecretId.ANTHROPIC to saved, SecretId.LASTFM to saved)
        val plan = enablePlan(listOf(SecretId.ANTHROPIC, SecretId.LASTFM), before, settings)
        assertFalse(plan.radios)
        assertEquals(mapOf(SecretId.ANTHROPIC to true, SecretId.LASTFM to true), plan.initial)

        // Replaced while off: it stays off unless chosen.
        val offPlan = enablePlan(listOf(SecretId.ANTHROPIC), mapOf(SecretId.ANTHROPIC to saved), none)
        assertEquals(mapOf(SecretId.ANTHROPIC to false), offPlan.initial)
    }

    @Test
    fun `choosing an AI service unchooses the other, and unselecting leaves none`() {
        val choices = mapOf(SecretId.ANTHROPIC to false, SecretId.GOOGLE to true, SecretId.EXA to true)
        val chosen = choices.chosen(SecretId.ANTHROPIC, true)
        assertEquals(mapOf(SecretId.ANTHROPIC to true, SecretId.GOOGLE to false, SecretId.EXA to true), chosen)
        val settings = none.copy(aiProvider = ProviderId.GOOGLE).applied(chosen)
        assertEquals(ProviderId.ANTHROPIC, settings.aiProvider)

        val unselected = chosen.chosen(SecretId.ANTHROPIC, false)
        assertNull(none.copy(aiProvider = ProviderId.GOOGLE).applied(unselected).aiProvider)
    }

    @Test
    fun `Last_fm switches on as a source and for genre labels, Exa with web search`() {
        val on = none.applied(mapOf(SecretId.LASTFM to true, SecretId.EXA to true))
        assertTrue(SourceSwitch.LASTFM in on.enabledSources)
        assertTrue(LabelSource.LASTFM in on.labelSources)
        assertTrue(SourceSwitch.WEB in on.enabledSources && WebEngine.EXA in on.webEngines)
        assertTrue(on.serviceOn(SecretId.LASTFM) && on.serviceOn(SecretId.EXA))

        val off = on.applied(mapOf(SecretId.LASTFM to false, SecretId.EXA to false))
        assertFalse(off.serviceOn(SecretId.LASTFM) || off.serviceOn(SecretId.EXA))
    }

    @Test
    fun `an AI service unticked that was not on changes nothing`() {
        val settings = none.copy(aiProvider = ProviderId.GOOGLE)
        assertEquals(settings, settings.applied(mapOf(SecretId.OPENAI to false)))
    }

    @Test
    fun `the summary lists the services saved, and an AI service whose state changed`() {
        val before = none.copy(aiProvider = ProviderId.ANTHROPIC)
        val secrets = mapOf(SecretId.ANTHROPIC to saved, SecretId.EXA to saved)
        val choices = mapOf(SecretId.EXA to false, SecretId.OPENAI to true, SecretId.ANTHROPIC to false)
        val keys = listOf(SavedKey(SecretId.EXA, "b4Lw", replaced = true), SavedKey(SecretId.OPENAI, "3fXa", false))
        val summary = summaryOf(keys, before, secrets, before.applied(choices))
        assertEquals(
            mapOf(SecretId.EXA to false, SecretId.OPENAI to true, SecretId.ANTHROPIC to false),
            summary.services,
        )
        // An AI service that stays as it was is not listed.
        val kept = summaryOf(keys.take(1), before, secrets, before.applied(mapOf(SecretId.EXA to true)))
        assertEquals(mapOf(SecretId.EXA to true), kept.services)
    }

    @Test
    fun `the key dialog offers Enable, Switch, or says the AI service is already on`() {
        val google = mapOf(SecretId.GOOGLE to saved)
        assertEquals(EnableOffer.Enable, enableOffer(SecretId.OPENAI, none, emptyMap()))
        assertEquals(EnableOffer.Enable, enableOffer(SecretId.EXA, none.copy(aiProvider = ProviderId.GOOGLE), google))
        val switch = enableOffer(SecretId.OPENAI, none.copy(aiProvider = ProviderId.GOOGLE), google)
        assertEquals(EnableOffer.Switch(ProviderId.GOOGLE), switch)
        assertEquals(
            EnableOffer.AlreadyOn,
            enableOffer(SecretId.GOOGLE, none.copy(aiProvider = ProviderId.GOOGLE), google),
        )
        // An AI service on with no key saved counts as off, as its switch shows.
        assertEquals(
            EnableOffer.Enable,
            enableOffer(SecretId.OPENAI, none.copy(aiProvider = ProviderId.GOOGLE), emptyMap()),
        )

        assertEquals(mapOf(SecretId.OPENAI to true), switch.choices(SecretId.OPENAI, ticked = true))
        assertTrue(switch.choices(SecretId.OPENAI, ticked = false).isEmpty())
        assertEquals(mapOf(SecretId.EXA to false), EnableOffer.Enable.choices(SecretId.EXA, ticked = false))
        assertTrue(EnableOffer.AlreadyOn.choices(SecretId.GOOGLE, ticked = false).isEmpty())
    }
}
