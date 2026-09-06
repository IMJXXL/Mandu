package moe.tarsin

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState

/**
 * Extracted from reference project `ContextBridges.kt` (the rest of that file depends on
 * MainActivity / composite-destinations which MangaReader does not use). See
 * `smbbridge/MODIFICATIONS.md`.
 */
context(state: SnackbarHostState)
suspend fun snackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
) = state.showSnackbar(message, actionLabel, withDismissAction, duration)
