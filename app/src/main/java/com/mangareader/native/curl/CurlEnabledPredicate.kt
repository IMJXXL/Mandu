package com.mangareader.native.curl

fun isCurlEnabled(
    flipAnimationEnabled: Boolean,
    pageCurlAnimationEnabled: Boolean,
    isVertical: Boolean,
    verticalPager: Boolean,
    doublePageMode: Boolean,
    doublePageReverse: Boolean,
    doublePageSplit: Boolean,
    tileModeEnabled: Boolean,
): Boolean =
    flipAnimationEnabled &&
        pageCurlAnimationEnabled &&
        !isVertical &&
        !verticalPager &&
        !doublePageMode &&
        !doublePageReverse &&
        !doublePageSplit &&
        !tileModeEnabled