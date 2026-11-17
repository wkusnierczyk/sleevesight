package app.sleevesight.app.pipeline

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The listener's choices among albums of one name, kept on the phone (#1080): each with the albums it
 * was made among, read back as kept, forgotten one by one and all at once.
 */
@RunWith(RobolectricTestRunner::class)
class KeptAlbumChoicesTest {
    private val store = KeptAlbumChoices(ApplicationProvider.getApplicationContext())

    @Test
    fun aChoiceIsReadBackWithTheAlbumsItWasMadeAmong() =
        runTest {
            val choice = AlbumChoice("g-2006", listOf("g-2001", "g-2006", "g-undated"))
            store.keep("greatest hits\u001fbilly idol", choice)
            store.keep("hits\u001fother", AlbumChoice("g-a", listOf("g-a", "g-b")))
            assertEquals(
                choice,
                KeptAlbumChoices(ApplicationProvider.getApplicationContext()).load()["greatest hits\u001fbilly idol"],
            )
            store.keep("hits\u001fother", null)
            assertEquals(setOf("greatest hits\u001fbilly idol"), store.load().keys)
            store.clear()
            assertEquals(emptyMap<String, AlbumChoice>(), store.load())
        }
}
