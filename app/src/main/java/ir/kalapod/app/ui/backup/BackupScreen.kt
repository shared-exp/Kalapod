package ir.kalapod.app.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.kalapod.app.MainActivity
import ir.kalapod.app.backup.AutoBackupWorker
import ir.kalapod.app.backup.BackupCrypto
import ir.kalapod.app.backup.BackupInfo
import ir.kalapod.app.backup.BackupManager
import ir.kalapod.app.backup.BackupPrefs
import ir.kalapod.app.backup.BackupSettings
import ir.kalapod.app.ui.common.formatDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface BackupBusy {
    data object Idle : BackupBusy
    data class Working(val what: String) : BackupBusy
    data class Done(val message: String) : BackupBusy
    data class Failed(val message: String) : BackupBusy
    /** Backup decrypted and checked; waiting for the user to confirm overwriting current data. */
    data class ConfirmRestore(val info: BackupInfo, val staged: File) : BackupBusy
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val manager: BackupManager,
    private val prefs: BackupPrefs,
) : ViewModel() {
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, BackupSettings())
    private val _busy = MutableStateFlow<BackupBusy>(BackupBusy.Idle)
    val busy = _busy.asStateFlow()

    fun setPassword(pw: String) = viewModelScope.launch { prefs.savePassword(pw.toCharArray()) }

    fun backupTo(uri: Uri) = viewModelScope.launch {
        val pw = prefs.password() ?: run { _busy.value = BackupBusy.Failed("ابتدا رمز پشتیبان را تعیین کنید."); return@launch }
        _busy.value = BackupBusy.Working("در حال تهیه نسخه پشتیبان…")
        _busy.value = runCatching {
            app.contentResolver.openOutputStream(uri)!!.use { manager.create(it, pw) }
            prefs.recordResult(System.currentTimeMillis(), null)
        }.fold({ BackupBusy.Done("نسخه پشتیبان ذخیره شد.") }, { BackupBusy.Failed("ذخیره نشد: ${it.message}") })
    }

    fun inspect(uri: Uri, password: String) = viewModelScope.launch {
        _busy.value = BackupBusy.Working("در حال بررسی فایل…")
        _busy.value = runCatching {
            app.contentResolver.openInputStream(uri)!!.use { manager.inspect(it, password.toCharArray()) }
        }.fold({ BackupBusy.ConfirmRestore(it.first, it.second) }, {
            BackupBusy.Failed(
                when (it) {
                    is BackupCrypto.WrongPasswordException, is BackupCrypto.NotABackupException -> it.message.orEmpty()
                    else -> it.message ?: "فایل قابل بازیابی نیست."
                },
            )
        })
    }

    fun restore(staged: File) = viewModelScope.launch {
        _busy.value = BackupBusy.Working("در حال بازیابی…")
        manager.restore(staged)
        // The database was swapped under the running app: restart cleanly.
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    fun setAuto(enabled: Boolean, folder: Uri?) = viewModelScope.launch {
        if (folder != null) {
            app.contentResolver.takePersistableUriPermission(
                folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.setAuto(enabled, folder?.toString())
        AutoBackupWorker.schedule(app, enabled)
    }

    fun dismiss() { _busy.value = BackupBusy.Idle }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var askPassword by remember { mutableStateOf(false) }
    var afterPassword by remember { mutableStateOf<(() -> Unit)?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(vm::backupTo)
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setAuto(true, uri)
    }
    fun withPassword(action: () -> Unit) { if (s.hasPassword) action() else { afterPassword = action; askPassword = true } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("پشتیبان‌گیری") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "بازگشت") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "همه اطلاعات فروشگاه فقط روی همین گوشی است. اگر گوشی گم یا خراب شود، بدون نسخه پشتیبان اطلاعات از بین می‌رود.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                s.lastBackupAt?.let { "آخرین پشتیبان: " + formatDateTime(it) } ?: "هنوز پشتیبانی تهیه نشده است.",
                color = if (s.lastBackupAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
            s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = { withPassword { createDoc.launch(AutoBackupWorker.fileName()) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("تهیه نسخه پشتیبان", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(onClick = { openDoc.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("بازیابی از فایل پشتیبان")
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("پشتیبان خودکار روزانه", style = MaterialTheme.typography.titleMedium)
                        Text("هر روز در پوشه‌ای که انتخاب کنید؛ ۷ نسخه آخر نگه داشته می‌شود.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(s.autoEnabled, onCheckedChange = { on ->
                        if (on) withPassword { pickFolder.launch(null) } else vm.setAuto(false, null)
                    })
                }
            }
            if (s.hasPassword) {
                TextButton(onClick = { afterPassword = null; askPassword = true }) { Text("تغییر رمز پشتیبان") }
            }
        }
    }

    if (askPassword) {
        PasswordDialog(
            title = "رمز پشتیبان",
            explain = "پشتیبان‌ها با این رمز قفل می‌شوند. آن را جایی یادداشت کنید؛ بدون این رمز بازیابی ممکن نیست.",
            confirm = true,
            onDone = { pw -> vm.setPassword(pw); askPassword = false; afterPassword?.invoke(); afterPassword = null },
            onDismiss = { askPassword = false; afterPassword = null },
        )
    }
    restoreUri?.let { uri ->
        PasswordDialog(
            title = "رمز فایل پشتیبان", explain = "رمزی را که هنگام تهیه این پشتیبان تعیین کرده‌اید وارد کنید.", confirm = false,
            onDone = { pw -> restoreUri = null; vm.inspect(uri, pw) }, onDismiss = { restoreUri = null },
        )
    }
    when (val b = busy) {
        is BackupBusy.Working -> AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(b.what) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
        is BackupBusy.Done -> AlertDialog(onDismissRequest = vm::dismiss, confirmButton = { TextButton(onClick = vm::dismiss) { Text("باشه") } }, text = { Text(b.message) })
        is BackupBusy.Failed -> AlertDialog(
            onDismissRequest = vm::dismiss, confirmButton = { TextButton(onClick = vm::dismiss) { Text("باشه") } },
            text = { Text(b.message, color = MaterialTheme.colorScheme.error) },
        )
        is BackupBusy.ConfirmRestore -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("جایگزینی اطلاعات؟") },
            text = {
                Text(
                    "پشتیبان «${b.info.shopName.ifBlank { "فروشگاه" }}» از ${formatDateTime(b.info.createdAt)} سالم است.\n" +
                        "با بازیابی، همه اطلاعات فعلی این گوشی با آن جایگزین می‌شود و برنامه دوباره باز می‌شود.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.restore(b.staged) }) { Text("بازیابی", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("انصراف") } },
        )
        BackupBusy.Idle -> Unit
    }
}

@Composable
private fun PasswordDialog(title: String, explain: String, confirm: Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explain, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pw, { pw = it; err = null }, label = { Text("رمز") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirm) {
                    OutlinedTextField(
                        pw2, { pw2 = it; err = null }, label = { Text("تکرار رمز") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = when {
                    pw.length < BackupCrypto.MIN_PASSWORD -> "رمز باید دست‌کم ${BackupCrypto.MIN_PASSWORD} حرف باشد."
                    confirm && pw != pw2 -> "دو رمز یکسان نیستند."
                    else -> null
                }
                if (err == null) onDone(pw)
            }) { Text("تأیید") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
