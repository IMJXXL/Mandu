package com.mangareader.ui

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.res.Configuration

object LayoutColumnsResolver {
    fun currentValue(orientation: Int, portraitVal: Int, landscapeVal: Int): Int {
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) landscapeVal else portraitVal
    }

    fun toGridCols(value: Int, minWidth: Dp = 96.dp): GridCells {
        return if (value in 1..8) GridCells.Fixed(value) else GridCells.Adaptive(minWidth)
    }
}