package com.mangareader.native.curl

fun isCurlEnabled(
    flipAnimationEnabled: Boolean,
    pageCurlAnimationEnabled: Boolean,
    isVertical: Boolean,
    verticalPager: Boolean,
    doublePageSplit: Boolean,
    tileModeEnabled: Boolean,
): Boolean =
    flipAnimationEnabled &&
        pageCurlAnimationEnabled &&
        !isVertical &&
        !verticalPager &&
        !doublePageSplit &&
        !tileModeEnabled
