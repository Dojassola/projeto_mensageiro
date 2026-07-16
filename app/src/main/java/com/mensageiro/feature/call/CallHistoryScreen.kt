package com.mensageiro.feature.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mensageiro.core.CallDirection
import com.mensageiro.core.CallEndedBy
import com.mensageiro.core.CallHistoryStore
import com.mensageiro.core.CallRecord
import com.mensageiro.core.CallResult
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.crypto.VerifiedContact
import com.mensageiro.ui.common.ProfileAvatar
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CallHistoryScreen(
    store: CallHistoryStore,
    contacts: List<VerifiedContact>,
    profilePhotos: ProfilePhotoStore,
    modifier: Modifier,
    onOpenContact: (VerifiedContact) -> Unit
) {
    val records = remember { store.all() }
    if (records.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhuma chamada", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(records, key = CallRecord::callId) { record ->
            val contact = contacts.firstOrNull { it.peerId == record.peerId }
            val name = contact?.displayName ?: "Contato removido"
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = contact != null) { contact?.let(onOpenContact) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProfileAvatar(profilePhotos.remote(record.peerId), name, Modifier.size(48.dp))
                Text(
                    if (record.direction == CallDirection.OUTGOING) "↗" else "↙",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (record.result == CallResult.MISSED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        callDescription(record, name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(record.startedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End
                    )
                    Text(
                        formatDuration(record.duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

private fun callDescription(record: CallRecord, contactName: String): String {
    val direction = if (record.direction == CallDirection.OUTGOING) "Voce chamou" else "$contactName chamou"
    val result = when (record.result) {
        CallResult.COMPLETED -> when (record.endedBy) {
            CallEndedBy.ME -> "voce desligou"
            CallEndedBy.CONTACT -> "$contactName desligou"
            else -> "encerrada"
        }
        CallResult.MISSED -> "perdida"
        CallResult.DECLINED -> "recusada"
        CallResult.CANCELED -> "cancelada"
        CallResult.NO_ANSWER -> "sem resposta"
        CallResult.BUSY -> "ocupado"
        CallResult.INTERRUPTED -> "interrompida"
        null -> "em andamento"
    }
    return "$direction · $result"
}

private fun formatDuration(duration: Long): String {
    if (duration <= 0) return "--"
    val seconds = duration / 1_000
    val hours = seconds / 3_600
    val minutes = seconds / 60 % 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%02d:%02d".format(minutes, remainingSeconds)
}
