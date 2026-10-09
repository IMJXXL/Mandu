package com.mangareader.ui

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.mangareader.data.ApplyResult
import com.mangareader.data.Book
import com.mangareader.data.PerBookConfigApplier
import com.mangareader.data.ReaderConfig
import com.mangareader.data.ReaderScope
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BookSettingsSheetHost(
    book: Book,
    engine: ArchiveEngine,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE) }

    val initialConfig = remember(book.id) { ReaderConfig.load(ctx, book.id) }
    val initialModeOrdinal = remember(book.id) { ReaderScope.readModeOrdinal(prefs, book.id) }
    val initialMode = remember(book.id) {
        if (initialModeOrdinal in Mode.entries.indices) Mode.entries[initialModeOrdinal]
        else when (initialConfig.scrollMode) {
            ReaderConfig.ScrollMode.VERTICAL -> Mode.VERT
            ReaderConfig.ScrollMode.HORIZONTAL_LEFT -> Mode.FLIP
            ReaderConfig.ScrollMode.HORIZONTAL_RIGHT -> Mode.RTL
        }
    }

    var bufferedConfig by remember(book.id) { mutableStateOf(initialConfig) }
    var bufferedMode by remember(book.id) { mutableStateOf(initialMode) }
    var applying by remember { mutableStateOf(false) }

    ReaderSettingsSheet(
        config = bufferedConfig,
        mode = bufferedMode,
        bookId = book.id,
        onConfigChange = { bufferedConfig = it },
        onModeChange = { bufferedMode = it },
        onOrientationChange = { bufferedConfig = bufferedConfig.copy(orientation = it) },
        onApply = {
            if (applying) return@ReaderSettingsSheet
            applying = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    PerBookConfigApplier.apply(ctx, book, engine, initialConfig, bufferedConfig)
                }
                when (result) {
                    is ApplyResult.Success -> {
                        val modeChanged = bufferedMode != initialMode
                        if (modeChanged) {
                            withContext(Dispatchers.IO) {
                                ReaderScope.write(prefs, book.id, ReaderScope.READ_MODE_KEY, bufferedMode.ordinal)
                            }
                        }
                        Toast.makeText(ctx, "已应用", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    is ApplyResult.PartialFailure -> {
                        Toast.makeText(ctx, "部分成功：${result.succeeded} 成功，${result.failed} 失败", Toast.LENGTH_LONG).show()
                    }
                    is ApplyResult.Failed -> {
                        Toast.makeText(ctx, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
                applying = false
            }
        },
        onClearOverrides = {
            scope.launch {
                withContext(Dispatchers.IO) { PerBookConfigApplier.clearOverrides(ctx, book) }
                Toast.makeText(ctx, "已清除单本覆盖", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}