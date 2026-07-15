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
internal fun ProfileScreen(
    identity: LocalIdentity,
    backupManager: BackupManager,
    profilePhotos: ProfilePhotoStore,
    contactStore: ContactStore,
    modifier: Modifier,
    versionName: String,
    checkingUpdate: Boolean,
    updateStatus: String,
    onCheckUpdate: () -> Unit,
    onSave: (String) -> String,
    onRestored: () -> Unit
) {
    val context = LocalContext.current
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
    val driveClient = remember(context) { Identity.getAuthorizationClient(context) }
    var pendingDriveAction by remember { mutableStateOf<DriveAction?>(null) }

    val finishDriveAction: (DriveAction, String) -> Unit = { action, accessToken ->
        pendingDriveAction = null
        when (action) {
            DriveAction.Enable -> {
                val result = runCatching {
                    AutomaticBackup.enableDrive(context, password)
                    AutomaticBackup.runNow(context)
                }
                backupBusy = false
                result.onSuccess {
                    automaticStatus = AutomaticBackup.status(context)
                    backupStatus = "Backup automatico ativado no Google Drive."
                }.onFailure {
                    backupStatus = "Falha ao ativar: ${it.message ?: "erro desconhecido"}"
                }
            }
            DriveAction.Restore -> scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = DriveBackupStorage.download(accessToken)
                        backupManager.restore(file, password)
                    }
                }
                backupBusy = false
                result.onSuccess {
                    backupStatus = "Restaurado do Drive: ${it.contacts} contatos e ${it.messages} mensagens."
                    onRestored()
                }.onFailure {
                    backupStatus = "Falha ao restaurar do Drive: ${it.message ?: "erro desconhecido"}"
                }
            }
        }
    }
    val driveAuthorization = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        val action = pendingDriveAction ?: return@rememberLauncherForActivityResult
        if (activityResult.resultCode != Activity.RESULT_OK || activityResult.data == null) {
            pendingDriveAction = null
            backupBusy = false
            backupStatus = "Acesso ao Google Drive cancelado."
            return@rememberLauncherForActivityResult
        }
        runCatching { driveClient.getAuthorizationResultFromIntent(activityResult.data!!) }
            .onSuccess { authorization ->
                val token = authorization.accessToken
                if (token == null) {
                    pendingDriveAction = null
                    backupBusy = false
                    backupStatus = "O Google Drive nao forneceu acesso."
                } else finishDriveAction(action, token)
            }
            .onFailure {
                pendingDriveAction = null
                backupBusy = false
                backupStatus = "Falha no Google Drive: ${it.message ?: "erro desconhecido"}"
            }
    }
    val requestDrive: (DriveAction) -> Unit = { action ->
        pendingDriveAction = action
        backupBusy = true
        driveClient.authorize(DriveBackupStorage.authorizationRequest())
            .addOnSuccessListener { authorization ->
                val resolution = authorization.pendingIntent
                when {
                    authorization.hasResolution() && resolution != null -> driveAuthorization.launch(
                        IntentSenderRequest.Builder(resolution.intentSender).build()
                    )
                    authorization.accessToken != null -> finishDriveAction(action, authorization.accessToken!!)
                    else -> {
                        pendingDriveAction = null
                        backupBusy = false
                        backupStatus = "O Google Drive nao forneceu acesso."
                    }
                }
            }
            .addOnFailureListener {
                pendingDriveAction = null
                backupBusy = false
                backupStatus = "Falha no Google Drive: ${it.message ?: "erro desconhecido"}"
            }
    }
    val enableLocalBackup = {
        val result = runCatching {
            AutomaticBackup.enableLocal(context, password)
            AutomaticBackup.runNow(context)
        }
        result.onSuccess {
            automaticStatus = AutomaticBackup.status(context)
            backupStatus = "Backup automatico ativado em ${automaticStatus.destination}."
        }.onFailure {
            backupStatus = "Falha ao ativar: ${it.message ?: "erro desconhecido"}"
        }
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableLocalBackup()
        else backupStatus = "Permissao de armazenamento negada."
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
            ) { Text("Abrir backup") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Backup automatico", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { requestDrive(DriveAction.Enable) },
                modifier = Modifier.weight(1f),
                enabled = password.length >= 6 && !backupBusy
            ) { Text("Google Drive") }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT <= 28 &&
                        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else enableLocalBackup()
                },
                modifier = Modifier.weight(1f),
                enabled = password.length >= 6 && !backupBusy
            ) { Text("Local") }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { requestDrive(DriveAction.Restore) },
            enabled = password.length >= 6 && !backupBusy
        ) { Text("Restaurar do Drive") }
        if (automaticStatus.enabled) {
            Spacer(Modifier.height(8.dp))
            Text("Destino: ${automaticStatus.destination}", style = MaterialTheme.typography.bodySmall)
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
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                AutomaticBackup.disable(context)
                automaticStatus = AutomaticBackup.status(context)
                backupStatus = "Backup automatico desativado."
            }) { Text("Desativar automatico") }
        }
        if (backupStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(backupStatus)
        }
        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))
        BlockedContactsSection(contactStore)
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


private enum class DriveAction { Enable, Restore }
