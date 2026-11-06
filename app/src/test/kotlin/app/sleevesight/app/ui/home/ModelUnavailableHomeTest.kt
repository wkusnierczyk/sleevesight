package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AppItemNotes
import app.sleevesight.app.pipeline.AppPipelineParts
import app.sleevesight.app.pipeline.ModelCheck
import app.sleevesight.app.pipeline.PipelineHostFixture
import app.sleevesight.app.pipeline.PipelineKeys
import app.sleevesight.app.pipeline.rebuilds
import app.sleevesight.app.pipeline.shown
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ChangeCause
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackChange
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

/**
 * Home after the service refused the selected model, from the provider to the card (#969), wired as
 * the app wires it (`AppGraph`, `MainActivity`): an AI service that lists the model and answers that
 * it does not know it; the real `ModelCheck`, run on the settings and heard through the AI access
 * `AppPipelineParts` builds; the host rebuilt on `ModelCheck.rebuilds`; the song's insight from
 * `AppItemNotes` on the host's live parts; and Home ([NowPlayingHome]) fed the host's view and
 * `ModelCheck.shown`, as `AppGraph.unavailableModel`. The stages that would send other requests are
 * the host fixture's fakes. With an insight kept, under *On demand* and an automatic mode, the card's
 * footer and the song's end with the unavailable-model note, whose *AI services* opens the model's
 * row, above a disabled *Write insight*; nothing on Home gives the refusal's reason.
 *
 * The host, the check and the notes run on one thread of their own, in real time (the host's
 * debounce included), and the screen waits for what they publish.
 */
@RunWith(RobolectricTestRunner::class)
internal class ModelUnavailableHomeTest : PipelineHostFixture() {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // The service: it lists both models, and while [refusing], answers each call as a model it does not know.
    private val listing: AiProvider by lazy {
        object : AiProvider by provider {
            override suspend fun models(key: Secret): ModelList =
                ModelList.Listed(listOf(FIRST, OTHER).map { ModelInfo(it, it, null) })
        }
    }

    private val wiring =
        appPipelineWiring("0.1.0", JvmAccentFolder, JvmResourceReader, ArtistResults.None, aiThrough = { listing })

    private val thread = Executors.newSingleThreadExecutor()
    private val dispatcher = thread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val check = ModelCheck(wiring.aiProviders, { Secret("ai") }, scope)
    private val playing = MutableStateFlow<NowPlaying?>(null)
    private val opened = mutableListOf<SettingsTarget>()

    @After
    fun close() {
        scope.cancel()
        thread.shutdownNow()
        wiring.close()
    }

    // The fixture's stages, with the AI access as the app's parts build it, through [check].
    private fun appParts(): (PipelineKeys, Settings) -> PipelineParts {
        val app = AppPipelineParts(wiring, Normalizer(JvmAccentFolder), models = check) { cache }
        return { keys, built ->
            val fake = fakeParts(keys, built)
            val real = app(keys, built)
            PipelineParts(
                fake.normalizer,
                fake.adapters,
                fake.resolver,
                fake.hasher,
                real.ai,
                fake.cache,
                modelUnavailable = real.modelUnavailable,
            )
        }
    }

    private fun model(id: String) {
        settings.value = settings.value.withModel(ProviderId.ANTHROPIC, id)
    }

    // Home on the app's wiring, in [mode], with the first model selected.
    private fun home(
        mode: WriteAutomatically,
        block: () -> Unit,
    ) {
        settings.value = settings.value.copy(writeAutomatically = mode)
        model(FIRST)
        scope.launch { check.run(settings, keyChanges.map {}, flowOf(true)) }
        host = newHost(appParts(), check.rebuilds(settings))
        host.activityStarted()
        host.start(scope)
        val notes = AppItemNotes(host.liveParts, settings, scope, NameMatcher(JvmAccentFolder), epoch, io = dispatcher)
        val feed =
            HomeFeed(
                view = host.state,
                nowPlaying = playing,
                playback = MutableStateFlow(PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)),
                art = MutableStateFlow(null),
                settings = settings,
                normalizer = Normalizer(JvmAccentFolder),
                playerLabel = { it },
                playerActivity = { null },
                write = host::write,
                stop = host::stop,
                unavailableModel = check.shown(settings).stateIn(scope, SharingStarted.Eagerly, null),
                itemNotes = notes,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingHome(feed, openSettingsAt = { target -> opened += target })
                    }
                }
            }
            block()
        }
    }

    // [title] playing, on its [play]th play, having followed the last by itself.
    private fun play(
        title: String,
        play: Long,
    ) {
        val now = NowPlaying(title = title, artists = "Artist", player = Settings.SPOTIFY, play = play)
        playing.value = now
        changes.value = TrackChange(now, ChangeCause.NATURAL_END)
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT_MS) { condition() }
        compose.waitForIdle()
    }

    private fun insight(): InsightState? =
        host.state.value.track
            ?.insight

    private fun writtenBy(): String? = (insight() as? InsightState.Written)?.writtenBy?.model

    private fun refused(): Boolean = check.unavailable.value[ProviderId.ANTHROPIC]?.model == OTHER

    // The key insight's Write, as a tap on it sends it, once it is offered.
    private fun tapWrite() {
        waitFor { host.state.value.writeKey != null }
        tap()
    }

    private fun songRow() = compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performScrollTo()

    private fun songWrite() = compose.onNodeWithTag(itemWriteTag(InsightItem.SONG)).performScrollTo()

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun top(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().positionInRoot.y

    private val said =
        "The currently selected Anthropic model $OTHER is not available. " +
            "Please select another model or provider in AI services."

    private val previously = app.getString(R.string.home_written_before)

    // No words of a refusal of the model, nor the start of a line that would give one, anywhere on Home.
    private fun assertNoRefusalSaid() {
        val words =
            listOf(R.string.home_failure_model_not_found, R.string.home_failure_model_refused).map(app::getString) +
                app.getString(R.string.home_rewrite_failed, "").substringBefore(':') +
                app.getString(R.string.home_item_failed, "").substringBefore(':') +
                app.getString(R.string.home_insight_failed, "").substringBefore(':')
        for (text in words) compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    }

    // The lowest note that the model is unavailable: the song's under the key insight's, or the key insight's.
    private fun lastNote(): Float =
        compose
            .onAllNodesWithTag(MODEL_UNAVAILABLE_TAG)
            .fetchSemanticsNodes()
            .maxOf { it.positionInRoot.y }

    // Nothing with text between the note at [note] and the button at [button]: the note ends its footer.
    private fun assertNothingBetween(
        note: Float,
        button: Float,
    ) {
        val between =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
                .fetchSemanticsNodes()
                .filter { it.positionInRoot.y > note && it.positionInRoot.y < button }
        assertEquals("nothing between the note and the button", emptyList<Any>(), between.map { it.config })
    }

    // The note's *AI services* opens the model's row in the settings (#581).
    private fun assertNoteLinks(node: SemanticsNodeInteraction) {
        node.performFirstLinkClick()
        compose.waitForIdle()
        assertEquals(listOf(SettingsTarget(SettingsSection.AI_SERVICES, "model_ANTHROPIC")), opened)
    }

    // The key insight kept, its footer ended by the note, above the disabled Write insight.
    private fun assertKeyFooterEndsWithTheNote() {
        // The key insight's: the first, above the song's when it has one.
        val history = topmost(compose.onAllNodesWithText(previously)).performScrollTo().assertTextEquals(previously)
        val report = topmost(compose.onAllNodesWithTag(REPORT_TAG))
        val note = topmost(compose.onAllNodesWithTag(MODEL_UNAVAILABLE_TAG)).assertTextEquals(said)
        val write = compose.onNodeWithTag(NO_ACTION_TAG).assertIsNotEnabled()
        write.assertTextContains(app.getString(R.string.home_write))
        assertEquals(0, count(WRITE_TAG))
        val tops = listOf(history, report, note, write).map(::top)
        assertEquals("top to bottom, in this order", tops.sorted(), tops)
        assertNothingBetween(top(note), top(write))
        assertNoRefusalSaid()
        assertNoteLinks(note)
    }

    private fun topmost(nodes: SemanticsNodeInteractionCollection) =
        (0 until nodes.fetchSemanticsNodes().size).map { nodes[it] }.minBy(::top)

    private fun lowest(nodes: SemanticsNodeInteractionCollection) =
        (0 until nodes.fetchSemanticsNodes().size).map { nodes[it] }.maxBy(::top)

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    // Whether the song's button reads [label] (*Write insight* or *Rewrite insight*, after its AI mark).
    private fun songWriteSays(label: Int): Boolean =
        compose
            .onAllNodesWithTag(itemWriteTag(InsightItem.SONG))
            .fetchSemanticsNodes()
            .any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { app.getString(label) in it.text }
            }

    @Test
    fun underOnDemandARewriteRefusedEndsTheKeyInsightsFooterWithTheNote() =
        home(WriteAutomatically.NEVER) {
            play("Alpha", play = 1)
            tapWrite()
            waitFor { writtenBy() == FIRST }
            model(OTHER)
            waitFor { host.state.value.rewrite }

            // *Rewrite insight* refused, the rebuild without AI access not yet published: the card is
            // given the failure's reason, and says the model unavailable instead.
            val reading = CompletableDeferred<Unit>()
            keysReady = reading
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            tap()
            waitFor { refused() && host.state.value.rewriteFailed == MODEL_NOT_FOUND }
            assertKeyFooterEndsWithTheNote()

            // Rebuilt: the insight kept alone, the same footer.
            opened.clear()
            reading.complete(Unit)
            waitFor { host.state.value.rewriteFailed == null && (insight() as? InsightState.Written)?.cached == true }
            assertEquals(FIRST, writtenBy())
            assertKeyFooterEndsWithTheNote()
        }

    @Test
    fun underAnAutomaticModeAWriteAnewRefusedOnALaterPlayEndsTheKeyInsightsFooterWithTheNote() =
        home(WriteAutomatically.ON_EVERY_TRACK_CHANGE) {
            play("Alpha", play = 1)
            tapWrite()
            waitFor { writtenBy() == FIRST }
            play("Beta", play = 2)
            waitFor {
                host.state.value.track
                    ?.nowPlaying
                    ?.title == "Beta" &&
                    writtenBy() == FIRST
            }
            model(OTHER)
            waitFor { host.state.value.cardShown }

            // A later play, written anew by itself and refused: the insight kept, once rebuilt.
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            play("Alpha", play = 3)
            waitFor {
                refused() &&
                    host.state.value.track
                        ?.nowPlaying
                        ?.play == 3L &&
                    writtenBy() == FIRST
            }
            assertTrue((insight() as InsightState.Written).cached)
            assertEquals(3, provider.requests)
            assertKeyFooterEndsWithTheNote()
        }

    @Test
    fun underOnDemandARewriteRefusedEndsTheSongsFooterWithTheNote() =
        home(WriteAutomatically.NEVER) {
            play("Alpha", play = 1)
            waitFor { count(itemRowTag(InsightItem.SONG)) == 1 }
            // The row opened by a tap writes at once, in every mode (#948).
            songRow().performClick()
            waitFor { provider.completed == 1 }
            model(OTHER)
            waitFor { songWriteSays(R.string.home_rewrite) }

            // *Rewrite insight* refused: the song's insight kept, "As written previously." alone, the
            // note last, above a disabled *Write insight*.
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            songWrite().assertIsEnabled().performClick()
            waitFor { refused() && shown(previously) == 1 && count(MODEL_UNAVAILABLE_TAG) == 2 }
            val history = compose.onNodeWithText(previously).performScrollTo().assertTextEquals(previously)
            val report = compose.onNodeWithTag(REPORT_TAG)
            val button = songWrite().assertIsNotEnabled().assertTextContains(app.getString(R.string.home_write))
            val note = lastNote()
            val tops = listOf(top(history), top(report), note, top(button))
            assertEquals("top to bottom, in this order", tops.sorted(), tops)
            assertNothingBetween(note, top(button))
            assertNoRefusalSaid()
            val notes = compose.onAllNodesWithTag(MODEL_UNAVAILABLE_TAG)
            val song = (0 until 2).map { notes[it] }.maxBy(::top)
            song.assertTextEquals(said)
            assertNoteLinks(song)
            assertEquals(2, provider.requests)
        }

    @Test
    fun underAnAutomaticModeAWriteAnewRefusedOnALaterPlayEndsTheSongsFooterWithTheNote() =
        // *On track skip (auto)*: the key insight waits on a track that followed by itself, and the
        // song's row opened by a tap writes at once.
        home(WriteAutomatically.WHEN_I_SKIP) {
            play("Alpha", play = 1)
            tapWrite()
            waitFor { writtenBy() == FIRST }
            waitFor { count(itemRowTag(InsightItem.SONG)) == 1 }
            songRow().performClick()
            waitFor { provider.completed == 2 }
            // Closed again, so the later play opens it by a tap.
            compose.onNodeWithTag("${itemPanelTag(InsightItem.SONG)}:close").performScrollTo().performClick()
            model(OTHER)
            play("Beta", play = 2)
            play("Alpha", play = 3)
            waitFor {
                host.state.value.track
                    ?.nowPlaying
                    ?.play == 3L &&
                    host.state.value.cardShown
            }

            // A later play: the row opened writes anew, and is refused (#975). The song's insight kept,
            // "As written previously." alone, the note last, above a disabled *Write insight*, as after
            // a refused *Rewrite insight*; the key insight kept ends with the note too.
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            songRow().performClick()
            waitFor { refused() && shown(previously) == 2 && count(MODEL_UNAVAILABLE_TAG) == 2 }
            assertEquals(0, count(ITEM_FAILED_TAG))
            val button = songWrite().assertIsNotEnabled().assertTextContains(app.getString(R.string.home_write))
            val history = lowest(compose.onAllNodesWithText(previously)).assertTextEquals(previously)
            val report = lowest(compose.onAllNodesWithTag(REPORT_TAG))
            val song = lowest(compose.onAllNodesWithTag(MODEL_UNAVAILABLE_TAG)).assertTextEquals(said)
            val tops = listOf(history, report, song, button).map(::top)
            assertEquals("top to bottom, in this order", tops.sorted(), tops)
            assertNothingBetween(top(song), top(button))
            assertNoRefusalSaid()
            assertNoteLinks(song)
            assertEquals(3, provider.requests)
            opened.clear()
            assertKeyFooterEndsWithTheNote()
        }

    private companion object {
        const val FIRST = "model-first"
        const val OTHER = "model-other"
        const val MODEL_NOT_FOUND = "model_not_found"
        const val TIMEOUT_MS = 20_000L
    }
}
