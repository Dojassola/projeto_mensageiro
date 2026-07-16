package com.mensageiro.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mensageiro.ui.common.ProfileAvatar
import com.mensageiro.ui.common.contactPresence
import com.mensageiro.core.ContactPreview
import com.mensageiro.core.MessagingRuntime
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.VerifiedContact
import kotlinx.coroutines.delay

@Composable
internal fun ContactsScreen(
    contacts: List<VerifiedContact>,
    profilePhotos: ProfilePhotoStore,
    modifier: Modifier,
    selectedPeerId: String? = null,
    onOpen: (VerifiedContact) -> Unit,
    onProfile: (VerifiedContact) -> Unit
) {
    var revision by remember { mutableStateOf(0) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var serviceStatus by remember { mutableStateOf("Conectando...") }
    val listener = remember {
        MessagingRuntime.Listener { snapshot ->
            serviceStatus = snapshot.serviceStatus
            revision++
        }
    }
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
    val orderedContacts = remember(contacts, previews) {
        contacts.sortedByDescending { previews[it.peerId]?.lastMessage?.timestamp ?: Long.MIN_VALUE }
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item(key = "connection-status") {
            Text(
                serviceStatus,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
        }
        if (contacts.isEmpty()) {
            item { Text("Nenhuma conversa.", modifier = Modifier.padding(vertical = 24.dp)) }
        } else {
            items(orderedContacts, key = { it.peerId }) { contact ->
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
                        onClick = { onProfile(contact) },
                        modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("...") }
                }
                HorizontalDivider()
            }
        }
    }
}
