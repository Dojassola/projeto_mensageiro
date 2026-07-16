package com.mensageiro.app

import android.content.Context
import com.mensageiro.core.crypto.AttachmentStore
import com.mensageiro.core.crypto.BackupManager
import com.mensageiro.core.crypto.ContactStore
import com.mensageiro.core.crypto.IdentityStore
import com.mensageiro.core.crypto.MessageStore
import com.mensageiro.core.crypto.ProfilePhotoStore
import com.mensageiro.core.CallHistoryStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val identityStore = IdentityStore(appContext)
    val contactStore = ContactStore(appContext)
    val messageStore by lazy { MessageStore(appContext, identityStore) }
    val attachmentStore = AttachmentStore(appContext)
    val profilePhotoStore = ProfilePhotoStore(appContext)
    val callHistoryStore = CallHistoryStore(appContext, identityStore)
    val backupManager by lazy {
        BackupManager(
            appContext,
            identityStore,
            contactStore,
            messageStore,
            profilePhotoStore
        )
    }
}
