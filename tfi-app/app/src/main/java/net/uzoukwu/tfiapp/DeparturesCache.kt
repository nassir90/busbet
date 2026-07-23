package net.uzoukwu.tfiapp

import androidx.compose.runtime.mutableStateMapOf

object DeparturesCache {
    private val map = mutableStateMapOf<String, DeparturesResponse>()

    fun get(code: String): DeparturesResponse? = map[code]

    fun put(code: String, data: DeparturesResponse) {
        map[code] = data
    }
}
