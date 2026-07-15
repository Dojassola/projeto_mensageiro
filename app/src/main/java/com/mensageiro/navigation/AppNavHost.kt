package com.mensageiro.navigation

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
import com.mensageiro.feature.profile.ContactProfileScreen
import com.mensageiro.feature.profile.ProfileScreen
import com.mensageiro.app.AppContainer
import java.util.Date
import java.util.Calendar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun MensageiroApp(
    container: AppContainer,
    incomingMessage: String? = null,
    onMessageConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identityStore = container.identityStore
    var identity by remember { mutableStateOf(identityStore.getOrCreate()) }
    var screen by rememberSaveable {
        mutableStateOf(if (identity.displayName.isBlank() || identity.displayName == "Eu") Screen.Profile else Screen.Contacts)
    }
    val contactStore = container.contactStore
    var contacts by remember { mutableStateOf(contactStore.all()) }
    var selectedPeerId by rememberSaveable { mutableStateOf<String?>(contacts.firstOrNull()?.peerId) }
    var contactStatus by rememberSaveable { mutableStateOf("") }
    var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
    var downloadedUpdate by remember { mutableStateOf<DownloadedUpdate?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateError by rememberSaveable { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateCheckStatus by rememberSaveable { mutableStateOf("") }

    fun checkUpdatesNow() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateCheckStatus = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdater.check(context) } }
            checkingUpdate = false
            result.onSuccess { update ->
                if (update == null) {
                    updateCheckStatus = "Voce ja esta na versao mais recente."
                } else {
                    availableUpdate = update
                    updateCheckStatus = "Versao ${update.versionName} disponivel."
                }
            }.onFailure {
                updateCheckStatus = "Falha ao verificar: ${it.message ?: "erro desconhecido"}"
            }
        }
    }

    LaunchedEffect(Unit) {
        downloadedUpdate = withContext(Dispatchers.IO) { AppUpdater.downloaded(context) }
        if (downloadedUpdate == null && AppUpdater.shouldCheck(context)) checkUpdatesNow()
    }

    fun importContact(payload: String): String = runCatching {
        contactStore.importContact(payload, identity.publicKey).also {
            contacts = contactStore.all()
            selectedPeerId = it.peerId
            MessagingRuntime.selectContact(context, it.peerId)
        }
    }.fold(
        onSuccess = { "Contato adicionado: ${it.displayName}" },
        onFailure = { "Falha: ${it.message}" }
    ).also { contactStatus = it }

    LaunchedEffect(incomingMessage) {
        if (incomingMessage?.startsWith("v=${CryptoText.ProtocolVersion}") == true) {
            importContact(incomingMessage)
            screen = if (contactStatus.startsWith("Contato")) Screen.Contacts else Screen.AddContact
            onMessageConsumed()
        }
    }

    fun openContact(contact: VerifiedContact) {
        selectedPeerId = contact.peerId
        MessagingRuntime.selectContact(context, contact.peerId)
        screen = Screen.Conversation
    }

    fun openContactProfile(contact: VerifiedContact) {
        selectedPeerId = contact.peerId
        MessagingRuntime.selectContact(context, contact.peerId)
        screen = Screen.ContactProfile
    }

    fun renameContact(peerId: String, name: String): String {
        contactStatus = runCatching { contactStore.rename(peerId, name) }
            .fold({ "Contato renomeado." }, { "Falha: ${it.message}" })
        contacts = contactStore.all()
        MessagingRuntime.start(context)
        return contactStatus
    }

    fun deleteContact(peerId: String, deleteConversation: Boolean, deleteAttachments: Boolean, block: Boolean) {
        val contact = contactStore.all().firstOrNull { it.peerId == peerId } ?: return
        if (deleteConversation) {
            val removed = container.messageStore.deleteConversation(peerId)
            if (deleteAttachments) {
                removed.forEach { container.attachmentStore.delete(it.id, it.attachment) }
            }
        }
        if (block) contactStore.block(contact) else contactStore.remove(peerId)
        contacts = contactStore.all()
        selectedPeerId = contacts.firstOrNull()?.peerId
        MessagingRuntime.reload(context)
        screen = Screen.Contacts
    }

    fun goBack() {
        screen = if (screen == Screen.ContactProfile && contacts.any { it.peerId == selectedPeerId }) {
            Screen.Conversation
        } else Screen.Contacts
    }

    BackHandler(enabled = screen != Screen.Contacts) { goBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (screen != Screen.Conversation || expanded) {
                    AppTopBar(
                        screen = screen,
                        expanded = expanded,
                        onContacts = ::goBack,
                        onAddContact = { screen = Screen.AddContact },
                        onProfile = { screen = Screen.Profile }
                    )
                }
            }
        ) { innerPadding ->
            if (expanded && (screen == Screen.Contacts || screen == Screen.Conversation)) {
                ExpandedConversationLayout(
                    contacts = contacts,
                    selectedPeerId = selectedPeerId.takeIf { screen == Screen.Conversation },
                    container = container,
                    modifier = Modifier.padding(innerPadding),
                    onOpen = ::openContact,
                    onProfile = ::openContactProfile,
                    onBack = { screen = Screen.Contacts },
                    onOpenProfile = { contacts.firstOrNull { it.peerId == selectedPeerId }?.let(::openContactProfile) }
                )
            } else when (screen) {
                Screen.Contacts -> ContactsScreen(
                    contacts = contacts,
                    profilePhotos = container.profilePhotoStore,
                    modifier = Modifier.padding(innerPadding),
                    onOpen = ::openContact,
                    onProfile = ::openContactProfile
                )
                Screen.AddContact -> AddContactScreen(
                    identity,
                    contactStatus,
                    ::importContact,
                    Modifier.padding(innerPadding)
                )
                Screen.Conversation -> ConversationScreen(
                    contact = contacts.firstOrNull { it.peerId == selectedPeerId },
                    attachmentStore = container.attachmentStore,
                    profilePhotos = container.profilePhotoStore,
                    modifier = Modifier.padding(innerPadding),
                    onBack = { screen = Screen.Contacts },
                    onOpenProfile = { contacts.firstOrNull { it.peerId == selectedPeerId }?.let(::openContactProfile) }
                )
                Screen.Profile -> ProfileScreen(
                    identity = identity,
                    backupManager = container.backupManager,
                    profilePhotos = container.profilePhotoStore,
                    contactStore = container.contactStore,
                    modifier = Modifier.padding(innerPadding),
                    versionName = AppUpdater.versionName(context),
                    checkingUpdate = checkingUpdate,
                    updateStatus = updateCheckStatus,
                    onCheckUpdate = ::checkUpdatesNow,
                    onSave = { name ->
                        runCatching { identityStore.rename(name) }
                            .onSuccess {
                                identity = it
                                MessagingRuntime.reload(context)
                                screen = Screen.Contacts
                            }.exceptionOrNull()?.message.orEmpty()
                    },
                    onRestored = {
                        identity = identityStore.getOrCreate()
                        contacts = contactStore.all()
                        selectedPeerId = contacts.firstOrNull()?.peerId
                        MessagingRuntime.reload(context)
                        screen = Screen.Contacts
                    }
                )
                Screen.ContactProfile -> ContactProfileScreen(
                    contact = contacts.firstOrNull { it.peerId == selectedPeerId },
                    profilePhotos = container.profilePhotoStore,
                    contactStore = container.contactStore,
                    modifier = Modifier.padding(innerPadding),
                    onSave = ::renameContact,
                    onReconnect = { selectedPeerId?.let(MessagingRuntime::reconnect) },
                    onDelete = ::deleteContact
                )
            }
        }
    }

    val update = downloadedUpdate?.update ?: availableUpdate
    if (update != null) {
        AlertDialog(
            onDismissRequest = {
                if (!updateBusy) {
                    availableUpdate = null
                    downloadedUpdate = null
                }
            },
            title = { Text(if (downloadedUpdate == null) "Atualizacao disponivel" else "Atualizacao pronta") },
            text = {
                Column {
                    Text("Mensageiro ${update.versionName}")
                    if (update.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(update.notes, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                    if (updateBusy) {
                        Spacer(Modifier.height(8.dp))
                        Text("Baixando...")
                    }
                    if (updateError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(updateError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !updateBusy,
                    onClick = {
                        val ready = downloadedUpdate
                        if (ready != null) {
                            if (AppUpdater.canInstall(context)) {
                                context.startActivity(AppUpdater.installIntent(context, ready))
                            } else {
                                updateError = "Permita instalacoes do Mensageiro e toque em Instalar novamente."
                                context.startActivity(AppUpdater.permissionIntent(context))
                            }
                        } else {
                            updateBusy = true
                            updateError = ""
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val id = AppUpdater.enqueue(context, update)
                                        AppUpdater.await(context, id)
                                    }
                                }
                                updateBusy = false
                                result.onSuccess { downloadedUpdate = it }
                                    .onFailure { updateError = it.message ?: "Falha ao baixar atualizacao." }
                            }
                        }
                    }
                ) { Text(if (downloadedUpdate == null) "Baixar" else "Instalar") }
            },
            dismissButton = {
                TextButton(
                    enabled = !updateBusy,
                    onClick = {
                        availableUpdate = null
                        downloadedUpdate = null
                    }
                ) { Text("Depois") }
            }
        )
    }
}

@Composable
private fun AppTopBar(
    screen: Screen,
    expanded: Boolean,
    onContacts: () -> Unit,
    onAddContact: () -> Unit,
    onProfile: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 360.dp
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (screen == Screen.Contacts || (expanded && screen == Screen.Conversation)) {
                    Text("Mensageiro", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onAddContact) { Text(if (narrow) "+" else "+ Contato") }
                    TextButton(onClick = onProfile) { Text(if (narrow) "Eu" else "Perfil") }
                } else {
                    TextButton(
                        onClick = onContacts,
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("<") }
                    Text(screen.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    if (screen == Screen.AddContact) {
                        TextButton(onClick = onProfile) { Text(if (narrow) "Eu" else "Perfil") }
                    }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ExpandedConversationLayout(
    contacts: List<VerifiedContact>,
    selectedPeerId: String?,
    container: AppContainer,
    modifier: Modifier,
    onOpen: (VerifiedContact) -> Unit,
    onProfile: (VerifiedContact) -> Unit,
    onBack: () -> Unit,
    onOpenProfile: () -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.fillMaxHeight().widthIn(max = 1_280.dp).fillMaxWidth()) {
            ContactsScreen(
                contacts = contacts,
                profilePhotos = container.profilePhotoStore,
                modifier = Modifier.fillMaxHeight().width(360.dp),
                selectedPeerId = selectedPeerId,
                onOpen = onOpen,
                onProfile = onProfile
            )
            VerticalDivider(Modifier.fillMaxHeight())
            val contact = contacts.firstOrNull { it.peerId == selectedPeerId }
            if (contact == null) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(
                        "Selecione uma conversa",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                ConversationScreen(
                    contact = contact,
                    attachmentStore = container.attachmentStore,
                    profilePhotos = container.profilePhotoStore,
                    modifier = Modifier.weight(1f),
                    onBack = onBack,
                    onOpenProfile = onOpenProfile,
                    showBack = false,
                    useStatusBarPadding = false
                )
            }
        }
    }
}

internal fun qrCode(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 700, 700)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
        else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}

private enum class Screen(val title: String) {
    Contacts("Contatos"),
    AddContact("Adicionar"),
    Conversation("Conversa"),
    Profile("Perfil"),
    ContactProfile("Contato")
}

