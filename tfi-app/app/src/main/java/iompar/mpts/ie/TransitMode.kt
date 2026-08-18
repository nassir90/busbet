package iompar.mpts.ie

/**
 * The kind of service a stop or departure belongs to, derived from a GTFS route_type. The NTA
 * feed carries bus, Luas (tram) and Irish Rail alongside each other, and the backend now reports
 * route_type on stops, departures, routes and trips — this maps it to the handful of modes the UI
 * actually distinguishes (a logo, a label). Anything unrecognised falls back to [BUS], which is by
 * far the common case and the safest default for a service that started life bus-only.
 *
 * The mode logos themselves (card view, stop view, search pane) are TFI-118.
 */
enum class TransitMode(val label: String) {
    BUS("Bus"),
    TRAM("Luas"),
    TRAIN("Train");

    companion object {
        /**
         * GTFS route_type → mode. Handles the three standard codes the NTA feed uses (0 tram,
         * 2 rail, 3 bus) plus the Hint/extended ranges (rail 100–117, coach/bus 200 & 700–716,
         * tram 900–906) so a future timetable that switches to extended types still resolves. Null
         * — an older backend that doesn't send route_type — is treated as bus.
         */
        fun of(routeType: Int?): TransitMode = when (routeType) {
            null -> BUS
            0, in 900..906 -> TRAM
            2, in 100..117 -> TRAIN
            else -> BUS   // 3, 200, 700..716, and anything unknown
        }

        /**
         * The single mode to show for a stop that lists several route_types. Stops are effectively
         * single-mode in the NTA data; when one somehow mixes modes, rail and tram are the
         * distinctive ones worth surfacing over the bus default.
         */
        fun ofStop(routeTypes: List<Int>?): TransitMode {
            if (routeTypes.isNullOrEmpty()) return BUS
            val modes = routeTypes.map { of(it) }
            return when {
                TRAIN in modes -> TRAIN
                TRAM in modes -> TRAM
                else -> BUS
            }
        }
    }
}
