package com.example.drawenginelab.core

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Save/open through the system file picker (SAF): no storage permission, user picks the place. */
class NoteFileActions(
    val save: (baseName: String, extension: String, bytes: ByteArray) -> Unit,
    val open: () -> Unit,
)

/** [onOpened] gets the picked file's bytes and display name; failures surface as a toast. */
@Composable
fun rememberNoteFileActions(onOpened: (bytes: ByteArray, name: String) -> Unit): NoteFileActions {
    val context = LocalContext.current
    val opened by rememberUpdatedState(onOpened)
    val pending = remember { arrayOfNulls<ByteArray>(1) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending[0]
        pending[0] = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
            .onSuccess { toast("저장됨") }
            .onFailure { toast("저장 실패: ${it.message}") }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            opened(bytes, uri.lastPathSegment.orEmpty())
        }.onFailure { toast("불러오기 실패: ${it.message}") }
    }
    return remember {
        NoteFileActions(
            save = { base, ext, bytes ->
                pending[0] = bytes
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                saver.launch("${base}_$stamp.$ext")
            },
            open = { opener.launch(arrayOf("*/*")) },
        )
    }
}
