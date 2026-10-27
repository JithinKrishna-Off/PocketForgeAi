package com.pocketforge.ai.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.data.catalogue.CatalogueModel
import com.pocketforge.ai.data.gguf.GgufParser
import com.pocketforge.ai.data.gguf.MemoryEstimator
import com.pocketforge.ai.device.HardwareCapabilityDetector
import com.pocketforge.ai.download.DownloadInfo
import com.pocketforge.ai.download.DownloadPhase
import com.pocketforge.ai.data.model.InstalledModel
import com.pocketforge.ai.inference.EngineState
import com.pocketforge.ai.inference.ModelLoadService
import com.pocketforge.ai.inference.SessionStats
import com.pocketforge.ai.settings.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelCardState(
    val model: CatalogueModel,
    val installed: InstalledModel? = null,
    val download: DownloadInfo? = null,
    val ggufArchitecture: String? = null,
    val ggufQuantisation: String? = null,
    val ggufContextLength: Long? = null,
    val estimate2048: MemoryEstimator.Breakdown? = null,
    val estimate4096: MemoryEstimator.Breakdown? = null,
    val availableRamBytes: Long = 0,
)

data class LoadAttempt(
    val modelId: String,
    val recommendedContext: Int,
    val estimate: MemoryEstimator.Breakdown?,
    val availableRamBytes: Long,
    val context4096Allowed: Boolean,
    val estimate4096: MemoryEstimator.Breakdown?,
    val warning: String?,
)

data class ModelsUiState(
    val cards: List<ModelCardState> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val loadAttempt: LoadAttempt? = null,
    val engineStateLabel: String = "",
    val loadedModelId: String? = null,
    val currentContextLength: Int? = null,
)

class ModelsViewModel(private val app: PocketForgeApp) : ViewModel() {

    private val engine = app.engine
    private val settingsStore = app.settingsStore

    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    private var lastSettings: Settings = Settings()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                lastSettings = s
            }
        }
        viewModelScope.launch {
            engine.state.collect { st ->
                _state.update {
                    it.copy(
                        engineStateLabel = engineLabel(st),
                        loadedModelId = if (st is EngineState.ModelReady) it.loadedModelId else null,
                    )
                }
                if (st is EngineState.Error) {
                    message(st.error.message ?: "Engine error")
                }
            }
        }
        // live registry + download states
        refresh()
        viewModelScope.launch {
            com.pocketforge.ai.download.DownloadRegistry.observe().collect {
                refresh()
            }
        }
    }

    private fun engineLabel(st: EngineState) = when (st) {
        is EngineState.Uninitialized -> "starting"
        is EngineState.Initializing, is EngineState.Initialized -> "idle (no model)"
        is EngineState.LoadingModel -> "loading model"
        is EngineState.ModelReady -> "model ready"
        is EngineState.ProcessingUserPrompt, is EngineState.Generating -> "generating"
        is EngineState.Error -> "engine error"
    }

    private fun message(msg: String?) {
        _state.update { it.copy(message = msg) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun refresh() {
        viewModelScope.launch {
            val cards = withContext(Dispatchers.IO) {
                val registry = app.modelRegistry.all()
                val downloads = com.pocketforge.ai.download.DownloadRegistry.observe().value
                val device = HardwareCapabilityDetector.detect(app)
                app.catalog.models.map { m ->
                    val installed = registry.firstOrNull { it.id == m.id }
                    val dl = downloads[m.id]
                    var card = ModelCardState(
                        model = m,
                        installed = installed,
                        download = dl,
                        availableRamBytes = device.availableRamBytes - device.ramThresholdBytes,
                    )
                    if (installed != null) {
                        card = parseInstalled(card, installed)
                    }
                    card
                }
            }
            _state.update { it.copy(cards = cards) }
        }
    }

    private fun parseInstalled(
        card: ModelCardState,
        installed: InstalledModel,
    ): ModelCardState {
        val f = File(installed.path)
        if (!f.isFile) return card.copy(installed = installed.copy(sizeBytes = -1))
        return try {
            val meta = GgufParser.parse(f)
            val est2048 = MemoryEstimator.breakdown(
                meta, meta.fileSize, 2048, lastSettings.safetyMarginMb * 1024L,
            )
            val est4096 = MemoryEstimator.breakdown(
                meta, meta.fileSize, 4096, lastSettings.safetyMarginMb * 1024L,
            )
            card.copy(
                ggufArchitecture = meta.architecture,
                ggufQuantisation = meta.quantName(),
                ggufContextLength = meta.contextLengthOr(0),
                estimate2048 = est2048,
                estimate4096 = est4096,
            )
        } catch (e: Exception) {
            card.copy(installed = installed)
        }
    }

    fun startDownload(modelId: String) {
        val model = app.catalog.models.firstOrNull { it.id == modelId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val err = app.downloadManager.startOrResume(model)
            message(err)
            if (err == null) refresh()
        }
    }

    fun cancelDownload(modelId: String) {
        app.downloadManager.cancel(modelId)
        refresh()
    }

    fun clearFailed(modelId: String) {
        app.downloadManager.clearFailed(modelId)
        refresh()
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // unload if loaded
            val card = _state.value.cards.firstOrNull { it.model.id == modelId }
            val installed = card?.installed
            if (installed != null) engine.unload()
            val err = app.downloadManager.deleteInstalled(modelId)
            message(err ?: "Model deleted")
            refresh()
        }
    }

    /**
     * Prepares the load dialog: parses the GGUF, computes memory estimates and decides
     * whether 4096-token context is acceptable for the current available RAM.
     */
    fun prepareLoad(modelId: String) {
        val card = _state.value.cards.firstOrNull { it.model.id == modelId } ?: return
        val installed = card.installed ?: return
        val f = File(installed.path)
        val meta = try {
            GgufParser.parse(f)
        } catch (e: Exception) {
            message("Model file looks corrupted: ${e.message}")
            return
        }
        val est2048 = MemoryEstimator.breakdown(meta, meta.fileSize, 2048, lastSettings.safetyMarginMb * 1024L)
            ?: run {
                message("Cannot estimate memory for this model (missing required metadata)")
                return
            }
        val est4096 = MemoryEstimator.breakdown(meta, meta.fileSize, 4096, lastSettings.safetyMarginMb * 1024L)
        val device = HardwareCapabilityDetector.detect(app)
        val available = device.availableRamBytes - device.ramThresholdBytes
        val fits2048 = est2048.fitsIn(available)
        if (!fits2048) {
            message(
                "This model does not fit safely right now.\n" + est2048.explain(available) +
                    "\nFree memory and try again, or free space by closing apps.",
            )
        }
        _state.update {
            it.copy(
                loadAttempt = LoadAttempt(
                    modelId = modelId,
                    recommendedContext = 2048,
                    estimate = est2048,
                    availableRamBytes = available,
                    context4096Allowed = (est4096?.fitsIn(available) == true) && fits2048,
                    estimate4096 = est4096,
                    warning = if (fits2048) null else "Refusing to load: memory estimate exceeds safe headroom.",
                ),
            )
        }
        message(if (fits2048) null else "Load blocked by the memory safety check")
    }

    fun cancelLoadDialog() {
        _state.update { it.copy(loadAttempt = null) }
    }

    fun confirmLoad(contextLength: Int, gpuLayers: Int) {
        val attempt = _state.value.loadAttempt ?: return
        val modelId = attempt.modelId
        _state.update { it.copy(busy = true, loadAttempt = null) }
        viewModelScope.launch {
            when (val r = ModelLoadService(app).loadInstalled(
                modelId = modelId,
                contextLength = contextLength,
                gpuLayers = if (gpuLayers > 0) gpuLayers else null,
            )) {
                is ModelLoadService.Result.Loaded -> {
                    _state.update {
                        it.copy(busy = false, loadedModelId = modelId, currentContextLength = contextLength)
                    }
                    message("Model loaded (ctx $contextLength) - ${r.summary}")
                }
                is ModelLoadService.Result.Failure -> {
                    _state.update { it.copy(busy = false, loadedModelId = null) }
                    message(r.message)
                }
            }
        }
    }

    fun unloadModel() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                engine.unload()
                _state.update { it.copy(busy = false, loadedModelId = null, currentContextLength = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(busy = false) }
                message("Unload failed: ${t.message}")
            }
        }
    }

    fun displayNameFor(uri: Uri, context: Context): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0) ?: uri.lastPathSegment ?: "imported.gguf"
                    } else {
                        uri.lastPathSegment ?: "imported.gguf"
                    }
                } ?: uri.lastPathSegment ?: "imported.gguf"
        } catch (_: Exception) {
            "imported.gguf"
        }
    }

    fun onImportResult(uri: Uri) {
        val context = app
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val name = displayNameFor(uri, context)
            val result = app.downloadManager.importGguf(
                displayName = name,
                openStream = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Cannot open the selected file (permission expired?)")
                },
                onProgress = { },
            )
            result.fold(
                onSuccess = { imported ->
                    _state.update { it.copy(busy = false) }
                    message(
                        "Imported ${imported.installed.fileName}: architecture=${imported.architecture}, " +
                            "quant=${imported.quantization}, ctx=${imported.contextLength}. " +
                            "Source checksum was recorded but could not be verified against a trusted list.",
                    )
                },
                onFailure = { t ->
                    _state.update { it.copy(busy = false) }
                    message("Import failed: ${t.message}")
                },
            )
            withContext(Dispatchers.Main) { refresh() }
        }
    }
}
