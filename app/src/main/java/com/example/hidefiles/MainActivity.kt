package com.example.hidefiles

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PickDocs : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private var skipLock = false
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme {
                if (unlocked) {
                    // skipLock يُستخدم فقط عند فتح شاشة اختيار الملفات
                    VaultScreen(beforeLeave = { skipLock = true })
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Button(onClick = { authenticate() }) { Text("فتح الخزنة") }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        skipLock = false
        if (!unlocked) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!skipLock) unlocked = false
    }

    private fun authenticate() {
        if (authenticating) return
        authenticating = true
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    unlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("فتح الخزنة")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
private val VID = setOf("mp4", "mkv", "3gp", "webm", "mov", "avi")
private fun File.isImage() = extension.lowercase() in IMG
private fun File.isVideo() = extension.lowercase() in VID
private fun listVault(dir: File): List<File> =
    dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

// ينسخ الملفات للخزنة ثم يحاول حذف الأصل. يرجع عدد الملفات التي لم يُحذف أصلها.
private fun hideUris(ctx: Context, dir: File, uris: List<Uri>): Int {
    var failed = 0
    for (uri in uris) {
        try {
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            } ?: "file_${System.currentTimeMillis()}"
            val ext = name.substringAfterLast('.', "")
            val stem = if (ext.isEmpty()) name else name.removeSuffix(".$ext")
            var dest = File(dir, name)
            var i = 1
            while (dest.exists()) {
                dest = File(dir, if (ext.isEmpty()) "${stem}_$i" else "${stem}_$i.$ext")
                i++
            }
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            try {
                if (!DocumentsContract.deleteDocument(ctx.contentResolver, uri)) failed++
            } catch (e: Exception) {
                failed++
            }
        } catch (e: Exception) {
            failed++
        }
    }
    return failed
}

private fun restoreFile(ctx: Context, f: File) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { out ->
        f.inputStream().use { it.copyTo(out) }
    }
    f.delete()
}

// للمستندات فقط (غير الصور والفيديو): تُفتح في تطبيق خارجي بمهمة مستقلة
private fun openExternal(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "لا يوجد تطبيق لفتح هذا الملف", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MediaViewer(file: File, loader: ImageLoader, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (file.isVideo()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    VideoView(c).apply {
                        val mc = MediaController(c)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                        setVideoPath(file.absolutePath)
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() }
            )
        } else {
            AsyncImage(
                model = file,
                contentDescription = null,
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
        ) { Text("✕ إغلاق", color = Color.White) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(beforeLeave: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { File(ctx.filesDir, "vault").apply { mkdirs() } }
    var files by remember { mutableStateOf(listVault(dir)) }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val loader = remember {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build()
    }

    val picker = rememberLauncherForActivityResult(PickDocs()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val failed = withContext(Dispatchers.IO) { hideUris(ctx, dir, uris) }
            files = listVault(dir)
            if (failed > 0) {
                Toast.makeText(
                    ctx, "تعذر حذف $failed ملف من مكانه الأصلي، احذفه يدوياً", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val current = viewing
    if (current != null) {
        MediaViewer(current, loader) { viewing = null }
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (selected.isEmpty()) "الخزنة" else "${selected.size} محدد") },
                    actions = {
                        if (selected.isNotEmpty()) {
                            TextButton(onClick = {
                                val items = selected.toList()
                                scope.launch {
                                    withContext(Dispatchers.IO) { items.forEach { restoreFile(ctx, it) } }
                                    selected = emptySet()
                                    files = listVault(dir)
                                    Toast.makeText(ctx, "تمت الاستعادة إلى Downloads", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("استعادة") }
                            TextButton(onClick = { confirmDelete = true }) { Text("حذف") }
                            TextButton(onClick = { selected = emptySet() }) { Text("إلغاء") }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    beforeLeave()
                    picker.launch(arrayOf("*/*"))
                }) { Icon(Icons.Default.Add, contentDescription = "إخفاء ملفات") }
            }
        ) { pad ->
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                    Text("اضغط + لإخفاء ملفات")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    modifier = Modifier.padding(pad),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(files, key = { it.absolutePath }) { f ->
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.DarkGray)
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            selected = if (f in selected) selected - f else selected + f
                                        } else if (f.isImage() || f.isVideo()) {
                                            viewing = f
                                        } else {
                                            openExternal(ctx, f)
                                        }
                                    },
                                    onLongClick = {
                                        selected = if (f in selected) selected - f else selected + f
                                    }
                                )
                        ) {
                            if (f.isImage() || f.isVideo()) {
                                AsyncImage(
                                    model = f,
                                    contentDescription = null,
                                    imageLoader = loader,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    f.name,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 3,
                                    modifier = Modifier.align(Alignment.Center).padding(4.dp)
                                )
                            }
                            if (f.isVideo()) {
                                Icon(
                                    Icons.Default.PlayArrow, null, tint = Color.White,
                                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                                )
                            }
                            if (f in selected) {
                                Box(
                                    Modifier.fillMaxSize().background(Color(0x884488FF)),
                                    contentAlignment = Alignment.Center
                                ) { Text("✓", color = Color.White, fontSize = 28.sp) }
                            }
                        }
                    }
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("حذف نهائي؟") },
                text = { Text("سيتم حذف ${selected.size} ملف نهائياً") },
                confirmButton = {
                    TextButton(onClick = {
                        selected.forEach { it.delete() }
                        selected = emptySet()
                        files = listVault(dir)
                        confirmDelete = false
                    }) { Text("حذف") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
            )
        }
    }
}
