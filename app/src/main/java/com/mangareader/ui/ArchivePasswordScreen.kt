package com.mangareader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hippo.ehviewer.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivePasswordScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    var passwords by remember { mutableStateOf(Settings.archivePasswds.toList().sorted()) }
    var inputText by remember { mutableStateOf("") }

    fun save(newList: List<String>) {
        val filtered = newList.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        Settings.archivePasswds = filtered.toSet()
        passwords = filtered.sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("归档密码") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入密码", fontSize = 14.sp) },
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        if (inputText.trim().isNotEmpty() && inputText.trim() !in passwords) {
                            save(passwords + inputText.trim())
                            inputText = ""
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加")
                }
            }
            Text(
                "加密压缩包打开时自动尝试，命中的优先（本地/SMB/WebDAV 通用）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, bottom = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(passwords, key = { it }) { pwd ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(pwd, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { save(passwords - pwd) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}