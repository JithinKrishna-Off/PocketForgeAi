package com.pocketforge.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.hub.HfCatalogService
import com.pocketforge.ai.data.hub.HfGgufFile
import com.pocketforge.ai.data.hub.HfModelDetail
import com.pocketforge.ai.data.hub.HfModelSummary
import com.pocketforge.ai.data.hub.HfResult
import com.pocketforge.ai.data.model.InstalledModel
import com.pocketforge.ai.data.steering.SteeringAssessment
import com.pocketforge.ai.data.steering.SteeringCompatibility
import com.pocketforge.ai.data.steering.SteeringTechStatus
import com.pocketforge.ai.download.DownloadInfo
import com.pocketforge.ai.download.DownloadRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One result row. Search fills id/author/counters; expanding the card merges HfModelDetail
 * (license, architecture, file list) and evaluates steering compatibility pre-download.
 */
data class HubModelUi(
    val id: String,
    val author: String,
    val displayName: String,
    val likes: Long,
    val downloads: Long,
    val lastModified: String,
    val sha: String? = null,
    val tags: List<String> = emptyList(),
    val license: String? = null,
    val architecture: String? = null,
    val contextLength: Long? = null,
    val totalGgufBytes: Long? = null,
    val steerStatus: SteeringAssessment? = null,
    val files: List<HfGgufFile> = emptyList(),
    val detailLoaded: Boolean = false,
) {
    val bestFile: HfGgufFile? get() = files.firstOrNull()
    val fileCount: Int get() = files.size
}

data class HubUiState(
    val query: String = "",
    val results: List<HubModelUi> = emptyList(),
    val expanded: Map<String, HfModelDetail> = emptyMap(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val message: String? = null,
    val cardErrors: Map<String, String> = emptyMap(),
    // key = "repoId|filePath", mirrors DownloadRegistry for ids started from this screen
    val downloadByTarget: Map<String, DownloadInfo> = emptyMap(),
    val installedByRepo: Map<String, List<InstalledModel>> = emptyMap(),
)

class HubViewModel(private val app: PocketForgeApp) : ViewModel() {

    private val _state = MutableStateFlow(HubUiState())
    val state: StateFlow<HubUiState> = _state.asStateFlow()

    // modelId -> (repoId, filePath); keys the registry state for the UI after a start
    private val startedTargets = ConcurrentHashMap<String, HubTarget>()
    private var searchJob: Job? = null
    private var loadLimit = INITIAL_LIMIT

    private data class HubTarget(val repoId: String, val filePath: String)

    init {
        viewModelScope.launch {
            DownloadRegistry.observe().collect { refreshHubDownloads() }
        }
    }

    private fun refreshHubDownloads() {
        val downloads = DownloadRegistry.observe().value
        val byTarget = LinkedHashMap<String, DownloadInfo>(startedTargets.size)
        downloads.forEach { (id, info) ->
            startedTargets[id]?.let { t -> byTarget["${t.repoId}|${t.filePath}"] = info }
        }
        val installed = app.modelRegistry.all().filter { it.repo.isNotEmpty() }.groupBy { it.repo }
        _state.update { it.copy(downloadByTarget = byTarget, installedByRepo = installed) }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            loadLimit = INITIAL_LIMIT
            _state.update {
                it.copy(results = emptyList(), canLoadMore = false, loading = false, loadingMore = false)
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            doSearch(trimmed, INITIAL_LIMIT, resetResults = true)
        }
    }

    fun search() {
        val trimmed = _state.value.query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { doSearch(trimmed, INITIAL_LIMIT, resetResults = true) }
    }

    fun loadMore() {
        if (!_state.value.canLoadMore || _state.value.loading || _state.value.loadingMore) return
        val trimmed = _state.value.query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            doSearch(trimmed, (loadLimit * 2).coerceAtMost(MAX_LIMIT), resetResults = false)
        }
    }

    private suspend fun doSearch(query: String, limit: Int, resetResults: Boolean) {
        _state.update { it.copy(loading = resetResults, loadingMore = !resetResults, message = null) }
        withContext(Dispatchers.IO) {
            when (val result = HfCatalogService.search(query, limit)) {
                is HfResult.Failure -> _state.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        canLoadMore = false,
                        message = "Search failed: ${result.message}",
                        results = if (resetResults) emptyList() else it.results,
                    )
                }
                is HfResult.Success -> {
                    searchJob = null
                    // offset is not honored by the API: grow the limit and de-duplicate by id
                    _state.update { st ->
                        val fresh = result.value.map(::searchToUi)
                        if (resetResults) {
                            st.copy(
                                loading = false,
                                results = fresh,
                                canLoadMore = result.value.size >= limit,
                            )
                        } else {
                            val seen = st.results.map { it.id }.toHashSet()
                            st.copy(
                                loadingMore = false,
                                results = st.results + fresh.filter { it.id !in seen },
                                canLoadMore = result.value.size >= limit && limit < MAX_LIMIT,
                            )
                        }
                    }
                    loadLimit = limit
                    // keep already loaded details across the new result list
                    _state.value.expanded.forEach { (id, detail) ->
                        updateDetail(id, detail)
                    }
                }
            }
        }
    }

    fun openDetail(repoId: String) {
        if (_state.value.expanded.containsKey(repoId)) return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = HfCatalogService.detail(repoId)) {
                is HfResult.Failure -> _state.update {
                    it.copy(message = "Could not load details: ${result.message}")
                }
                is HfResult.Success -> updateDetail(repoId, result.value)
            }
        }
    }

    private fun searchToUi(s: HfModelSummary) = HubModelUi(
        id = s.id,
        author = s.author,
        displayName = s.id.substringAfter('/'),
        likes = s.likes,
        downloads = s.downloads,
        lastModified = s.lastModified,
        sha = s.sha,
        tags = s.tags,
    )

    private fun updateDetail(repoId: String, detail: HfModelDetail) {
        _state.update { st ->
            val results = st.results.let { list ->
                val idx = list.indexOfFirst { it.id == repoId }
                val updated = withDetail(HubModelUi(repoId, repoId.substringBefore('/'), repoId.substringAfter('/'), detail.likes, detail.downloads, detail.lastModified, detail.sha, detail.tags), detail)
                if (idx >= 0) list.toMutableList().also { it[idx] = updated } else list
            }
            st.copy(expanded = st.expanded + (repoId to detail), results = results)
        }
    }

    private fun withDetail(base: HubModelUi, detail: HfModelDetail): HubModelUi = base.copy(
        likes = detail.likes,
        downloads = detail.downloads,
        lastModified = detail.lastModified,
        sha = detail.sha ?: base.sha,
        license = detail.license,
        architecture = detail.architecture,
        contextLength = detail.contextLength,
        totalGgufBytes = detail.totalGgufBytes,
        steerStatus = steerFor(detail),
        files = detail.files,
        detailLoaded = true,
    )

    // No name guessing: without gguf.architecture the result stays UNKNOWN.
    private fun steerFor(detail: HfModelDetail): SteeringAssessment {
        val arch = detail.architecture
        if (arch.isNullOrEmpty()) {
            return SteeringAssessment(SteeringTechStatus.UNKNOWN, listOf("architecture not reported by registry"))
        }
        return SteeringCompatibility.evaluate(arch, null, null, 14, 8949)
    }

    fun startDownload(repoId: String, file: HfGgufFile? = null) {
        val detail = _state.value.expanded[repoId]
        if (detail == null) {
            openDetail(repoId)
            _state.update {
                it.copy(message = "Fetching details for $repoId; choose a file once the list appears.")
            }
            return
        }
        internalStart(repoId, detail, file ?: detail.files.firstOrNull())
    }

    private fun internalStart(repoId: String, detail: HfModelDetail, chosen: HfGgufFile?) {
        if (chosen == null) {
            _state.update {
                it.copy(message = "This repository has no non-sharded .gguf file to download.")
            }
            return
        }
        _state.update { it.copy(cardErrors = it.cardErrors - repoId) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = app.downloadManager.startHubDownload(
                context = app,
                repoId = repoId,
                revision = detail.sha ?: "main",
                fileName = chosen.path,
                totalBytes = chosen.sizeBytes,
                sha256 = chosen.sha256,
                displayName = _state.value.results.firstOrNull { it.id == repoId }?.displayName
                    ?: repoId.substringAfter('/'),
                author = repoId.substringBefore('/'),
            )
            result.fold(
                onSuccess = { id ->
                    startedTargets[id] = HubTarget(repoId, chosen.path)
                    refreshHubDownloads()
                },
                onFailure = { t ->
                    val msg = t.message ?: "Download failed to start"
                    _state.update {
                        it.copy(message = msg, cardErrors = it.cardErrors + (repoId to msg))
                    }
                    refreshHubDownloads()
                },
            )
        }
    }

    fun cancelDownload(modelId: String) {
        app.downloadManager.cancel(modelId)
        refreshHubDownloads()
    }

    fun retryDownload(modelId: String) {
        val target = startedTargets[modelId]
        if (target == null) {
            _state.update {
                it.copy(message = "Use the Download button on the card to start this file again.")
            }
            return
        }
        val detail = _state.value.expanded[target.repoId]
        if (detail == null) {
            openDetail(target.repoId)
            _state.update { it.copy(message = "Re-fetching details before retrying.") }
            return
        }
        internalStart(target.repoId, detail, detail.files.firstOrNull { it.path == target.filePath })
    }

    fun clearFailed(modelId: String) {
        app.downloadManager.clearFailed(modelId)
        refreshHubDownloads()
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val INITIAL_LIMIT = 25
        const val MAX_LIMIT = 100
    }
}
