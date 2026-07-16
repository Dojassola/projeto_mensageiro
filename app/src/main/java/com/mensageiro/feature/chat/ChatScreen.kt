package com.mensageiro.feature.chat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.mensageiro.core.crypto.ContactStore
import com.mensageiro.core.crypto.BackupManager
import com.mensageiro.core.crypto.AutomaticBackup
import com.mensageiro.core.crypto.AttachmentStore
import com.mensageiro.core.crypto.CryptoText
import com.mensageiro.core.crypto.DriveBackupStorage
import com.mensageiro.core.crypto.IdentityStore
import com.mensageiro.core.crypto.LocalIdentity
import com.mensageiro.core.crypto.MessageStatus
import com.mensageiro.core.crypto.MessageStore
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.StoredMessage
import com.mensageiro.core.crypto.VerifiedContact
import com.mensageiro.core.webrtc.CallState
import com.mensageiro.core.MessagingService
import com.mensageiro.core.ContactPreview
import com.mensageiro.core.AppUpdater
import com.mensageiro.core.AppUpdate
import com.mensageiro.core.DownloadedUpdate
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.android.gms.auth.api.identity.Identity
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mensageiro.ui.theme.MensageiroTheme
import com.mensageiro.ui.contacts.AddContactScreen
import com.mensageiro.ui.contacts.ContactsScreen
import com.mensageiro.ui.profile.BlockedContactsSection
import com.mensageiro.ui.common.ProfileAvatar
import com.mensageiro.ui.common.decodePreview
import com.mensageiro.ui.common.elapsedTime
import com.mensageiro.ui.common.messageDate
import com.mensageiro.ui.common.messageStatus
import com.mensageiro.ui.common.messageTime
import com.mensageiro.ui.common.openAttachment
import com.mensageiro.ui.common.sameDay
import java.util.Date
import java.util.Calendar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ConversationScreen(
    contact: VerifiedContact?,
    attachmentStore: AttachmentStore,
    profilePhotos: ProfilePhotoStore,
    modifier: Modifier,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit,
    showBack: Boolean = true,
    useStatusBarPadding: Boolean = true
) {
    val context = LocalContext.current
    if (contact == null) {
        Column(modifier = modifier.fillMaxSize().navigationBarsPadding()) {
            TextButton(
                onClick = onBack,
                modifier = if (useStatusBarPadding) Modifier.statusBarsPadding() else Modifier
            ) { Text("< Voltar") }
        }
        return
    }
    val chatViewModel = viewModel<ChatViewModel>(key = "chat-${contact.peerId}") {
        ChatViewModel(context.applicationContext, contact.peerId)
    }
    val uiState by chatViewModel.state.collectAsState()
    val snapshot = uiState.snapshot
    val replyToId = uiState.replyToId
    val editingId = uiState.editingId
    val fileStatus = uiState.message
    val loadingOlder = uiState.loadingOlder
    val hasOlderMessages = uiState.hasOlderMessages
    val scope = rememberCoroutineScope()
    val composerState = rememberSaveable(contact.peerId, saver = MessageComposerState.Saver) {
        MessageComposerState()
    }
    var messageOptions by remember(contact.peerId) { mutableStateOf<StoredMessage?>(null) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val composerFocus = remember(contact.peerId) { FocusRequester() }
    val listState = rememberLazyListState()
    var followLatest by remember(contact.peerId) { mutableStateOf(true) }
    var showCallScreen by remember(contact.peerId) { mutableStateOf(false) }
    var pendingCallAction by remember(contact.peerId) { mutableStateOf<(() -> Unit)?>(null) }
    val requestCallPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val action = pendingCallAction
        pendingCallAction = null
        val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (microphoneGranted) action?.invoke()
        else chatViewModel.setMessage("Permita o uso do microfone para fazer chamadas.")
    }
    fun withMicrophone(action: () -> Unit) {
        val missing = buildList {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingCallAction = action
            requestCallPermissions.launch(missing.toTypedArray())
        }
    }
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { attachmentStore.import(uri) } }
            result.onSuccess {
                chatViewModel.sendFile(it.messageId, it.attachment)
                chatViewModel.setMessage("")
            }.onFailure {
                chatViewModel.setMessage(it.message ?: "Falha ao preparar arquivo.")
            }
        }
    }
    DisposableEffect(chatViewModel) {
        chatViewModel.attach()
        onDispose(chatViewModel::detach)
    }
    val messages = remember(snapshot.messages) { snapshot.messages.asReversed() }
    val connected = snapshot.connected
    fun startReply(message: StoredMessage) {
        if (editingId != null) composerState.clear()
        chatViewModel.startReply(message)
    }
    fun startEdit(message: StoredMessage) {
        chatViewModel.startEditing(message)?.let { composerState.text = it }
    }
    LaunchedEffect(messages.firstOrNull()?.id, contact.peerId) {
        chatViewModel.markRead()
    }
    LaunchedEffect(replyToId, editingId) {
        if (replyToId != null || editingId != null) composerFocus.requestFocus()
    }
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (messages.isNotEmpty() && followLatest) listState.scrollToItem(0)
    }
    LaunchedEffect(snapshot.callState) {
        if (snapshot.callState == CallState.RINGING) showCallScreen = true
    }
    LaunchedEffect(listState, contact.peerId) {
        snapshotFlow {
            messages.isEmpty() || listState.layoutInfo.visibleItemsInfo.any { it.index == 0 }
        }.collect { followLatest = it }
    }
    LaunchedEffect(listState, messages.size, hasOlderMessages) {
        if (!hasOlderMessages) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            messages.isNotEmpty() && lastVisible >= messages.lastIndex - 5
        }.collect { nearOldest ->
            if (nearOldest) chatViewModel.loadOlder()
        }
    }
    LaunchedEffect(contact.peerId) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(30_000)
        }
    }

    BackHandler(enabled = editingId != null || replyToId != null) {
        if (editingId != null) composerState.clear()
        chatViewModel.cancelComposer()
    }

    Column(modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        if (snapshot.callState != CallState.IDLE && showCallScreen) {
            CallScreen(
                contactName = contact.displayName,
                photo = profilePhotos.remote(contact.peerId),
                state = snapshot.callState,
                startedAt = snapshot.callStartedAt,
                muted = snapshot.callMuted,
                speakerOn = snapshot.speakerOn,
                useStatusBarPadding = useStatusBarPadding,
                onBack = { showCallScreen = false },
                onAccept = {
                    withMicrophone {
                        chatViewModel.acceptCall()
                    }
                },
                onReject = chatViewModel::rejectCall,
                onMute = { chatViewModel.setMuted(!snapshot.callMuted) },
                onSpeaker = { chatViewModel.setSpeaker(!snapshot.speakerOn) },
                onEnd = chatViewModel::endCall
            )
            return@Column
        }

        val headerModifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).let {
            if (useStatusBarPadding) it.statusBarsPadding() else it
        }
        Column(headerModifier) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                if (showBack) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("<") }
                }
                Row(
                    modifier = Modifier.weight(1f).clickable(onClick = onOpenProfile),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(
                        profilePhotos.remote(contact.peerId),
                        contact.displayName,
                        Modifier.size(40.dp)
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            contact.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (snapshot.active) "online"
                            else if (snapshot.lastOnline > 0) "visto ha ${elapsedTime(clock - snapshot.lastOnline)}"
                            else snapshot.status,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "WebRTC",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snapshot.callState == CallState.IDLE) {
                    TextButton(
                        enabled = connected,
                        onClick = {
                            withMicrophone {
                                showCallScreen = true
                                chatViewModel.startCall()
                            }
                        }
                    ) { Text("Ligar") }
                } else {
                    TextButton(onClick = { showCallScreen = true }) { Text("Chamada") }
                }
                TextButton(
                    onClick = chatViewModel::reconnect,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Reconectar" },
                    contentPadding = PaddingValues(0.dp)
                ) { Text("↻") }
            }
            HorizontalDivider()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bubbleWidth = minOf(560.dp, maxWidth * 0.84f)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = if (maxWidth < 360.dp) 8.dp else 12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    Column(Modifier.fillMaxWidth()) {
                        if (index == messages.lastIndex || !sameDay(messages[index + 1].timestamp, message.timestamp)) {
                                            Text(
                                messageDate(context, message.timestamp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (message.system) {
                            Text(
                                message.text,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start
                            ) {
                                SwipeMessage(
                                    message = message,
                                    enabled = true,
                                    onSwipe = { startReply(message) }
                                ) {
                                    Card(
                                        Modifier.widthIn(min = 72.dp, max = bubbleWidth).combinedClickable(
                                            onClick = {},
                                            onLongClickLabel = "Opcoes da mensagem",
                                            onLongClick = { messageOptions = message }
                                        )
                                    ) {
                                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                            message.replyToId?.let { targetId ->
                                                val target = messages.firstOrNull { it.id == targetId }
                                                MessageQuote(
                                                    title = when {
                                                        target == null -> "Mensagem"
                                                        target.mine -> "Voce"
                                                        else -> contact.displayName
                                                    },
                                                    text = target?.let(::messagePreview) ?: "Mensagem indisponivel"
                                                )
                                                Spacer(Modifier.height(6.dp))
                                            }
                                            val attachment = message.attachment
                                            if (attachment == null) {
                                                Text(message.text)
                                            } else {
                                                AttachmentPreview(attachment)
                                                Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    android.text.format.Formatter.formatShortFileSize(context, attachment.size),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (attachment.complete) {
                                                    TextButton(onClick = {
                                                        chatViewModel.setMessage(
                                                            openAttachment(context, attachmentStore, attachment)
                                                        )
                                                    }) { Text("Abrir") }
                                                } else {
                                                    Text(
                                                        if (message.mine) "Arquivo indisponivel" else "Recebendo...",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            Row(
                                                modifier = Modifier.align(Alignment.End).padding(top = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (message.editedAt > 0) {
                                                    Text(
                                                        "editada",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(Modifier.size(6.dp))
                                                }
                                                Text(
                                                    messageTime(context, message.timestamp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (message.mine) {
                                                    Spacer(Modifier.size(6.dp))
                                                    Text(
                                                        messageStatus(message.status),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (message.status == MessageStatus.PENDING) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = messageOptions?.id == message.id,
                                        onDismissRequest = { messageOptions = null },
                                        properties = PopupProperties(focusable = false)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Responder") },
                                            onClick = {
                                                startReply(message)
                                                messageOptions = null
                                            }
                                        )
                                        if (message.mine && message.attachment == null) {
                                            DropdownMenuItem(
                                                text = { Text("Editar") },
                                                onClick = {
                                                    startEdit(message)
                                                    messageOptions = null
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Copiar") },
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(
                                                    ClipData.newPlainText("Mensagem", messagePreview(message))
                                                )
                                                messageOptions = null
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Excluir para mim") },
                                            onClick = {
                                                if (editingId == message.id) composerState.clear()
                                                chatViewModel.deleteLocal(message.id)
                                                messageOptions = null
                                            }
                                        )
                                        if (message.mine) {
                                            DropdownMenuItem(
                                                text = { Text("Excluir para todos") },
                                                onClick = {
                                                    if (chatViewModel.deleteForEveryone(message.id)) {
                                                        if (editingId == message.id) composerState.clear()
                                                        messageOptions = null
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (loadingOlder) {
                    item(key = "loading-older") {
                        Text(
                            "Carregando mensagens...",
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (!followLatest && messages.isNotEmpty()) {
                TextButton(
                    onClick = {
                        followLatest = true
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .background(MaterialTheme.colorScheme.surface)
                ) { Text("Novas mensagens") }
            }
        }
        if (fileStatus.isNotBlank()) {
            Text(
                fileStatus,
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        MessageComposer(
            state = composerState,
            contact = contact,
            messages = messages,
            editingId = editingId,
            replyToId = replyToId,
            focusRequester = composerFocus,
            onChooseFile = { chooseFile.launch(arrayOf("*/*")) },
            onCancel = {
                if (editingId != null) composerState.clear()
                chatViewModel.cancelComposer()
            },
            onSubmit = { text ->
                val sendingNew = editingId == null
                chatViewModel.submit(text).also { sent ->
                    if (sent && sendingNew) {
                        followLatest = true
                    }
                }
            }
        )
    }

}

@Composable
private fun CallScreen(
    contactName: String,
    photo: StoredAttachment?,
    state: CallState,
    startedAt: Long,
    muted: Boolean,
    speakerOn: Boolean,
    useStatusBarPadding: Boolean,
    onBack: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onEnd: () -> Unit
) {
    val duration by produceState(0L, state, startedAt) {
        while ((state == CallState.ACTIVE || state == CallState.RECONNECTING) && startedAt > 0) {
            value = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
            delay(1_000)
        }
    }
    val status = when (state) {
        CallState.CALLING -> "Chamando..."
        CallState.RINGING -> "Chamada recebida"
        CallState.CONNECTING -> "Conectando chamada..."
        CallState.ACTIVE -> "%02d:%02d".format(duration / 60_000, duration / 1_000 % 60)
        CallState.RECONNECTING -> "Reconectando chamada..."
        CallState.IDLE -> ""
    }
    val screenModifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).let {
        if (useStatusBarPadding) it.statusBarsPadding() else it
    }
    Column(
        modifier = screenModifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Conversa") }
        }
        Spacer(Modifier.weight(1f))
        ProfileAvatar(photo, contactName, Modifier.size(112.dp))
        Spacer(Modifier.height(20.dp))
        Text(contactName, style = MaterialTheme.typography.headlineSmall, maxLines = 2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (state == CallState.RINGING) {
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
                Text("Atender")
            }
            TextButton(onClick = onReject, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
                Text("Recusar")
            }
        } else {
            if (state == CallState.ACTIVE || state == CallState.RECONNECTING) {
                Row(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                    TextButton(onClick = onMute, modifier = Modifier.weight(1f)) {
                        Text(if (muted) "Ativar mic" else "Silenciar")
                    }
                    TextButton(onClick = onSpeaker, modifier = Modifier.weight(1f)) {
                        Text(if (speakerOn) "Telefone" else "Viva-voz")
                    }
                }
            }
            Button(onClick = onEnd, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
                Text(if (state == CallState.CALLING) "Cancelar" else "Encerrar")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private class MessageComposerState(initialText: String = "") {
    var text by mutableStateOf(initialText)

    fun clear() {
        text = ""
    }

    companion object {
        val Saver = Saver<MessageComposerState, String>(
            save = { it.text },
            restore = { MessageComposerState(it) }
        )
    }
}

@Composable
private fun MessageComposer(
    state: MessageComposerState,
    contact: VerifiedContact,
    messages: List<StoredMessage>,
    editingId: String?,
    replyToId: String?,
    focusRequester: FocusRequester,
    onChooseFile: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: (String) -> Boolean
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
    ) {
        val compact = maxWidth < 360.dp
        val targetId = editingId ?: replyToId
        val target = messages.firstOrNull { it.id == targetId }
        Column {
            if (targetId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editingId != null) "Editando mensagem"
                            else "Respondendo a ${if (target?.mine == true) "voce" else contact.displayName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            target?.let(::messagePreview) ?: "Mensagem indisponivel",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = {
                            if (editingId != null) state.clear()
                            onCancel()
                        },
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("X") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onChooseFile,
                    modifier = Modifier.size(48.dp),
                    enabled = editingId == null && replyToId == null,
                    contentPadding = PaddingValues(0.dp)
                ) { Text("+") }
                Spacer(Modifier.size(if (compact) 4.dp else 8.dp))
                OutlinedTextField(
                    value = state.text,
                    onValueChange = { if (it.length <= 4_000) state.text = it },
                    modifier = Modifier.weight(1f).widthIn(min = 0.dp).focusRequester(focusRequester),
                    placeholder = { Text(if (editingId == null) "Mensagem" else "Editar mensagem") },
                    maxLines = 4
                )
                Spacer(Modifier.size(if (compact) 4.dp else 8.dp))
                Button(
                    onClick = {
                        if (onSubmit(state.text)) state.clear()
                    },
                    enabled = state.text.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(if (editingId == null) "Enviar" else "Salvar") }
            }
        }
    }
}

@Composable
private fun SwipeMessage(
    message: StoredMessage,
    enabled: Boolean,
    onSwipe: () -> Unit,
    content: @Composable () -> Unit
) {
    var offset by remember(message.id) { mutableStateOf(0f) }
    var armed by remember(message.id) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    Box {
        if (offset != 0f) {
            Text(
                "↩",
                modifier = Modifier.align(if (message.mine) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 16.dp),
                color = if (armed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            Modifier.pointerInput(message.id, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        val next = offset + amount
                        offset = if (message.mine) {
                            next.coerceIn(-threshold * 1.25f, 0f)
                        } else {
                            next.coerceIn(0f, threshold * 1.25f)
                        }
                        val reached = if (message.mine) offset <= -threshold else offset >= threshold
                        if (reached && !armed) {
                            armed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val triggered = armed
                        offset = 0f
                        armed = false
                        if (triggered) onSwipe()
                    },
                    onDragCancel = {
                        offset = 0f
                        armed = false
                    }
                )
            }.graphicsLayer { translationX = offset }
        ) { content() }
    }
}

@Composable
private fun MessageQuote(title: String, text: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

private fun messagePreview(message: StoredMessage): String =
    message.attachment?.let { "Arquivo: ${it.name}" } ?: message.text

@Composable
private fun AttachmentPreview(attachment: com.mensageiro.core.crypto.StoredAttachment) {
    if (!attachment.complete || !attachment.mimeType.startsWith("image/")) return
    val bitmap by produceState<Bitmap?>(null, attachment.localPath) {
        value = withContext(Dispatchers.IO) { decodePreview(attachment.localPath) }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = attachment.name,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(16f / 10f)
                .padding(bottom = 8.dp),
            contentScale = ContentScale.Crop
        )
    }
}


