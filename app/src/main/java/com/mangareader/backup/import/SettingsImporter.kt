package com.mangareader.backup.`import`

import android.content.Context
import com.mangareader.backup.format.SettingsPayload

object SettingsImporter {
    fun `import`(context: Context, payload: SettingsPayload) {
        val ctx = context.applicationContext
        val mainPrefs = ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)
        val mainEditor = mainPrefs.edit()
        payload.globalSettings.forEach { (k, v) -> mainEditor.putString(k, v) }
        payload.perBookOverrides.forEach { (k, v) ->
            if (k.startsWith("read_mode_") || k.startsWith("orientation_")) {
                mainEditor.putInt(k, v.toInt())
            } else {
                mainEditor.putString(k, v)
            }
        }
        mainEditor.apply()

        val configPrefs = ctx.getSharedPreferences("reader_configs", Context.MODE_PRIVATE)
        val configEditor = configPrefs.edit()
        payload.namedPresets.forEach { (k, v) -> configEditor.putString(k, v) }
        configEditor.apply()
    }
}