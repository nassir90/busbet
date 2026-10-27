package iompar.mpts.ie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Covers the status line shown beside the Time heading in the window editor (TFI-114), and the
 * active/next-start rules underneath it that NotificationScheduler arms its alarm against.
 */
class NotificationWindowStatusTest {

    /** Mon-Fri 08:00-09:00 unless overridden. */
    private fun window(
        days: Set<Int> = setOf(1, 2, 3, 4, 5),
        start: Int = 8 * 60,
        end: Int = 9 * 60,
        enabled: Boolean = true,
    ) = NotificationWindow(
        name = "Commute", days = days, startMinute = start, endMinute = end,
        stopCode = "7796", stopName = "Somerton", routes = emptyList(), enabled = enabled,
    )

    // 2026-08-17 is a Monday.
    private fun mon(h: Int, m: Int = 0) = LocalDateTime.of(2026, 8, 17, h, m)
    private fun sat(h: Int, m: Int = 0) = LocalDateTime.of(2026, 8, 22, h, m)

    @Test
    fun `active inside the window`() {
        assertTrue(window().isActiveAt(mon(8, 30)))
        assertEquals("Currently active", window().statusLabel(mon(8, 30)))
    }

    @Test
    fun `start minute is inclusive and end minute is exclusive`() {
        assertTrue(window().isActiveAt(mon(8, 0)))
        assertFalse(window().isActiveAt(mon(9, 0)))
    }

    @Test
    fun `not active on a day that is not selected`() {
        assertFalse(window().isActiveAt(sat(8, 30)))
    }

    @Test
    fun `counts down in hours and minutes when under a day`() {
        // Monday 05:40 -> next start Monday 08:00 is 2h 20m away.
        assertEquals("Active in 2h 20m", window().statusLabel(mon(5, 40)))
    }

    @Test
    fun `counts down in minutes only when under an hour`() {
        assertEquals("Active in 25m", window().statusLabel(mon(7, 35)))
    }

    @Test
    fun `counts down in days and hours when over a day`() {
        // Saturday 10:00 -> next start is Monday 08:00, 1d 22h away.
        assertEquals("Active in 1d 22h", window().statusLabel(sat(10, 0)))
    }

    @Test
    fun `after today's window the next start rolls to the following day`() {
        // Monday 09:30 is past today's window, so the next start is Tuesday 08:00.
        assertEquals(LocalDateTime.of(2026, 8, 18, 8, 0), window().nextStartAfter(mon(9, 30)))
    }

    @Test
    fun `a weekly window rolls a full seven days, not six`() {
        val mondayOnly = window(days = setOf(1))
        assertEquals(LocalDateTime.of(2026, 8, 24, 8, 0), mondayOnly.nextStartAfter(mon(9, 30)))
    }

    @Test
    fun `disabled reports disabled rather than a countdown`() {
        assertEquals("Disabled", window(enabled = false).statusLabel(mon(8, 30)))
    }

    @Test
    fun `no days selected never starts`() {
        val none = window(days = emptySet())
        assertNull(none.nextStartAfter(mon(5, 0)))
        assertEquals("No days selected", none.statusLabel(mon(5, 0)))
    }

    @Test
    fun `end not after start is reported rather than silently never firing`() {
        assertEquals("End is not after start", window(start = 9 * 60, end = 8 * 60).statusLabel(mon(5, 0)))
    }
}
