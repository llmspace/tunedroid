package com.tunedroid.app.engine

object MediaUrlParser {

    private val SUPPORTED_URL_PATTERNS = listOf(
        // Standard watch URLs
        Regex("https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}/watch\\?.*v=([a-zA-Z0-9_-]{11})"),
        // Short URLs
        Regex("https?://youtu\\.be/([a-zA-Z0-9_-]{11})"),
        // Embed URLs
        Regex("https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}/embed/([a-zA-Z0-9_-]{11})"),
        // Shorts URLs
        Regex("https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}/shorts/([a-zA-Z0-9_-]{11})"),
        // Mobile URLs
        Regex("https?://m\\.[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}/watch\\?.*v=([a-zA-Z0-9_-]{11})"),
        // Generic video URL (catch-all for other platforms)
        Regex("https?://(?:www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b[-a-zA-Z0-9@:%_+.~#?&/=]*")
    )

    data class ParseResult(
        val isValid: Boolean,
        val url: String,
        val mediaId: String? = null
    )

    fun parse(input: String): ParseResult {
        val trimmed = input.trim()

        if (trimmed.isBlank()) {
            return ParseResult(isValid = false, url = trimmed)
        }

        // Try to extract a media ID from known patterns
        for (pattern in SUPPORTED_URL_PATTERNS.dropLast(1)) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val mediaId = match.groupValues.getOrNull(1)
                return ParseResult(
                    isValid = true,
                    url = trimmed,
                    mediaId = mediaId
                )
            }
        }

        // Fall back to generic URL check
        val genericPattern = SUPPORTED_URL_PATTERNS.last()
        if (genericPattern.matches(trimmed)) {
            return ParseResult(isValid = true, url = trimmed)
        }

        // Check if it's at least a valid URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return ParseResult(isValid = true, url = trimmed)
        }

        return ParseResult(isValid = false, url = trimmed)
    }

    fun isValidUrl(input: String): Boolean = parse(input).isValid
}
