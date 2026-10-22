package com.pocketforge.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.chat.ChatMessage
import com.pocketforge.ai.chat.ChatStore
import com.pocketforge.ai.inference.EngineState
import com.pocketforge.ai.inference.FinishReason
import com.pocketforge.ai.inference.LlamaInferenceEngine
import com.pocketforge.ai.inference.ModelRuntimeInfo
import com.pocketforge.ai.inference.SessionStats
import com.pocketforge.ai.inference.StreamEvent
import com.pocketforge.ai.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConversationMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val messageCount: Int,
)

data class UiMessage(
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
)

data class ChatUiState(
    val engineReady: Boolean = false,
    val engineStateLabel: String = "starting",
    val runtimeInfo: ModelRuntimeInfo? = null,
    val conversations: List<ConversationMeta> = emptyList(),
    val activeId: String? = null,
    val activeTitle: String = "New chat",
    val messages: List<UiMessage> = emptyList(),
    val streamingText: String = "",
    val generating: Boolean = false,
    val error: String? = null,
    val systemPrompt: String = "",
    val chatsUnrecoverable: Boolean = false,
)

class ChatViewModel(
    private val app: PocketForgeApp,
) : ViewModel() {

    private val engine: LlamaInferenceEngine = app.engine
    private val chatStore: ChatStore = app.chatStore

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var settings: Settings = Settings()
    private var sendJob: Job? = null

    /** Session id against which the active conversation's history is registered natively. */
    private var historySessionId: Long = -1

    init {
        viewModelScope.launch {
            app.settingsStore.settings.collect { s ->
                settings = s
                _state.update { it.copy(systemPrompt = s.systemPrompt) }
            }
        }
        viewModelScope.launch {
            app.chatStore.unreadable.collect { bad ->
                _state.update { it.copy(chatsUnrecoverable = bad) }
            }
        }
        viewModelScope.launch {
            engine.state.collect { st ->
                _state.update {
                    it.copy(
                        engineReady = st is EngineState.ModelReady,
                        engineStateLabel = describe(st),
                        runtimeInfo = engine.runtimeInfo.value,
                    )
                }
                if (st is EngineState.Error) {
                    _state.update { it.copy(error = st.error.message ?: "Engine error") }
                }
            }
        }
        viewModelScope.launch {
            engine.runtimeInfo.collect { info ->
                _state.update { it.copy(runtimeInfo = info) }
            }
        }
        newConversationInternal(initial = true)
    }

    private fun describe(st: EngineState): String = when (st) {
        is EngineState.Uninitialized -> "starting"
        is EngineState.Initializing, is EngineState.Initialized -> "engine idle - no model"
        is EngineState.LoadingModel -> "loading model"
        is EngineState.ModelReady -> "ready"
        is EngineState.ProcessingUserPrompt -> "processing prompt"
        is EngineState.Generating -> "generating"
        is EngineState.Error -> "error"
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Recovery action for an unreadable encrypted chat store: wipe chats, keep everything else. */
    fun resetUnreadableChats() {
        chatStore.resetBecauseUnreadable()
        _state.update { it.copy(chatsUnrecoverable = false, messages = emptyList()) }
        newConversationInternal(false)
    }

    fun newConversation() {
        sendJob?.cancel()
        newConversationInternal(initial = false)
    }

    fun selectConversation(id: String) {
        sendJob?.cancel()
        val conv = chatStore.get(id) ?: return
        historySessionId = -1 // needs re-registration in native session before next turn
        _state.update {
            it.copy(
                activeId = conv.id,
                activeTitle = conv.title,
                messages = conv.messages.map { m -> UiMessage(m.role, m.content) },
                streamingText = "",
                generating = false,
            )
        }
        refreshConversations()
    }

    /** "Clear conversation": wipes stored messages, restores system turn in native. */
    fun clearCurrentConversation() {
        sendJob?.cancel()
        val activeId = _state.value.activeId ?: return
        val fresh = (chatStore.get(activeId) ?: return)
            .copy(messages = emptyList(), title = "New chat")
        chatStore.save(fresh)
        // conversation is empty: no restore needed, keep session id to avoid re-restore churn
        _state.update {
            it.copy(messages = emptyList(), activeTitle = "New chat", streamingText = "")
        }
        refreshConversations()
    }

    fun deleteConversation(id: String) {
        chatStore.delete(id)
        if (_state.value.activeId == id) {
            newConversationInternal(initial = false)
        } else {
            refreshConversations()
        }
    }

    fun deleteAllConversations() {
        chatStore.deleteAll()
        newConversationInternal(initial = false)
    }

    private fun refreshConversations() {
        _state.update { st ->
            st.copy(
                conversations = chatStore.list().map { c ->
                    ConversationMeta(c.id, c.title, c.createdAt, c.messages.size)
                },
            )
        }
    }

    private fun newConversationInternal(initial: Boolean) {
        val conv = chatStore.create()
        historySessionId = -1
        _state.update { st ->
            st.copy(activeId = conv.id, activeTitle = conv.title, messages = emptyList(), streamingText = "")
        }
        refreshConversations()
    }

    fun stopGeneration() {
        engine.cancel()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || sendJob?.isActive == true) return
        val s = _state.value
        if (!s.engineReady) {
            _state.update { it.copy(error = "No model is loaded. Open the Models tab.") }
            return
        }
        val activeId = s.activeId
            ?: let { newConversationInternal(false); _state.value.activeId!! }

        val existing = chatStore.get(activeId) ?: return
        val historyBefore = existing.messages // snapshot BEFORE appending (used for context restore)
        val conv = existing.copy(
            messages = existing.messages + ChatMessage("user", trimmed),
            title = chatStore.newTitleFor(existing.messages, existing.title),
        )
        chatStore.save(conv)

        _state.update { st ->
            st.copy(
                messages = st.messages + UiMessage("user", trimmed),
                generating = true,
                streamingText = "",
                error = null,
            )
        }
        refreshConversations()

        sendJob = viewModelScope.launch {
            // Streaming checkpoint: every ~2 s persist the partial assistant text
            // (append-or-replace trailing assistant message), so a crash mid-stream
            // never loses more than 2 s of output. Done/Cancel rewrites replace it.
            val checkpointJob = launch {
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    val partial = _state.value.streamingText
                    if (partial.isNotBlank()) {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            chatStore.upsertAssistantPartial(activeId, partial)
                        }
                        refreshConversations()
                    }
                }
            }
            runCatching {
                if (engine.sessionStateId != historySessionId) {
                    // Native session is fresh: (re)apply system prompt, then template-history
                    if (!engine.ensureSystemPrompt(settings.systemPrompt)) {
                        _state.update { it.copy(error = "Failed to apply system prompt", generating = false) }
                        return@launch
                    }
                    val prior = historyBefore.filter { it.role != "system" }.map { it.role to it.content }
                    val restored = engine.restoreHistory(prior)
                    if (!restored) {
                        _state.update { it.copy(error = "Failed to restore conversation context", generating = false) }
                        return@launch
                    }
                    historySessionId = engine.sessionStateId
                }

                engine.send(trimmed, settings.maxTokens)
                    .collect { event ->
                        when (event) {
                            is StreamEvent.Token -> {
                                _state.update { it.copy(streamingText = it.streamingText + event.text) }
                            }
                            is StreamEvent.Done -> {
                                val content = if (event.text.isNotBlank()) event.text else "(no output)"
                                _state.update {
                                    it.copy(
                                        messages = it.messages + UiMessage("assistant", content),
                                        streamingText = "",
                                        generating = false,
                                    )
                                }
                                withContext(Dispatchers.IO) {
                                    val existing = chatStore.get(activeId) ?: return@withContext
                                    chatStore.save(
                                        existing.copy(messages = existing.messages + ChatMessage("assistant", content)),
                                    )
                                }
                                SessionStats.onTurn(event.stats)
                                refreshConversations()
                            }
                            is StreamEvent.Error -> {
                                val content = state.value.streamingText
                                if (content.isNotBlank()) {
                                    _state.update {
                                        it.copy(
                                            messages = it.messages + UiMessage("assistant", content),
                                            streamingText = "",
                                            generating = false,
                                            error = event.error.message,
                                        )
                                    }
                                } else {
                                    _state.update {
                                        it.copy(streamingText = "", generating = false, error = event.error.message)
                                    }
                                }
                            }
                        }
                    }
            }.onFailure { t ->
                _state.update { it.copy(generating = false, error = t.message ?: "Generation failed") }
            }
            checkpointJob.cancel()
        }
    }
}
