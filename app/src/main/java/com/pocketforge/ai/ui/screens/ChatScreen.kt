package com.pocketforge.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import android.widget.Toast
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketforge.ai.PocketForgeApp
import com.pocketforge.ai.inference.ModelLoadService
import com.pocketforge.ai.ui.components.MarkdownText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val container = LocalContext.current.applicationContext as PocketForgeApp
    val vm: ChatViewModel = viewModel(initializer = { ChatViewModel(container) })
    val state by vm.state.collectAsState()
    var showConversations by remember { mutableStateOf(false) }
    var showSwitcher by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toastContext = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.streamingText.length, state.generating) {
        val target = (state.messages.size + (if (state.generating) 1 else 0)).coerceAtLeast(0)
        if (target > 0) {
            listState.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(state.activeTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = modelStatusLine(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { showSwitcher = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Switch model")
                }
                DropdownMenu(expanded = showSwitcher, onDismissRequest = { if (!switching) showSwitcher = false }) {
                    if (switching) {
                        DropdownMenuItem(
                            text = { Text("Loading model…") },
                            onClick = {},
                            leadingIcon = { CircularProgressIndicator(modifier = Modifier.size(18.dp)) },
                        )
                    } else {
                        val installed = ModelLoadService(container).installedModels()
                        if (installed.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No models installed") },
                                onClick = { showSwitcher = false },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Installed models (jump between them)") },
                                onClick = {},
                                enabled = false,
                            )
                        }
                        installed.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label, maxLines = 1) },
                                onClick = {
                                    showSwitcher = false
                                    switching = true
                                    scope.launch {
                                        val r = ModelLoadService(container).loadInstalled(id)
                                        Toast.makeText(
                                            toastContext,
                                            when (r) {
                                                is ModelLoadService.Result.Loaded -> r.summary
                                                is ModelLoadService.Result.Failure -> r.message
                                            },
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        switching = false
                                    }
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Manage / download models (Models tab)") },
                            onClick = { showSwitcher = false },
                        )
                    }
                }
                IconButton(onClick = { showConversations = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Conversations")
                }
            },
        )

        if (!state.engineReady && !state.generating) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("No model loaded", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Open the Models tab to download, import or load a model.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        state.error?.let { err ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        err,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = vm::dismissError) { Text("Dismiss") }
                }
            }
        }

        if (state.chatsUnrecoverable) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { },
                title = { Text("Chats are unreadable") },
                text = {
                    Text(
                        "The saved chats could not be decrypted with your device's key. " +
                            "This can happen after a failed backup restore or key corruption. " +
                            "The stored data will not be overwritten silently. Tap Reset to delete the " +
                            "chat history and start clean (model files and other data are kept).",
                    )
                },
                confirmButton = {
                    TextButton(onClick = vm::resetUnreadableChats) {
                        Text("Reset chats", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { msg ->
                    MessageBubble(msg)
                }
                if (state.generating) {
                    item { MessageBubble(UiMessage("assistant", state.streamingText, isStreaming = true)) }
                }
                if (state.messages.isEmpty() && !state.generating) {
                    item {
                        Text(
                            "Local, private chat. Everything runs on this device.\nSend your first message.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }

        ChatInput(
            enabled = state.engineReady && !state.generating,
            generating = state.generating,
            onSend = vm::send,
            onStop = vm::stopGeneration,
        )
    }

    if (showConversations) {
        AlertDialog(
            onDismissRequest = { showConversations = false },
            confirmButton = { TextButton(onClick = { showConversations = false }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = { vm.deleteAllConversations(); showConversations = false }) {
                    Text("Delete all conversations", color = MaterialTheme.colorScheme.error)
                }
            },
            title = { Text("Conversations") },
            text = {
                ConversationList(
                    conversations = state.conversations,
                    activeId = state.activeId,
                    onSelect = { vm.selectConversation(it); showConversations = false },
                    onDelete = vm::deleteConversation,
                )
            },
        )
    }
}

@Composable
private fun ConversationList(
    conversations: List<ConversationMeta>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 380.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (conversations.isEmpty()) {
            item { Text("No saved conversations yet") }
        }
        items(conversations, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(c.id) }, modifier = Modifier.weight(1f)) {
                    Column {
                        Text(
                            c.title,
                            maxLines = 1,
                            color = if (c.id == activeId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            "${c.messageCount} messages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onDelete(c.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Delete conversation")
                }
            }
        }
    }
}

private fun modelStatusLine(state: ChatUiState): String {
    val info = state.runtimeInfo
    val base = if (state.generating) "generating" else state.engineStateLabel
    return if (info != null) "$base | ${info.desc} | ctx ${info.nCtx}" else base
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(10.dp)) {
                if (message.isStreaming) {
                    if (message.content.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("thinking...", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        MarkdownText(message.content)
                    }
                } else if (isUser) {
                    Text(message.content)
                } else {
                    MarkdownText(message.content)
                }
                if (!isUser && !message.isStreaming && message.content.isNotEmpty()) {
                    CopyRow(message.content)
                }
            }
        }
    }
}

@Composable
private fun CopyRow(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    TextButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ChatInput(
    enabled: Boolean,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 4,
        )
        if (generating) {
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop generation")
            }
        } else {
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
