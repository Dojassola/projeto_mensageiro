package com.mensageiro.ui.contacts

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.mensageiro.ScreenColumn
import com.mensageiro.core.crypto.LocalIdentity
import com.mensageiro.qrCode

@Composable
internal fun AddContactScreen(
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
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)
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
