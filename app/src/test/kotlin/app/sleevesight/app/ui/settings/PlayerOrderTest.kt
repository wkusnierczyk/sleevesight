package app.sleevesight.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The order *Players* and Home list players in (#627): by name, as the language sorts it. */
class PlayerOrderTest {
    private fun player(
        packageName: String,
        name: String,
    ) = ListedPlayer(packageName, name, launch = null)

    private fun order(vararg players: ListedPlayer) = players.sortedWith(byName(Locale.ENGLISH)).map { it.packageName }

    @Test
    fun byNameNotByPackage() {
        assertEquals(listOf("com.z.aaa", "com.a.zzz"), order(player("com.a.zzz", "Zulu"), player("com.z.aaa", "Alpha")))
    }

    @Test
    fun whateverTheCase() {
        assertEquals(listOf("b", "a"), order(player("a", "Beta"), player("b", "alpha")))
    }

    @Test
    fun asTheLanguageSortsAccents() {
        // By code point, "É" would come after "Z".
        assertEquals(listOf("e", "z"), order(player("z", "Zune"), player("e", "Écoute")))
    }

    @Test
    fun theSameNameByPackageSoTheOrderStaysPut() {
        assertEquals(listOf("a.one", "b.two"), order(player("b.two", "Music"), player("a.one", "Music")))
    }
}
