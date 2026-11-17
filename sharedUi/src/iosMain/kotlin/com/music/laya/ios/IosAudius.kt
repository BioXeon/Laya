package com.music.laya.ios

import com.music.laya.data.model.Song
import com.music.laya.platform.ktorClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Audius (audius.co): an open catalogue of independent artists. Its API has no "similar to this track",
 * so a station is built from the seed's genre: the artist's own tracks first, then what's trending in
 * that genre. Tracks are queued as `audius:<id>` songs and streamed from Audius itself by [streamUrl].
 */
internal object IosAudius {
    const val PREFIX = "audius:"
    private const val API = "https://api.audius.co/v1"
    private const val APP = "app_name=laya"
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { ktorClient {} }

    fun isAudius(song: Song) = song.videoId.startsWith(PREFIX)

    private suspend fun get(path: String): JsonElement? = runCatching {
        val sep = if ('?' in path) '&' else '?'
        json.parseToJsonElement(client.get("$API/$path$sep$APP").bodyAsText()).jsonObject["data"]
    }.getOrNull()

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun toSong(o: JsonObject): Song? {
        if (o["is_streamable"]?.jsonPrimitive?.contentOrNull == "false") return null
        val id = o.str("id") ?: return null
        val seconds = o["duration"]?.jsonPrimitive?.intOrNull
        return Song(
            videoId = PREFIX + id,
            title = o.str("title") ?: return null,
            artist = o["user"]?.jsonObject?.str("name") ?: "Audius",
            thumbnailUrl = o["artwork"]?.jsonObject?.let { it.str("480x480") ?: it.str("150x150") },
            durationText = seconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
        )
    }

    private fun songs(data: JsonElement?): List<Pair<Song, String?>> =
        (data as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            toSong(o)?.let { it to o.str("genre") }
        }

    /** A signed stream address for [song], or null when Audius can't serve it. */
    suspend fun streamUrl(song: Song): String? {
        val id = song.videoId.removePrefix(PREFIX)
        return (get("tracks/$id/stream?no_redirect=true") as? JsonPrimitive)?.contentOrNull
    }

    /** Up to [limit] Audius tracks to play after [seed], never the seed itself or a repeat. */
    suspend fun radio(seed: Song, limit: Int): List<Song> {
        val picks = ArrayList<Pair<Song, String?>>()
        var genre: String? = null
        if (isAudius(seed)) {
            genre = (get("tracks/${seed.videoId.removePrefix(PREFIX)}") as? JsonObject)?.str("genre")
        } else {
            // The seed lives on YouTube: find its artist (or the song) on Audius and take that genre.
            val q = "${seed.artist} ${seed.title}".trim().encodeURLParameter()
            val hits = songs(get("tracks/search?query=$q&limit=10"))
            val own = hits.filter { it.first.artist.equals(seed.artist, ignoreCase = true) }
            picks += own
            genre = (own.firstOrNull() ?: hits.firstOrNull())?.second
        }
        val trending = songs(get("tracks/trending?${genre?.let { "genre=${it.encodeURLParameter()}&" } ?: ""}time=month&limit=60"))
        picks += trending.shuffled()
        return picks.map { it.first }
            .filter { it.videoId != seed.videoId }
            .distinctBy { it.videoId }
            .take(limit)
    }
}
