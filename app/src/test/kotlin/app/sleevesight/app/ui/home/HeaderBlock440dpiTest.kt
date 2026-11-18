package app.sleevesight.app.ui.home

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [HeaderBlockTest]'s checks at 440 dpi, at the default font size and twice it (#1407): there a gap
 * of 2 dp is 5.5 px, each laid out as 6 px, so a minimum that rounded the gaps together would fall a
 * pixel short and the buttons would move when the last line came in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeaderBlock440dpiTest : HeaderBlockTest() {
    override val draws: Boolean = false
}
