package com.unshoo.pixelmusic.presentation.viewmodel

import android.util.LruCache

import com.unshoo.pixelmusic.data.model.SearchFilterType
import com.unshoo.pixelmusic.data.model.SearchHistoryItem
import com.unshoo.pixelmusic.data.model.SearchResultItem
import com.unshoo.pixelmusic.data.repository.MusicRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import com.unshoo.pixelmusic.data.remote.youtube.toNativeSong
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val youtubeSongRepository: com.unshoo.pixelmusic.data.remote.youtube.SongRepository,
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L // Reduced from 300ms for faster response
        const val SEARCH_CACHE_SIZE = 20
    }

    // In-memory LRU cache for recent search results — instant display for repeated queries
    private val searchResultCache = LruCache<String, ImmutableList<SearchResultItem>>(SEARCH_CACHE_SIZE)

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
    )

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    // Show cached results immediately while fresh results load
                    val cachedResults = searchResultCache.get(normalizedQuery)
                    if (cachedResults != null) {
                        _searchResults.value = cachedResults
                    }

                    try {
                        val currentFilter = _selectedSearchFilter.value
                        val localSearchFlow = musicRepository.searchAll(normalizedQuery, currentFilter)
                        
                        val youtubeSearchFlow = flow {
                            val items = mutableListOf<SearchResultItem>()

                            // 1. Fetch songs if applicable
                            if (currentFilter == SearchFilterType.ALL || currentFilter == SearchFilterType.SONGS) {
                                try {
                                    val songResult = youtubeSongRepository.search(normalizedQuery).first { 
                                        it is com.unshoo.pixelmusic.data.remote.youtube.ApiResult.Success || it is com.unshoo.pixelmusic.data.remote.youtube.ApiResult.Error
                                    }
                                    if (songResult is com.unshoo.pixelmusic.data.remote.youtube.ApiResult.Success) {
                                        items.addAll(songResult.data.map { SearchResultItem.SongItem(it.toNativeSong()) })
                                    }
                                } catch (e: Exception) {
                                    Timber.e(e, "Error fetching YouTube search songs")
                                }
                            }

                            // 2. Fetch artists if applicable
                            if (currentFilter == SearchFilterType.ALL || currentFilter == SearchFilterType.ARTISTS) {
                                try {
                                    val searchResult = withContext(Dispatchers.IO) {
                                        unshoo.ianshulyadav.pixelmusic.innertube.YouTube.search(
                                            normalizedQuery,
                                            unshoo.ianshulyadav.pixelmusic.innertube.YouTube.SearchFilter.FILTER_ARTIST
                                        ).getOrNull()
                                    }
                                    val apiArtists = searchResult?.items?.filterIsInstance<unshoo.ianshulyadav.pixelmusic.innertube.models.ArtistItem>() ?: emptyList()

                                    items.addAll(apiArtists.map { apiArtist ->
                                        SearchResultItem.ArtistItem(
                                            com.unshoo.pixelmusic.data.model.Artist(
                                                id = toUnifiedYoutubeArtistId(apiArtist.title),
                                                name = apiArtist.title,
                                                songCount = 0,
                                                imageUrl = apiArtist.thumbnail,
                                                channelId = apiArtist.id
                                            )
                                        )
                                    })
                                } catch (e: Exception) {
                                    Timber.e(e, "Error fetching YouTube search artists")
                                }
                            }

                            emit(items)
                        }.flowOn(Dispatchers.IO)

                        combine(localSearchFlow, youtubeSearchFlow) { localResults, youtubeResults ->
                            val localSongsMap = localResults.filterIsInstance<SearchResultItem.SongItem>()
                                .map { it.song }
                                .filter { !it.youtubeId.isNullOrBlank() }
                                .associateBy { it.youtubeId!! }

                            val youtubeIdsSet = youtubeResults.filterIsInstance<SearchResultItem.SongItem>()
                                .mapNotNull { it.song.youtubeId }
                                .toSet()

                            val updatedYoutubeResults = youtubeResults.map { ytResult ->
                                if (ytResult is SearchResultItem.SongItem) {
                                    val ytId = ytResult.song.youtubeId
                                    val matchingLocalSong = localSongsMap[ytId]
                                    if (matchingLocalSong != null) {
                                        SearchResultItem.SongItem(
                                            ytResult.song.copy(
                                                id = matchingLocalSong.id,
                                                path = matchingLocalSong.path,
                                                isFavorite = matchingLocalSong.isFavorite
                                            )
                                        )
                                    } else {
                                        ytResult
                                    }
                                } else {
                                    ytResult
                                }
                            }

                            val filteredLocalResults = localResults.filter { localResult ->
                                if (localResult is SearchResultItem.SongItem) {
                                    val ytId = localResult.song.youtubeId
                                    ytId.isNullOrBlank() || !youtubeIdsSet.contains(ytId)
                                } else {
                                    true
                                }
                            }

                            val combined = updatedYoutubeResults + filteredLocalResults
                            combined.sortedWith(
                                compareBy { result ->
                                    when (result) {
                                        is SearchResultItem.SongItem -> 0
                                        is SearchResultItem.AlbumItem -> 1
                                        is SearchResultItem.ArtistItem -> 2
                                        is SearchResultItem.PlaylistItem -> 3
                                    }
                                }
                            )
                        }.collect { resultsList ->
                            if (request.requestId != latestSearchRequestId.get()) {
                                return@collect
                            }

                            val immutableResults = resultsList.toImmutableList()
                            if (_searchResults.value != immutableResults) {
                                _searchResults.value = immutableResults
                                // Cache for future instant display
                                searchResultCache.put(normalizedQuery, immutableResults)
                            }
                        }
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            _searchResults.value = persistentListOf()
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Timber.e(e, "Error loading search history")
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Timber.e(e, "Error adding search history item")
                }
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
        }

        searchRequests.tryEmit(SearchRequest(normalizedQuery, requestId))
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Timber.e(e, "Error deleting search history item")
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing search history")
            }
        }
    }

    private fun toUnifiedYoutubeArtistId(artistName: String): Long {
        return -(17_000_000_000_000L + kotlin.math.abs(artistName.lowercase().hashCode().toLong()))
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
