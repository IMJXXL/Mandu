package tachiyomi.decoder

data class ImageType internal constructor(
    val format: Format,
    val isAnimated: Boolean,
) {
    internal constructor(format: Int, isAnimated: Boolean) : this(Format.from(format), isAnimated)
}

enum class Format {
    Jpeg, Png, Webp, Gif, Heif, Avif, Jxl;

    internal companion object {
        fun from(value: Int): Format = when (value) {
            0 -> Jpeg
            1 -> Png
            2 -> Webp
            3 -> Gif
            4 -> Heif
            5 -> Avif
            6 -> Jxl
            else -> error("Invalid format code")
        }
    }
}