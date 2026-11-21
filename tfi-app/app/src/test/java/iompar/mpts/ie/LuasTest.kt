package iompar.mpts.ie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuasTest {
    private val jervisWest = Stop("8220GA00404", "998020", "Jervis", routes = listOf("Red"), routeTypes = listOf(0), towards = "Tallaght")
    private val bus = Stop("8220DB000292", "292", "Abbey St Lower", routes = listOf("33"), routeTypes = listOf(3))
    private val rail = Stop("8220IR0007", "0", "Connolly", routeTypes = listOf(2))

    @Test fun `a Luas platform is addressed by its own id, not the code it shares`() {
        assertEquals("8220GA00404", jervisWest.addressId)
        assertEquals("292", bus.addressId)
        assertEquals("8220IR0007", rail.addressId)
    }

    @Test fun `a Luas platform is labelled by where it goes`() {
        assertEquals("Jervis → Tallaght", jervisWest.searchLabel)
        assertEquals("Jervis", jervisWest.copy(towards = null).searchLabel)
        assertEquals("292  Abbey St Lower", bus.searchLabel)
        assertEquals("Connolly", rail.searchLabel)
    }

    @Test fun `only the Luas lines get their own colours`() {
        assertEquals(LUAS_RED, lineColors("Red"))
        assertEquals(LUAS_GREEN, lineColors("Green", routeType = 0))
        assertNull(lineColors("Red", routeType = 3))
        assertNull(lineColors("37"))
        assertNull(lineColors("DART", routeType = 2))
    }
}
