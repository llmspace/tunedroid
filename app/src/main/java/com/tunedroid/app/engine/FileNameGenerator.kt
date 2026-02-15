package com.tunedroid.app.engine

object FileNameGenerator {

    private val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|]")
    private const val MAX_LENGTH = 80

    fun generate(title: String, author: String, extension: String): String {
        val sanitizedTitle = sanitize(title)
        val sanitizedAuthor = sanitize(author)

        val baseName = when {
            sanitizedTitle.isBlank() && sanitizedAuthor.isBlank() -> "audio"
            sanitizedAuthor.isBlank() -> sanitizedTitle
            sanitizedTitle.isBlank() -> sanitizedAuthor
            else -> "$sanitizedTitle - $sanitizedAuthor"
        }

        val truncated = if (baseName.length > MAX_LENGTH) {
            baseName.take(MAX_LENGTH).trimEnd()
        } else {
            baseName
        }

        val ext = extension.removePrefix(".")
        return "$truncated.$ext"
    }

    fun generateWithMediaId(mediaId: String, extension: String): String {
        val ext = extension.removePrefix(".")
        return "audio_$mediaId.$ext"
    }

    private fun sanitize(input: String): String {
        return UNSAFE_CHARS.replace(input, "")
            .replace("\n", " ")
            .replace("\r", "")
            .trim()
    }
}
