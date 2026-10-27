package iompar.mpts.ie

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The clock the backend speaks.
 *
 * Every time in an API response — `scheduledDeparture`, `estimatedDeparture`, a trip's
 * first departure and last arrival — is a GTFS wall-clock time on the operator's service
 * day, i.e. Irish local time. It carries no offset and no date. So the moment the device
 * is set to anything other than Irish time, `LocalTime.now()` is the wrong "now" to
 * subtract from it: a phone on CET reads every Dublin bus as an hour further away than it
 * is, and one on PDT as eight hours in the past. That is not a display quirk — the stop
 * map hides buses whose due time falls outside a threshold, so the markers simply vanish.
 *
 * Anything compared against a server time therefore reads the clock here. A user's own
 * schedule — notification windows, which they set on their own phone — stays on the
 * device clock, because that is the clock they set it by.
 */
val SERVICE_ZONE: ZoneId = ZoneId.of("Europe/Dublin")

/**
 * Where every screen gets "now".
 *
 * The system clock in the app. Screenshot tests pin it to the instant their recorded API answers
 * are true for, so a board rendered on a PC says "4 mins" for the same bus on every run instead of
 * counting down to whenever the test happens to execute. Background work (notification workers,
 * schedulers) still reads the system clock directly: nothing renders it.
 */
object AppClock {
    @Volatile var clock: Clock = Clock.systemDefaultZone()

    fun millis(): Long = clock.millis()
    fun inZone(zone: ZoneId): Clock = clock.withZone(zone)
}

/** Wall-clock time in the service zone. */
fun serviceNow(): LocalTime = LocalTime.now(AppClock.inZone(SERVICE_ZONE))

/** The service date, as GTFS spells it (`yyyyMMdd`). */
fun serviceToday(): LocalDate = LocalDate.now(AppClock.inZone(SERVICE_ZONE))

/** Minutes since midnight on the service clock, right now. */
fun serviceNowMinutes(): Int = serviceNow().let { it.hour * 60 + it.minute }

/** Minutes since midnight on the service clock at an instant (used for time-travel pins). */
fun serviceMinutesAt(epochSec: Long): Int =
    Instant.ofEpochSecond(epochSec).atZone(SERVICE_ZONE).let { it.hour * 60 + it.minute }

/** The instant, on the service clock, for a time-travel pin. */
fun serviceTimeAt(epochSec: Long): ZonedDateTime =
    Instant.ofEpochSecond(epochSec).atZone(SERVICE_ZONE)

/**
 * Signed minutes from [nowMins] to [targetMins], both minutes since midnight.
 *
 * Both wrap at midnight, so a plain subtraction is only meaningful modulo a day: at 23:55
 * a bus due at 00:05 subtracts to −1430, not 10. Normalising into (−720, 720] reads any
 * difference as "the nearest way round the clock", which is the right answer for a bus
 * board where nothing is ever more than a couple of hours out. It also absorbs GTFS's
 * after-midnight encoding, where a departure time can be 24:00 or later.
 */
fun minutesUntil(targetMins: Int, nowMins: Int): Int {
    var diff = (targetMins - nowMins) % 1440
    if (diff <= -720) diff += 1440
    else if (diff > 720) diff -= 1440
    return diff
}
