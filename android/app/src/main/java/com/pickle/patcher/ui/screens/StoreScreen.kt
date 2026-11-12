package com.pickle.patcher.ui.screens
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.ui.MarkdownText
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    api: StoreApi,
    onUserClick: (String) -> Unit = {},
    onUploadClick: () -> Unit = {},
) {
    val context = LocalContext.current
    var signedIn by remember { mutableStateOf(com.pickle.patcher.data.StoreSession.token(context) != null) }
    var authMode by remember { mutableStateOf("login") }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    if (!signedIn) {
        when (authMode) {
            "register" -> StoreRegisterScreen(
                api = api,
                onRegisterSuccess = { email -> authMode = "login" },
                onBack = { authMode = "login" },
            )
            else -> StoreLoginScreen(
                api = api,
                onLoginSuccess = { signedIn = true },
                onRegister = { authMode = "register" },
            )
        }
        return
    }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var detailItem by remember { mutableStateOf<JSONObject?>(null) }
    var showComments by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf<JSONArray?>(null) }
    var commentText by remember { mutableStateOf("") }
    fun load(isRefresh: Boolean = false) {
        scope.launch {
            if (isRefresh) refreshing = true else loading = true
            error = null
            api.listItems(1).fold(
                onSuccess = { json ->
                    items = json.optJSONArray("data")
                    loading = false
                    refreshing = false
                },
                onFailure = { t ->
                    error = t.message ?: "Failed to load store"
                    loading = false
                    refreshing = false
                },
            )
        }
    }
    LaunchedEffect(signedIn) { if (signedIn) load() }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Store",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Community mods and game assets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                onClick = onUploadClick,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Upload",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { showLogoutConfirm = true },
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Sign out",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { load(true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    val list = items
                    if (list == null || list.length() == 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No items yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val feed = remember(list) { (0 until list.length()).map { list.getJSONObject(it) } }
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(feed, key = { it.optString("id") }) { obj ->
                                StoreItemCard(
                                    obj = obj,
                                    onUserClick = onUserClick,
                                    onOpenDetail = { detailItem = obj },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Sign out?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("You can sign back in anytime.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { api.logout() }
                    com.pickle.patcher.data.StoreSession.clear(context)
                    api.token = null
                    items = null
                    loading = true
                    error = null
                    comments = null
                    commentText = ""
                    detailItem = null
                    showComments = false
                    signedIn = false
                    showLogoutConfirm = false
                }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
    fun postComment() {
        val obj = detailItem ?: return
        if (obj.optString("status", "approved") != "approved") {
            android.widget.Toast.makeText(context, "Wait for this post to be approved", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val token = com.pickle.patcher.data.StoreSession.token(context) ?: api.token
        if (token == null) { android.widget.Toast.makeText(context, "Log in to comment", android.widget.Toast.LENGTH_SHORT).show(); return }
        if (!com.pickle.patcher.data.StoreSession.emailVerified(context)) { android.widget.Toast.makeText(context, "Verify your email to comment", android.widget.Toast.LENGTH_SHORT).show(); return }
        if (commentText.isBlank()) return
        val trimmed = commentText.trim()
        scope.launch {
            api.postComment(token, obj.optString("id"), trimmed).onSuccess {
                commentText = ""
                api.getComments(obj.optString("id")).onSuccess { res -> comments = res }
                obj.put("commentsCount", obj.optInt("commentsCount", 0) + 1)
                detailItem = detailItem?.let { JSONObject(it.toString()) }
            }.onFailure {
                android.widget.Toast.makeText(context, "Couldn't post comment", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    detailItem?.let { obj ->
        val libs = obj.optJSONArray("libs")
        val libCount = libs?.length() ?: 0
        val links = obj.optJSONArray("links")
        val detailUsername = obj.optJSONObject("user")?.optString("username", "anon") ?: "anon"
        val detailLogo = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
        val approved = obj.optString("status", "approved") == "approved"
        AlertDialog(
            onDismissRequest = { detailItem = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (detailLogo != null) {
                        AsyncImage(
                            model = detailLogo,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Text(
                                    obj.optString("title", "?").take(1).uppercase(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            obj.optString("title", "Untitled"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "v${obj.optString("version", "1.0.0")}  ·  by $detailUsername",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!approved) {
                            Spacer(Modifier.height(6.dp))
                            StatusPill("Pending review", tone = Tone.Tertiary)
                        }
                    }
                }
            },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    val desc = obj.optString("description", "")
                    if (desc.isNotBlank()) {
                        MarkdownText(
                            desc,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (libCount > 0) {
                        DialogCaption("Files ($libCount)")
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            (0 until libCount).map { libs!!.getJSONObject(it) }.forEach { l ->
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                cleanStoreFileName(l.optString("name")),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                "${formatFileSize(l.optLong("size", 0))}  ·  ${l.optInt("downloads", 0)} downloads",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (approved) {
                                        IconButton(onClick = {
                                            if (com.pickle.patcher.data.StoreSession.emailVerified(context)) {
                                                downloadLib(context, l.optString("url"), cleanStoreFileName(l.optString("name")))
                                                scope.launch {
                                                    api.incrementLibDownload(obj.optString("id"), l.optString("id")).onSuccess { count ->
                                                        l.put("downloads", count)
                                                        detailItem = detailItem?.let { JSONObject(it.toString()) }
                                                    }
                                                }
                                            } else {
                                                android.widget.Toast.makeText(context, "Verify your email to download", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }) {
                                            Icon(
                                                Icons.Filled.Download,
                                                contentDescription = "Download ${cleanStoreFileName(l.optString("name"))}",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (links != null && links.length() > 0) {
                        DialogCaption("Links")
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            (0 until links.length()).map { links.getJSONObject(it) }.forEach { lk ->
                                val url = lk.optString("url")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        lk.optString("label", url).removePrefix("https://").removePrefix("http://"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (approved) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val liked = obj.optBoolean("liked", false)
                        val likes = obj.optInt("likesCount", 0)
                        Surface(
                            shape = CircleShape,
                            color = if (liked) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                        ) {
                            IconButton(onClick = {
                                val token = com.pickle.patcher.data.StoreSession.token(context) ?: api.token
                                if (token == null) {
                                    android.widget.Toast.makeText(context, "Log in to like", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    scope.launch {
                                        api.toggleLike(token, obj.optString("id")).onSuccess { (newLiked, newCount) ->
                                            obj.put("liked", newLiked)
                                            obj.put("likesCount", newCount)
                                            items?.let { arr ->
                                                for (i in 0 until arr.length()) {
                                                    val it = arr.getJSONObject(i)
                                                    if (it.optString("id") == obj.optString("id")) {
                                                        it.put("liked", newLiked)
                                                        it.put("likesCount", newCount)
                                                    }
                                                }
                                                items = org.json.JSONArray(arr.toString())
                                            }
                                            detailItem = detailItem?.let { JSONObject(it.toString()) }
                                        }
                                    }
                                }
                            }) {
                                Icon(
                                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (liked) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$likes",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(20.dp))
                        TextButton(onClick = {
                            showComments = true
                            scope.launch {
                                api.getComments(obj.optString("id")).onSuccess { res ->
                                    comments = res
                                }
                            }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Comment,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Comments (${obj.optInt("commentsCount", 0)})",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.HourglassEmpty,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Likes, comments and downloads unlock after this post is approved",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailItem = null }) {
                    Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
    if (showComments && detailItem != null) {
        val obj = detailItem!!
        val commentToken = com.pickle.patcher.data.StoreSession.token(context) ?: api.token
        val canComment = commentToken != null && com.pickle.patcher.data.StoreSession.emailVerified(context) && commentText.isNotBlank()
        AlertDialog(
            onDismissRequest = { showComments = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Comments",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            "${obj.optInt("commentsCount", 0)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            },
            text = {
                Column {
                    Box(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                        val list = comments
                        when {
                            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                            list.length() == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No comments yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                                (0 until list.length()).map { list.getJSONObject(it) }.forEach { c ->
                                    val u = c.optJSONObject("user")
                                    val uname = u?.optString("username") ?: "?"
                                    val avatar = u?.optString("avatarUrl")
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        if (!avatar.isNullOrBlank()) {
                                            AsyncImage(
                                                model = avatar,
                                                contentDescription = null,
                                                modifier = Modifier.size(30.dp).clip(CircleShape),
                                            )
                                        } else {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.size(30.dp),
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                    Text(
                                                        uname.take(1).uppercase(),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    uname,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                BadgeIcons(u?.optJSONArray("badges"), maxCount = 3, iconSize = 14.dp)
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                c.optString("content"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.padding(start = 40.dp),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        label = { Text("Write a comment") },
                        trailingIcon = {
                            IconButton(enabled = canComment, onClick = { postComment() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Post comment",
                                    tint = if (canComment) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        },
                        minLines = 1,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showComments = false }) {
                    Text("Done", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}
@Composable
private fun StoreItemCard(obj: JSONObject, onUserClick: (String) -> Unit, onOpenDetail: () -> Unit) {
    val title = obj.optString("title", "Untitled")
    val desc = obj.optString("description", "").trim()
    val version = obj.optString("version", "1.0.0")
    val status = obj.optString("status", "approved")
    val libs = obj.optJSONArray("libs")
    val fileCount = libs?.length() ?: 0
    val downloadSum = (0 until fileCount).sumOf { libs!!.getJSONObject(it).optInt("downloads", 0) }
    val liked = obj.optBoolean("liked", false)
    val likes = obj.optInt("likesCount", 0)
    val comments = obj.optInt("commentsCount", 0)
    val userObj = obj.optJSONObject("user")
    val username = userObj?.optString("username", "anon") ?: "anon"

    Surface(
        onClick = onOpenDetail,
        shape = getGroupedItemShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val logoUrl = obj.optString("logoUrl", "").takeIf { it.isNotBlank() }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.large),
            ) {
                if (logoUrl != null) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            title.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (status != "approved") {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(
                            status.replaceFirstChar { it.titlecase() },
                            tone = Tone.Tertiary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v$version",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  ·  by $username",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    BadgeIcons(userObj?.optJSONArray("badges"), maxCount = 4, iconSize = 14.dp)
                }
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status == "approved") {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (liked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(likes.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.Comment,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(comments.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(downloadSum.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
private fun downloadLib(context: Context, url: String, name: String) {
    if (url.isBlank()) {
        Toast.makeText(context, "No download URL", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        dm.enqueue(req)
        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
@Composable
private fun DialogCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}



