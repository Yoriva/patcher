package com.pickle.patcher.ui.screens
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.data.StoreSession
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
@Composable
fun StoreUploadScreen(
    api: StoreApi,
    onUploadSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0.0") }
    var logoUri by remember { mutableStateOf<Uri?>(null) }
    var libEntries by remember { mutableStateOf<List<Triple<Uri, String, String>>>(emptyList()) }
    var links by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
    var linkInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableStateOf<String?>(null) }
    var dupMsg by remember { mutableStateOf<String?>(null) }
    val assetRows = if (libEntries.isEmpty()) 2 else 3
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) logoUri = uri
    }
    val libPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val existing = libEntries.map { it.second }.toMutableSet()
                val added = mutableListOf<Triple<Uri, String, String>>()
                var skipped = 0
                for (u in uris) {
                    val h = hashUri(context, u)
                    if (h == null || h in existing) { skipped++; continue }
                    existing.add(h)
                    val name = cleanStoreFileName(displayName(context, u))
                    added.add(Triple(u, h, name))
                }
                if (added.isNotEmpty()) libEntries = libEntries + added
                dupMsg = if (skipped > 0) "$skipped duplicate file(s) skipped" else null
            }
        }
    }
    fun uriToFile(uri: Uri, prefix: String, nameOverride: String? = null): File? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            val name = nameOverride ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "$prefix.bin")
            val file = File(context.cacheDir, name)
            input.use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
            file
        } catch (_: Exception) { null }
    }
    fun submit() {
        val token = StoreSession.token(context) ?: api.token
        if (token == null) { error = "Not logged in"; return }
        if (!StoreSession.emailVerified(context)) { error = "Verify your email to upload"; return }
        loading = true; error = null; cooldown = null
        scope.launch {
            val me = api.me()
            val upload = me.getOrNull()?.optJSONObject("upload")
            val allowed = upload?.optBoolean("allowed", true) ?: true
            if (!allowed) {
                val next = upload?.optString("nextAllowedAt")
                cooldown = "Upload cooldown active" + (if (!next.isNullOrBlank()) " (next: $next)" else "")
                loading = false
                return@launch
            }
            progress = 0f
            var logoUrl: String? = null
            var uploadedLogoUrl: String? = null
            if (logoUri != null) {
                val f = uriToFile(logoUri!!, "logo")
                if (f != null) {
                    progressLabel = "Uploading logo..."
                    val r = api.uploadFile(f) { w, t -> progress = if (t > 0) w.toFloat() / t else 0f }
                    if (r.isFailure) { error = r.exceptionOrNull()?.message ?: "Logo upload failed"; loading = false; return@launch }
                    logoUrl = r.getOrNull()?.url
                    uploadedLogoUrl = logoUrl
                }
            }
            val libUploads = mutableListOf<StoreApi.LibUpload>()
            val totalLibs = libEntries.size
            libEntries.forEachIndexed { idx, (uri, _, name) ->
                val f = uriToFile(uri, "file", name)
                if (f != null) {
                    progressLabel = "Uploading file ${idx + 1}/$totalLibs: ${f.name}"
                    progress = 0f
                    val r = api.uploadFile(f) { w, t -> progress = if (t > 0) w.toFloat() / t else 0f }
                    if (r.isFailure) {
                        error = r.exceptionOrNull()?.message ?: "File upload failed"
                        loading = false
                        (libUploads.map { it.url } + listOfNotNull(uploadedLogoUrl)).forEach { url -> api.deleteStorageFile(token, url) }
                        return@launch
                    }
                    val u = r.getOrNull()!!
                    libUploads.add(StoreApi.LibUpload(cleanStoreFileName(f.name), u.url, u.bytes))
                }
            }
            api.createItem(token, title.trim(), description.trim(), version.trim().ifBlank { "1.0.0" }, logoUrl, libUploads, links).fold(
                onSuccess = { loading = false; Toast.makeText(context, "Submitted for approval", Toast.LENGTH_SHORT).show(); onUploadSuccess() },
                onFailure = { t ->
                    android.util.Log.e("StoreUpload", "upload failed", t)
                    val msg = (t.message ?: t.toString()).let { if (it.isBlank()) "Upload failed" else it }
                    if (msg.contains("cooldown", ignoreCase = true)) cooldown = msg
                    else error = msg
                    loading = false
                    (libUploads.map { it.url } + listOfNotNull(uploadedLogoUrl)).forEach { url -> api.deleteStorageFile(token, url) }
                },
            )
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column {
                Card(
                    shape = getGroupedItemShape(0, 1),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it; error = null },
                            label = { Text("Title") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = version,
                            onValueChange = { version = it },
                            label = { Text("Version") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Card(
                    shape = getGroupedItemShape(0, assetRows),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Logo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = logoUri?.lastPathSegment ?: "No logo selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        SmallTonalPill(onClick = { logoPicker.launch(arrayOf("image/*")) }) {
                            Text(if (logoUri == null) "Pick" else "Change", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
                Card(
                    shape = getGroupedItemShape(1, assetRows),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Files",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${libEntries.size} file(s) selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        SmallTonalPill(onClick = { libPicker.launch(arrayOf("*/*")) }) {
                            Text("Add files", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                }
                if (libEntries.isNotEmpty()) {
                    Card(
                        shape = getGroupedItemShape(2, assetRows),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            libEntries.forEachIndexed { index, (uri, _, name) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { libEntries = libEntries.filterIndexed { i, _ -> i != index } },
                                        modifier = Modifier.size(34.dp),
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove $name",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                dupMsg?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp, top = 2.dp),
                    )
                }
            }
        }
        item {
            Column {
                var linkError by remember { mutableStateOf<String?>(null) }
                fun addLink(): Boolean {
                    val normalized = normalizeProjectLink(linkInput)
                    if (normalized == null) {
                        linkError = "Enter a valid link, e.g. github.com/you/repo"
                        return false
                    }
                    links = links + Pair(null, normalized)
                    linkInput = ""
                    linkError = null
                    return true
                }
                Card(
                    shape = getGroupedItemShape(0, 1),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it; linkError = null },
                        label = { Text("Project link") },
                        placeholder = { Text("github.com/you/repo") },
                        leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { addLink() }, enabled = linkInput.isNotBlank()) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add link",
                                    tint = if (linkInput.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { addLink() }),
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                    )
                }
                linkError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 10.dp, top = 4.dp),
                    )
                }
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        shape = getGroupedItemShape(0, 1),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            links.forEachIndexed { idx, (_, url) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = url.removePrefix("https://").removePrefix("http://"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { links = links.filterIndexed { i, _ -> i != idx } },
                                        modifier = Modifier.size(34.dp),
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove link",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                if (idx != links.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(start = 44.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Column {
                cooldown?.let {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                error?.let {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (loading) {
                    Text(
                        text = progressLabel.ifBlank { "Working..." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                Button(
                    onClick = { submit() },
                    enabled = !loading && title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Upload package",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
@Composable
private fun SmallTonalPill(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        modifier = Modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        content()
    }
}
private fun normalizeProjectLink(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return try {
        val uri = android.net.Uri.parse(withScheme)
        val host = uri.host
        if (host.isNullOrBlank() || !host.contains('.') || host.contains(" ")) null else uri.toString()
    } catch (_: Exception) { null }
}
private fun displayName(context: Context, uri: Uri): String {
    val fromColumn = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
    val fromPath = uri.lastPathSegment?.substringAfterLast('/')
    val candidates = listOfNotNull(fromColumn, fromPath)
    val withExt = candidates.firstOrNull { it.contains('.') }
    return (withExt ?: candidates.firstOrNull() ?: "file.bin")
}
private suspend fun hashUri(context: Context, uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val md = MessageDigest.getInstance("SHA-256")
        input.use { ins ->
            val buf = ByteArray(8192)
            var n: Int
            while (ins.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }
}

