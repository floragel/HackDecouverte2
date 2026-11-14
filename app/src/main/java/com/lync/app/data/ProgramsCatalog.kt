package com.lync.app.data

import android.content.Context
import com.lync.app.R
import com.lync.app.data.model.Program
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * All Concordia undergraduate programs (165), scraped from concordia.ca/academics/undergraduate.html
 * into res/raw/concordia_programs.json. Later: one file per university, or a `programs` table.
 */
object ProgramsCatalog {
    @Serializable private data class Row(val slug: String, val name: String, val degree: String)

    @Volatile private var cache: List<Program>? = null

    fun all(context: Context): List<Program> = cache ?: synchronized(this) {
        cache ?: context.resources.openRawResource(R.raw.concordia_programs).bufferedReader().use { it.readText() }
            .let { Json.decodeFromString<List<Row>>(it) }
            .map { Program(it.slug, it.name, it.degree) }
            .also { cache = it }
    }

    fun search(context: Context, query: String): List<Program> {
        val q = query.trim().lowercase()
        val list = all(context)
        if (q.isEmpty()) return list
        return list.filter { q in it.name.lowercase() || q in it.degree.lowercase() }
            .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
    }
}
