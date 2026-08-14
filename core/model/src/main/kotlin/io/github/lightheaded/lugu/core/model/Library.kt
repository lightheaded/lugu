package io.github.lightheaded.lugu.core.model

/** A library on an Audiobookshelf server. */
data class Library(
    val id: String,
    val name: String,
    val mediaType: MediaType,
    val displayOrder: Int,
)

enum class MediaType {
    BOOK,
    PODCAST,
    ;

    companion object {
        fun fromWire(value: String?): MediaType =
            if (value.equals("podcast", ignoreCase = true)) PODCAST else BOOK
    }
}
