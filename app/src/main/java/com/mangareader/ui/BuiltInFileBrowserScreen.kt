package com.mangareader.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StorageUriHelper {

    fun fileToSafTreeUri(file: File): Uri? {
        val abs = file.absolutePath
        val storage = File("/storage")
        val volumes = storage.listFiles()?.toList() ?: emptyList()
        for (vol in volumes) {
            val volPath = vol.absolutePath
            if (abs == volPath) {
                val docId = safVolumeId(vol)
                return Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode(docId)}")
            }
            if (abs.startsWith("$volPath/")) {
                val rel = abs.substring(volPath.length + 1)
                val volId = safVolumeId(vol)
                val docId = "$volId:$rel"
                return Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode(docId)}")
            }
        }
        val emulated = Environment.getExternalStorageDirectory().absolutePath
        if (abs == emulated) {
            return Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode("primary:")}")
        }
        if (abs.startsWith("$emulated/")) {
            val rel = abs.substring(emulated.length + 1)
            val docId = "primary:$rel"
            return Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode(docId)}")
        }
        return null
    }

    private fun safVolumeId(vol: File): String {
        if (vol.name == "emulated") return "primary"
        return vol.name
    }

    fun listStorageVolumes(): List<File> {
        val result = mutableListOf<File>()
        val primary = Environment.getExternalStorageDirectory()
        if (primary.exists()) result.add(primary)
        val storage = File("/storage")
        storage.listFiles()?.forEach { vol ->
            if (vol.name != "emulated" && vol.name != "self" && vol.isDirectory) {
                if (vol.name.contains("-")) {
                    result.add(vol)
                } else if (vol.name != "emulated" && vol.absolutePath != primary.absolutePath) {
                    val sub = vol.listFiles()
                    if (sub != null && sub.isNotEmpty()) {
                        sub.filter { it.isDirectory }.forEach { result.add(it) }
                    }
                }
            }
        }
        return result.distinctBy { it.absolutePath }
    }

    fun hasMultipleStorageVolumes(): Boolean = listStorageVolumes().size > 1
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuiltInFileBrowserScreen(
    onBack: () -> Unit,
    onSelect: (File) -> Unit
) {
    var currentPath by remember {
        mutableStateOf(File("/storage"))
    }
    var dirList by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(currentPath) {
        loading = true
        dirList = withContext(Dispatchers.IO) {
            if (currentPath.absolutePath == "/storage") {
                StorageUriHelper.listStorageVolumes()
            } else {
                currentPath.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }
        }
        loading = false
    }

    BackHandler {
        val parent = currentPath.parentFile
        if (parent != null && parent.absolutePath != "/" && parent.canRead()) {
            currentPath = parent
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentPath.absolutePath, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { onSelect(currentPath) }) {
                        Text("选择此目录")
                    }
                }
            )
        }
    ) { pad ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (dirList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("没有子文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(dirList) { dir ->
                    ListItem(
                        modifier = Modifier.clickable { currentPath = dir },
                        headlineContent = { Text(dir.name) },
                        leadingContent = { Icon(Icons.Default.Folder, null) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}