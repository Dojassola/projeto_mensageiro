package com.mensageiro.feature.profile

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
import androidx.compose.material3.AlertDialog
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
import com.mensageiro.core.MessagingRuntime
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
import com.mensageiro.feature.chat.ConversationScreen
import com.mensageiro.ui.common.ProfileAvatar
import com.mensageiro.ui.common.ProfilePhotoDialog
import com.mensageiro.ui.common.ScreenColumn
import com.mensageiro.ui.common.elapsedTime
import java.util.Date
import java.util.Calendar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@Composable
internal fun ContactProfileScreen(
    contact: VerifiedContact?,
    profilePhotos: ProfilePhotoStore,
    contactStore: ContactStore,
    modifier: Modifier,
    onSave: (String, String) -> String,
    onReconnect: () -> Unit,
    onDelete: (String, Boolean, Boolean, Boolean) -> Unit
) {
    if (contact == null) {
        ScreenColumn(modifier) { Text("Contato nao encontrado.") }
        return
    }
    val context = LocalContext.current
    var snapshot by remember(contact.peerId) { mutableStateOf(MessagingRuntime.snapshot()) }
    var name by rememberSaveable(contact.peerId) { mutableStateOf(contact.displayName) }
    var result by rememberSaveable(contact.peerId) { mutableStateOf("") }
    var showPhoto by remember { mutableStateOf(false) }
    var removalAction by remember { mutableStateOf<ContactRemovalAction?>(null) }
    var deleteAttachments by remember { mutableStateOf(true) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val listener = remember(contact.peerId) { MessagingRuntime.Listener { snapshot = it } }

    DisposableEffect(contact.peerId) {
        MessagingRuntime.addListener(listener)
        onDispose { MessagingRuntime.removeListener(listener) }
    }
    LaunchedEffect(contact.peerId) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val photo = profilePhotos.remote(contact.peerId)
    ScreenColumn(modifier = modifier) {
        ProfileAvatar(
            photo,
            contact.displayName,
            Modifier.size(112.dp)
                .align(Alignment.CenterHorizontally)
                .clickable(enabled = photo != null) { showPhoto = true }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            contact.displayName,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            if (snapshot.active) "online"
            else if (snapshot.lastOnline > 0) "visto ha ${elapsedTime(clock - snapshot.lastOnline)}"
            else snapshot.status,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Apelido neste aparelho") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { result = onSave(contact.peerId, name) }, enabled = name.isNotBlank()) {
                Text("Salvar")
            }
            Button(onClick = onReconnect) { Text("Reconectar") }
        }
        if (result.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(result, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val payload = contactStore.all()
                    .firstOrNull { it.peerId == contact.peerId }?.sharePayload.orEmpty()
                if (payload.isBlank()) {
                    result = "Conecte com este contato antes de compartilha-lo."
                } else {
                    result = ""
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, payload)
                                },
                                "Compartilhar contato"
                            )
                        )
                    }.onFailure { result = "Nao foi possivel compartilhar o contato." }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Compartilhar contato") }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        Text("Seguranca", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("Fingerprint", style = MaterialTheme.typography.labelMedium)
        Text(contact.fingerprint, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text("Peer ID", style = MaterialTheme.typography.labelMedium)
        Text(contact.peerId, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            if (snapshot.connected) "Transporte: WebRTC conectado" else "Transporte: aguardando WebRTC",
            style = MaterialTheme.typography.bodySmall,
            color = if (snapshot.connected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        TextButton(
            onClick = { removalAction = ContactRemovalAction.Remove },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Remover contato", color = MaterialTheme.colorScheme.error)
        }
        TextButton(
            onClick = { removalAction = ContactRemovalAction.RemoveConversation },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Remover e apagar conversa", color = MaterialTheme.colorScheme.error)
        }
        TextButton(
            onClick = { removalAction = ContactRemovalAction.Block },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Bloquear contato", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showPhoto && photo != null) {
        ProfilePhotoDialog(photo, contact.displayName) { showPhoto = false }
    }
    removalAction?.let { action ->
        AlertDialog(
            onDismissRequest = { removalAction = null },
            title = {
                Text(if (action == ContactRemovalAction.Block) "Bloquear contato?" else "Remover contato?")
            },
            text = {
                Column {
                    Text(
                        when (action) {
                            ContactRemovalAction.Remove -> "A conversa e os arquivos locais serao mantidos."
                            ContactRemovalAction.RemoveConversation -> "A conversa sera apagada somente deste aparelho."
                            ContactRemovalAction.Block -> "Novas conexoes e sinalizacoes deste Peer ID serao rejeitadas."
                        }
                    )
                    if (action == ContactRemovalAction.RemoveConversation) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Apagar anexos e midias", modifier = Modifier.weight(1f))
                            Switch(checked = deleteAttachments, onCheckedChange = { deleteAttachments = it })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(
                        contact.peerId,
                        action == ContactRemovalAction.RemoveConversation,
                        action == ContactRemovalAction.RemoveConversation && deleteAttachments,
                        action == ContactRemovalAction.Block
                    )
                }) {
                    Text(
                        if (action == ContactRemovalAction.Block) "Bloquear" else "Remover",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removalAction = null }) { Text("Cancelar") }
            }
        )
    }
}


private enum class ContactRemovalAction { Remove, RemoveConversation, Block }
