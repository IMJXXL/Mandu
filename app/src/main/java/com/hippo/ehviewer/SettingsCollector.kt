package com.hippo.ehviewer

import com.ehviewer.core.preferences.PrefDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch

private val collectScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

fun <T, R : PrefDelegate<T>> R.observed(func: suspend (T) -> Unit) = apply { collectScope.launch { valueFlow().drop(1).collectLatest(func) } }
fun <T, R : Settings.Delegate<T>> R.emitTo(flow: MutableSharedFlow<Unit>) = apply { collectScope.launch { flow.emitAll(changesFlow()) } }
fun <T, R : PrefDelegate<T>> R.emitTo(flow: MutableSharedFlow<Unit>) = apply { collectScope.launch { flow.emitAll(changesFlow()) } }

suspend fun updateWhenKeepMediaStatusChanges(mediaScan: Boolean) = Unit
suspend fun updateWhenThemeChanges(theme: Int) = Unit
suspend fun updateWhenSaveFileMarkersChanges(enabled: Boolean) = Unit
fun updateWhenStreamKeepAliveUnlimitedChanges(enabled: Boolean) = Unit
fun updateWhenExternalVideoAccessDirChanges(enabled: Boolean) = Unit
fun updateWhenExternalVideoRandomizeTokenChanges(enabled: Boolean) = Unit
fun updateWhenRequestNewsChanges() = Unit
suspend fun updateWhenGallerySiteChanges(gallerySite: Int) = Unit
fun updateWhenTagTranslationChanges(enabled: Boolean) = Unit
