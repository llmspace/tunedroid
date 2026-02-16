package com.tunedroid.app.engine

enum class FormatPreset(
    val label: String,
    val bitrate: Int, // target kbps, 0 for original
    val extension: String,
    val requiresConversion: Boolean
) {
    MP3_128("MP3 — Standard (128 kbps)", 128, "mp3", true),
    MP3_256("MP3 — High (256 kbps)", 256, "mp3", true),
    MP3_320("MP3 — Maximum (320 kbps)", 320, "mp3", true),
    ORIGINAL("Original", 0, "", false);

    companion object {
        fun availablePresets(sourceBitrateKbps: Int): List<FormatPreset> {
            val eligible = entries.filter { preset ->
                // Include preset if it's ORIGINAL or if its bitrate doesn't exceed source
                preset == ORIGINAL || (preset.requiresConversion && preset.bitrate <= sourceBitrateKbps)
            }
            // Always include ORIGINAL as fallback
            return if (eligible.isEmpty()) listOf(ORIGINAL) else eligible
        }

        /**
         * Returns true if [preset] can be used with the given source bitrate.
         * ORIGINAL is always eligible; MP3 presets are eligible only when their
         * target bitrate does not exceed the source.
         */
        fun isEligible(preset: FormatPreset, sourceBitrateKbps: Int): Boolean {
            return preset == ORIGINAL || (preset.requiresConversion && preset.bitrate <= sourceBitrateKbps)
        }
    }
}
