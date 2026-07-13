package com.mensageiro

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mensageiro.core.crypto.ContactStore
import com.mensageiro.core.crypto.BackupManager
import com.mensageiro.core.crypto.AutomaticBackup
import com.mensageiro.core.crypto.AttachmentStore
import com.mensageiro.core.crypto.CryptoText
import com.mensageiro.core.crypto.IdentityStore
import com.mensageiro.core.crypto.LocalIdentity
import com.mensageiro.core.crypto.MessageStatus
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.StoredAttachment
import com.mensageiro.core.crypto.VerifiedContact
import com.mensageiro.core.MessagingRuntime
import com.mensageiro.core.MessagingService
import com.mensageiro.core.ContactPreview
import com.mensageiro.core.AppUpdater
import com.mensageiro.core.AppUpdate
import com.mensageiro.core.DownloadedUpdate
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mensageiro.ui.theme.MensageiroTheme
import java.util.Date
import java.util.Calendar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    private var incomingMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingMessage = sharedText(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        MessagingService.start(this)
        enableEdgeToEdge()
        setContent {
            MensageiroTheme {
                MensageiroApp(incomingMessage) { incomingMessage = null }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MessagingRuntime.setAppVisible(true)
    }

    override fun onStop() {
        MessagingRuntime.setAppVisible(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingMessage = sharedText(intent)
    }

    private fun sharedText(intent: Intent): String? =
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null
}

@Composable
fun MensageiroApp(incomingMessage: String? = null, onMessageConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identityStore = remember { IdentityStore(context) }
    var identity by remember { mutableStateOf(identityStore.getOrCreate()) }
    var screen by rememberSaveable {
        mutableStateOf(if (identity.displayName.isBlank() || identity.displayName == "Eu") Screen.Profile else Screen.Contacts)
    }
    val contactStore = remember { ContactStore(context) }
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

    fun renameContact(peerId: String, name: String) {
        contactStatus = runCatching { contactStore.rename(peerId, name) }
            .fold({ "Contato renomeado." }, { "Falha: ${it.message}" })
        contacts = contactStore.all()
    }

    BackHandler(enabled = screen != Screen.Contacts) { screen = Screen.Contacts }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (screen != Screen.Conversation || expanded) {
                    AppTopBar(
                        screen = screen,
                        expanded = expanded,
                        onContacts = { screen = Screen.Contacts },
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
                    modifier = Modifier.padding(innerPadding),
                    onOpen = ::openContact,
                    onRename = ::renameContact,
                    onBack = { screen = Screen.Contacts }
                )
            } else when (screen) {
                Screen.Contacts -> ContactsScreen(
                    contacts = contacts,
                    modifier = Modifier.padding(innerPadding),
                    onOpen = ::openContact,
                    onRename = ::renameContact
                )
                Screen.AddContact -> AddContactScreen(
                    identity,
                    contactStatus,
                    ::importContact,
                    Modifier.padding(innerPadding)
                )
                Screen.Conversation -> ConversationScreen(
                    contact = contacts.firstOrNull { it.peerId == selectedPeerId },
                    modifier = Modifier.padding(innerPadding),
                    onBack = { screen = Screen.Contacts }
                )
                Screen.Profile -> ProfileScreen(
                    identity = identity,
                    modifier = Modifier.padding(innerPadding),
                    versionName = AppUpdater.versionName(context),
                    checkingUpdate = checkingUpdate,
                    updateStatus = updateCheckStatus,
                    onCheckUpdate = ::checkUpdatesNow,
                    onSave = { name ->
                        runCatching { identityStore.rename(name) }
                            .onSuccess {
                                identity = it
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
    modifier: Modifier,
    onOpen: (VerifiedContact) -> Unit,
    onRename: (String, String) -> Unit,
    onBack: () -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.fillMaxHeight().widthIn(max = 1_280.dp).fillMaxWidth()) {
            ContactsScreen(
                contacts = contacts,
                modifier = Modifier.fillMaxHeight().width(360.dp),
                selectedPeerId = selectedPeerId,
                onOpen = onOpen,
                onRename = onRename
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
                    modifier = Modifier.weight(1f),
                    onBack = onBack,
                    showBack = false,
                    useStatusBarPadding = false
                )
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    contacts: List<VerifiedContact>,
    modifier: Modifier,
    selectedPeerId: String? = null,
    onOpen: (VerifiedContact) -> Unit,
    onRename: (String, String) -> Unit
) {
    val context = LocalContext.current
    val profilePhotos = remember { ProfilePhotoStore(context) }
    var renaming by remember { mutableStateOf<VerifiedContact?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val listener = remember { MessagingRuntime.Listener { revision++ } }
    DisposableEffect(Unit) {
        MessagingRuntime.addListener(listener)
        onDispose { MessagingRuntime.removeListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val previews = remember(contacts, revision, clock) {
        contacts.associate { it.peerId to MessagingRuntime.preview(it.peerId) }
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (contacts.isEmpty()) {
            item { Text("Nenhuma conversa.", modifier = Modifier.padding(vertical = 24.dp)) }
        } else {
            items(contacts, key = { it.peerId }) { contact ->
                val preview = previews[contact.peerId] ?: ContactPreview(null, 0, false)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(
                            if (contact.peerId == selectedPeerId) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable { onOpen(contact) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(
                        profilePhotos.remote(contact.peerId),
                        contact.displayName,
                        Modifier.size(48.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                contact.displayName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                contactPresence(preview, clock),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (preview.active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            preview.lastMessage?.let { (if (it.mine) "Voce: " else "") + it.text }
                                ?: "Nenhuma mensagem",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    TextButton(
                        onClick = {
                            renaming = contact
                            name = contact.displayName
                        },
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("...") }
                }
                HorizontalDivider()
            }
        }
    }

    renaming?.let { contact ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear contato") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") })
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(contact.peerId, name)
                    renaming = null
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun AddContactScreen(
    identity: LocalIdentity,
    result: String,
    onImport: (String) -> String,
    modifier: Modifier
) {
    val context = LocalContext.current
    var scannerError by rememberSaveable { mutableStateOf("") }
    val qrCode = remember(identity.publicPayload) { qrCode(identity.publicPayload) }

    ScreenColumn(modifier = modifier) {
        Text("Adicionar contato", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { code -> onImport(code.rawValue.orEmpty()) }
                    .addOnFailureListener { scannerError = "Falha no leitor: ${it.message}" }
            }
        ) {
            Text("Escanear contato")
        }
        Spacer(Modifier.height(20.dp))
        Text("Meu contato", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Image(
            bitmap = qrCode.asImageBitmap(),
            contentDescription = "QR Code do meu contato",
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, identity.publicPayload)
                        },
                        "Compartilhar meu contato"
                    )
                )
            }
        ) {
            Text("Compartilhar meu contato")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val payload = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                onImport(payload)
            }
        ) {
            Text("Colar e adicionar contato")
        }
        Spacer(Modifier.height(12.dp))
        Text(scannerError.ifBlank { result })
    }
}

@Composable
private fun ProfileScreen(
    identity: LocalIdentity,
    modifier: Modifier,
    versionName: String,
    checkingUpdate: Boolean,
    updateStatus: String,
    onCheckUpdate: () -> Unit,
    onSave: (String) -> String,
    onRestored: () -> Unit
) {
    val context = LocalContext.current
    val backupManager = remember { BackupManager(context.applicationContext) }
    val profilePhotos = remember { ProfilePhotoStore(context) }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(identity.displayName) { mutableStateOf(identity.displayName.takeUnless { it == "Eu" }.orEmpty()) }
    var error by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var backupStatus by rememberSaveable { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    var automaticStatus by remember { mutableStateOf(AutomaticBackup.status(context)) }
    var localPhoto by remember { mutableStateOf(profilePhotos.local()) }
    var sharingPhoto by remember { mutableStateOf(profilePhotos.isSharing()) }
    var profileStatus by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            automaticStatus = AutomaticBackup.status(context)
            delay(5_000)
        }
    }

    val chooseProfilePhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { profilePhotos.import(uri) } }
            result.onSuccess {
                localPhoto = it
                profileStatus = "Foto atualizada."
                MessagingRuntime.refreshProfilePhoto()
            }.onFailure {
                profileStatus = it.message ?: "Falha ao preparar a foto."
            }
        }
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val backup = backupManager.export(password)
                    val stream = requireNotNull(context.contentResolver.openOutputStream(uri)) {
                        "Nao foi possivel abrir o arquivo."
                    }
                    stream.bufferedWriter().use { it.write(backup) }
                }
            }
            backupBusy = false
            backupStatus = result.fold(
                { "Backup exportado." },
                { "Falha ao exportar: ${it.message ?: "erro desconhecido"}" }
            )
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "Nao foi possivel abrir o arquivo."
                    }
                    val file = stream.bufferedReader().use { it.readText() }
                    backupManager.restore(file, password)
                }
            }
            backupBusy = false
            result.onSuccess {
                backupStatus = "Restaurado: ${it.contacts} contatos e ${it.messages} mensagens."
                onRestored()
            }.onFailure {
                backupStatus = "Falha ao restaurar: senha ou arquivo invalido."
            }
        }
    }
    val automaticBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            AutomaticBackup.enable(context, uri, password)
        }.onSuccess {
            automaticStatus = AutomaticBackup.status(context)
            backupStatus = "Backup automatico ativado em ${automaticStatus.destination}."
        }.onFailure {
            backupStatus = "Falha ao ativar: ${it.message ?: "erro desconhecido"}"
        }
    }

    ScreenColumn(modifier = modifier) {
        Text("Seu perfil", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(localPhoto, name.ifBlank { identity.displayName }, Modifier.size(88.dp))
            Spacer(Modifier.size(16.dp))
            Column {
                Button(onClick = { chooseProfilePhoto.launch(arrayOf("image/*")) }) {
                    Text(if (localPhoto == null) "Escolher foto" else "Alterar foto")
                }
                if (localPhoto != null) {
                    TextButton(onClick = {
                        profilePhotos.clearLocal()
                        localPhoto = null
                        profileStatus = "Foto removida."
                        MessagingRuntime.refreshProfilePhoto()
                    }) { Text("Remover foto") }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Compartilhar foto")
            Spacer(Modifier.weight(1f))
            Switch(
                checked = sharingPhoto,
                onCheckedChange = {
                    sharingPhoto = it
                    profilePhotos.setSharing(it)
                    MessagingRuntime.refreshProfilePhoto()
                }
            )
        }
        if (profileStatus.isNotBlank()) {
            Text(profileStatus, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Seu nome") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { error = onSave(name) }, enabled = name.isNotBlank()) {
            Text("Salvar")
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        Text("Backup criptografado", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Senha do backup") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { exportBackup.launch("mensageiro-backup.json") },
                enabled = password.length >= 6 && !backupBusy
            ) { Text("Exportar") }
            Button(
                onClick = { importBackup.launch(arrayOf("application/json", "text/plain")) },
                enabled = password.length >= 6 && !backupBusy
            ) { Text("Restaurar") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (automaticStatus.enabled) {
                    AutomaticBackup.disable(context)
                    automaticStatus = AutomaticBackup.status(context)
                    backupStatus = "Backup automatico desativado."
                } else automaticBackup.launch("mensageiro-automatico.json")
            },
            enabled = automaticStatus.enabled || password.length >= 6
        ) {
            Text(
                if (automaticStatus.enabled) "Desativar automatico"
                else "Escolher Google Drive ou arquivo"
            )
        }
        if (automaticStatus.enabled) {
            Spacer(Modifier.height(8.dp))
            Text("Destino: ${automaticStatus.destination}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { automaticBackup.launch("mensageiro-automatico.json") },
                enabled = password.length >= 6
            ) { Text("Alterar Drive ou arquivo") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                AutomaticBackup.runNow(context)
                automaticStatus = AutomaticBackup.status(context)
                backupStatus = "Backup automatico iniciado."
            }) {
                Text("Fazer backup agora")
            }
            Spacer(Modifier.height(8.dp))
            Text("Intervalo minimo: 6 horas", style = MaterialTheme.typography.bodySmall)
            Text(
                "Ultimo backup: " + automaticStatus.lastSuccess.takeIf { it > 0 }
                    ?.let(::backupDateTime).orEmpty().ifBlank { "ainda nao realizado" },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Proximo backup: " + automaticStatus.nextBackup.takeIf { it > 0 }
                    ?.let(::backupDateTime).orEmpty().ifBlank { "aguardando alteracoes" },
                style = MaterialTheme.typography.bodySmall
            )
            if (automaticStatus.error.isNotBlank()) {
                Text(
                    "Ultima falha: ${automaticStatus.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (backupStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(backupStatus)
        }
        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        Text("Atualizacoes", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Versao atual: $versionName")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onCheckUpdate, enabled = !checkingUpdate) {
            Text(if (checkingUpdate) "Verificando..." else "Verificar agora")
        }
        if (updateStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(updateStatus, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun backupDateTime(timestamp: Long): String =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.SHORT
    ).format(Date(timestamp))

private fun qrCode(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 700, 700)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
        else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ConversationScreen(
    contact: VerifiedContact?,
    modifier: Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
    useStatusBarPadding: Boolean = true
) {
    val context = LocalContext.current
    val attachmentStore = remember { AttachmentStore(context) }
    val profilePhotos = remember { ProfilePhotoStore(context) }
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var fileStatus by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<com.mensageiro.core.crypto.StoredMessage?>(null) }
    var snapshot by remember(contact?.peerId) { mutableStateOf(MessagingRuntime.snapshot()) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val listState = rememberLazyListState()
    val listener = remember(contact?.peerId) { MessagingRuntime.Listener { snapshot = it } }
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { attachmentStore.import(uri) } }
            result.onSuccess {
                MessagingRuntime.sendFile(it.messageId, it.attachment)
                fileStatus = ""
            }.onFailure {
                fileStatus = it.message ?: "Falha ao preparar arquivo."
            }
        }
    }
    DisposableEffect(contact?.peerId) {
        contact?.let { MessagingRuntime.selectContact(context, it.peerId) }
        MessagingRuntime.addListener(listener)
        MessagingRuntime.setConversationVisible(contact != null)
        onDispose {
            MessagingRuntime.setConversationVisible(false)
            MessagingRuntime.removeListener(listener)
        }
    }
    val messages = snapshot.messages
    val connected = snapshot.connected
    LaunchedEffect(messages, contact?.peerId) {
        if (contact != null) MessagingRuntime.markRead()
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(contact?.peerId) {
        while (true) {
            clock = System.currentTimeMillis()
            delay(30_000)
        }
    }

    Column(modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        if (contact == null) {
            TextButton(
                onClick = onBack,
                modifier = if (useStatusBarPadding) Modifier.statusBarsPadding() else Modifier
            ) { Text("< Voltar") }
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
                Text(
                    "WebRTC",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bubbleWidth = minOf(560.dp, maxWidth * 0.84f)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = if (maxWidth < 360.dp) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                    Column(Modifier.fillMaxWidth()) {
                        if (index == 0 || !sameDay(messages[index - 1].timestamp, message.timestamp)) {
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
                                Card(
                                    Modifier.widthIn(min = 72.dp, max = bubbleWidth).combinedClickable(
                                        onClick = {},
                                        onLongClick = { deleting = message }
                                    )
                                ) {
                                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                                                    fileStatus = openAttachment(context, attachmentStore, attachment)
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
                            }
                        }
                    }
                }
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
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        ) {
            val compactComposer = maxWidth < 360.dp
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = if (compactComposer) 6.dp else 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { chooseFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("+") }
                Spacer(Modifier.size(if (compactComposer) 4.dp else 8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).widthIn(min = 0.dp),
                    placeholder = { Text("Mensagem") },
                    maxLines = 4
                )
                Spacer(Modifier.size(if (compactComposer) 4.dp else 8.dp))
                Button(
                    onClick = {
                        MessagingRuntime.send(text)
                        text = ""
                    },
                    enabled = text.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) { Text("Enviar") }
            }
        }
    }

    deleting?.let { message ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir mensagem?") },
            text = { Text("Ela sera excluida apenas deste aparelho.") },
            confirmButton = {
                TextButton(onClick = {
                    MessagingRuntime.deleteMessage(message.id)
                    deleting = null
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
}

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

@Composable
private fun ProfileAvatar(photo: StoredAttachment?, name: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, photo?.localPath, photo?.sha256) {
        value = withContext(Dispatchers.IO) { photo?.let { decodePreview(it.localPath) } }
    }
    Box(
        modifier = modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Foto de $name",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun decodePreview(path: String): Bitmap? {
    if (!File(path).isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1_024 || bounds.outHeight / sample > 1_024) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun messageTime(context: Context, timestamp: Long): String =
    DateFormat.getTimeFormat(context).format(Date(timestamp))

private fun openAttachment(
    context: Context,
    store: AttachmentStore,
    attachment: com.mensageiro.core.crypto.StoredAttachment
): String = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(store.uri(attachment), attachment.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}.fold({ "" }, { "Nenhum aplicativo pode abrir este arquivo." })

private fun messageDate(context: Context, timestamp: Long): String =
    DateFormat.getMediumDateFormat(context).format(Date(timestamp))

private fun sameDay(first: Long, second: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

private fun messageStatus(status: MessageStatus): String = when (status) {
    MessageStatus.PENDING -> "Pendente"
    MessageStatus.SENT -> "Enviada"
    MessageStatus.DELIVERED -> "Entregue"
    MessageStatus.READ -> "Vista"
}

private fun contactPresence(preview: ContactPreview, now: Long): String = when {
    preview.active -> "online"
    preview.lastOnline > 0 -> "visto ha ${elapsedTime(now - preview.lastOnline)}"
    else -> "offline"
}

private fun elapsedTime(milliseconds: Long): String {
    val minutes = (milliseconds.coerceAtLeast(0) / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 1_440 -> "${minutes / 60} h"
        else -> "${minutes / 1_440} d"
    }
}

@Composable
private fun ScreenColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.fillMaxHeight()
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (maxWidth < 360.dp) 16.dp else 24.dp, vertical = 24.dp),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content
        )
    }
}

private enum class Screen(val title: String) {
    Contacts("Contatos"),
    AddContact("Adicionar"),
    Conversation("Conversa"),
    Profile("Perfil")
}

@Preview(name = "Compacta", showBackground = true, widthDp = 320, heightDp = 640)
@Preview(name = "Expandida", showBackground = true, widthDp = 1_000, heightDp = 700)
@Composable
fun MensageiroAppPreview() {
    MensageiroTheme {
        MensageiroApp()
    }
}
