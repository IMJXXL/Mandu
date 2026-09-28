package com.mangareader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.data.Bookshelf

sealed class MergeTarget {
    data class Existing(val shelfId: Long) : MergeTarget()
    data class New(val name: String) : MergeTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeTargetPickerDialog(
    allShelves: List<Bookshelf>,
    excludeIds: Set<Long>,
    onConfirm: (MergeTarget) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedExisting by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val candidateShelves = allShelves.filter { it.id !in excludeIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择合并目标") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("已有书架", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                candidateShelves.forEach { shelf ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedExisting = shelf.id; errorMsg = null }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedExisting == shelf.id, onClick = { selectedExisting = shelf.id; errorMsg = null })
                        Spacer(Modifier.width(8.dp))
                        Text(shelf.name, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("新建书架", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; selectedExisting = null; errorMsg = null },
                    label = { Text("书架名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) {
                    if (allShelves.any { it.name == newName.trim() }) {
                        errorMsg = "书架已存在"
                    } else {
                        onConfirm(MergeTarget.New(newName.trim()))
                    }
                } else if (selectedExisting != null) {
                    onConfirm(MergeTarget.Existing(selectedExisting!!))
                } else {
                    errorMsg = "须选择目标"
                }
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}