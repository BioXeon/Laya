package com.music.laya.ios

import com.music.laya.data.YtMusicRepository
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import com.music.laya.data.model.Song
import com.music.laya.data.sources.TrackMatcher
import com.music.laya.platform.ktorClient
import com.music.laya.playback.youtubeSeedFor
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One station from several recommenders, all of which play through YouTube Music:
 *  - YouTube Music's own radio for the track (the strongest signal, and the only one that knows the song);
 *  - Deezer's radio for the track's artist;
 *  - ListenBrainz's listening-based "similar artists", each represented by what YouTube Music lists for them.
 * Candidates are matched across sources by artist and title and ranked by reciprocal-rank fusion, so a track
 * several recommenders agree on rises above one only a single source offered. A source that fails or is slow
 * is simply left out. Spotify no longer offers recommendations to new apps, so it has no part here.
 */
internal object IosMixedRadio {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { ktorClient {} }

    /** Added to a rank before it is inverted; the standard RRF constant. */
    private const val RRF_K = 60.0
    /** One source's share of the vote, so YouTube Music (the best) counts a little more than the others. */
    private const val YT_WEIGHT = 1.5
    private const val SOURCE_TIMEOUT_MS = 12_000L
    private const val DEEZER_RESOLVE = 12
    private const val SIMILAR_ARTISTS = 6

    private class Cand(val song: Song?, val artist: String, val title: String, var score: Double = 0.0)

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun key(artist: String, title: String) = norm(artist) + "|" + norm(title)

    private suspend fun getJson(url: String): JsonElement? = runCatching {
        json.parseToJsonElement(client.get(url) { header("User-Agent", "Laya/1.0 (iOS)") }.bodyAsText())
    }.getOrNull()

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    /** YouTube Music's radio, ranked as it came. */
    private suspend fun youtube(seed: Song): List<Song> =
        YtMusicRepository.radio(youtubeSeedFor(seed) ?: return emptyList()).getOrNull().orEmpty()

    /** The first song YouTube Music lists for "[artist] [title]". */
    private suspend fun resolve(artist: String, title: String?): Song? {
        val q = listOfNotNull(artist, title).joinToString(" ")
        val songs = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
        return if (title != null) TrackMatcher.best(songs, TrackMatcher.targetOf(Song("", title, artist, null)))
        else songs.firstOrNull { it.artist.equals(artist, ignoreCase = true) } ?: songs.firstOrNull()
    }

    /** Deezer's radio for the seed's artist as (artist, title) pairs; the seed is looked up by name, no key needed. */
    private suspend fun deezer(seed: Song): List<Pair<String, String>> {
        val q = "artist:\"${seed.artist}\" track:\"${seed.title}\"".encodeURLParameter()
        val hit = (getJson("https://api.deezer.com/search?q=$q&limit=1")?.jsonObject?.get("data") as? JsonArray)
            ?.firstOrNull()?.jsonObject ?: return emptyList()
        val artistId = hit["artist"]?.jsonObject?.str("id") ?: return emptyList()
        return (getJson("https://api.deezer.com/artist/$artistId/radio?limit=40")?.jsonObject?.get("data") as? JsonArray)
            .orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                (o["artist"]?.jsonObject?.str("name") ?: return@mapNotNull null) to (o.str("title") ?: return@mapNotNull null)
            }
    }

    /** Artists ListenBrainz's listeners play alongside the seed's artist, by MusicBrainz id. */
    private suspend fun listenBrainz(seed: Song): List<String> {
        val q = "artist:\"${seed.artist}\"".encodeURLParameter()
        val mbid = ((getJson("https://musicbrainz.org/ws/2/artist?query=$q&fmt=json&limit=1")
            ?.jsonObject?.get("artists") as? JsonArray)?.firstOrNull()?.jsonObject)?.str("id") ?: return emptyList()
        val algo = "session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30"
        return (getJson("https://labs.api.listenbrainz.org/similar-artists/json?artist_mbids=$mbid&algorithm=$algo") as? JsonArray)
            .orEmpty().mapNotNull { it.jsonObject.str("name") }.take(SIMILAR_ARTISTS)
    }

    suspend fun radio(seed: Song, limit: Int): List<Song> = coroutineScope {
        val yt = async { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { youtube(seed) }.orEmpty() }
        val dz = async { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { deezer(seed) }.orEmpty() }
        val lb = async { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { listenBrainz(seed) }.orEmpty() }

        val cands = LinkedHashMap<String, Cand>()
        fun vote(rank: Int, weight: Double, artist: String, title: String, song: Song?) {
            val c = cands.getOrPut(key(artist, title)) { Cand(song, artist, title) }
            c.score += weight / (RRF_K + rank)
        }
        yt.await().forEachIndexed { i, s -> vote(i, YT_WEIGHT, s.artist, s.title, s) }

        // Deezer's pairs: a match against what YouTube already listed needs no lookup; the best few others are looked up.
        val deezerPairs = dz.await()
        val fresh = deezerPairs.withIndex().filter { key(it.value.first, it.value.second) !in cands }.take(DEEZER_RESOLVE)
        val looked = fresh.map { (i, p) -> async { i to resolve(p.first, p.second) } }.awaitAll().toMap()
        deezerPairs.forEachIndexed { i, (artist, title) ->
            val k = key(artist, title)
            if (k in cands) vote(i, 1.0, artist, title, null)
            else looked[i]?.let { vote(i, 1.0, it.artist, it.title, it) }
        }

        // ListenBrainz's artists: one song each, the way YouTube Music ranks them for the artist.
        lb.await().map { name -> async { name to resolve(name, null) } }.awaitAll().forEachIndexed { i, (_, song) ->
            if (song != null) vote(i * 3, 1.0, song.artist, song.title, song)
        }

        val seedKey = key(seed.artist, seed.title)
        cands.entries.filter { it.key != seedKey && it.value.song != null }
            .sortedByDescending { it.value.score }
            .map { it.value.song!! }
            .distinctBy { it.videoId }
            .take(limit)
    }
}
