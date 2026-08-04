package iompar.mpts.ie

/**
 * Last-seen departures per stop, so reopening a board (or going back to one) paints immediately
 * instead of flashing "Loading…" while the first poll lands.
 *
 * A plain synchronised LRU, deliberately not a `mutableStateMapOf`. As snapshot state it had two
 * problems: reading it inside a `remember` calculation subscribed that composable to the *whole*
 * map, so a poll on one screen recomposed every card on another that had nothing to do with it;
 * and it grew without bound for every stop visited in a session. Nothing observes this — callers
 * read it once to seed their own state — so snapshot semantics bought nothing.
 */
object DeparturesCache {
    /** Enough to cover a favourites list plus a browsing session, without pinning the lot. */
    private const val MAX_ENTRIES = 64

    private val map = object : LinkedHashMap<String, DeparturesResponse>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeparturesResponse>) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(code: String): DeparturesResponse? = map[code]

    @Synchronized
    fun put(code: String, data: DeparturesResponse) {
        map[code] = data
    }
}
