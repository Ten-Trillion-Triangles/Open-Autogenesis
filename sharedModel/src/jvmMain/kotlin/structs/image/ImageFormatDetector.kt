package structs.image

/**
 * Magic-byte based image format detection.
 *
 * The JDK's `ImageIO.read` returns null on any format its bundled
 * codec set does not handle (WebP, CMYK JPEG, JPEG-2000, HEIC, AVIF,
 * TIFF variants beyond baseline, indexed PNG with a palette the
 * codec rejects). The fallback path then either throws or silently
 * passes the undecodable bytes downstream — which is the exact
 * failure mode the Sand Martello upload hits.
 *
 * Detection by magic bytes (not file extension) is the right primitive
 * for the gate because:
 *
 *   1. The map editor's JVM packer produces a `.png` zip entry by
 *      convention, but the source image a player drags into the
 *      editor can be ANY format. The extension on the player's
 *      machine has zero relationship to the bytes that end up in
 *      the zip's `map.png` entry.
 *   2. ImageIO's `getReaderFormatNames()` returns the registered
 *      format list, not a content-detected list — so it tells you
 *      "PNG is available" but not "the bytes in front of me are PNG."
 *   3. TwelveMonkeys registers its codecs with ImageIO automatically
 *      (ServiceLoader), so `ImageIO.read(bytes)` will pick them up —
 *      but ONLY if the codecs can identify the format from the bytes
 *      themselves. Some of them (TIFF in particular) need a magic
 *      sniff before they can pick the right reader.
 *
 * The detector returns a strongly-typed `ImageFormat` enum so the
 * decoder can route the bytes to the right codec path without
 * re-sniffing.
 */
object ImageFormatDetector
{
    /**
     * The image formats the map upload pipeline supports. The names
     * match the AGS-accepted `file_type` short forms (see server-extend
     * AGENTS.md § "AGS VALIDATION GOTCHAS" — `png`, `jpeg`, `webp`,
     * `bmp`, `gif`, `tif`) so the format detection also feeds
     * downstream AGS metadata correctly without a separate lookup.
     */
    enum class ImageFormat(val extension: String, val mimeType: String)
    {
        PNG("png", "image/png"),
        JPEG("jpeg", "image/jpeg"),
        GIF("gif", "image/gif"),
        BMP("bmp", "image/bmp"),
        TIFF("tif", "image/tiff"),
        WEBP("webp", "image/webp"),
        UNKNOWN("bin", "application/octet-stream");

        /**
         * True when the format is one TwelveMonkeys adds support for
         * beyond the JDK's bundled set. Used by the decoder to decide
         * whether the JDK's `ImageIO.read` is sufficient or whether
         * TwelveMonkeys' reader chain must be tried first.
         */
        val isExtended: Boolean
            get() = this == WEBP || this == TIFF || this == JPEG  // JPEG covers CMYK/12-bit/JPEG-2000
    }

    /**
     * Detect the image format from the leading bytes of the input.
     * Reads at most 16 bytes — every supported format's magic fits
     * in the first 12 bytes except TIFF (which can be either "II" or
     * "MM" followed by 42 as a little-endian or big-endian uint32).
     *
     * @return The detected format, or [ImageFormat.UNKNOWN] when the
     *   magic does not match any known format. `UNKNOWN` is treated as
     *   "give up and reject with a specific reason" by the decoder —
     *   we never silently pass unknown bytes downstream.
     */
    fun detect(bytes: ByteArray): ImageFormat
    {
        if (bytes.size < 4) return ImageFormat.UNKNOWN

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte())
        {
            return ImageFormat.PNG
        }

        // JPEG: FF D8 FF (followed by a marker byte that distinguishes
        // JFIF vs EXIF vs raw, but we don't need to distinguish for routing)
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
        {
            return ImageFormat.JPEG
        }

        // GIF: "GIF87a" or "GIF89a"
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte())
        {
            return ImageFormat.GIF
        }

        // BMP: "BM"
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte())
        {
            return ImageFormat.BMP
        }

        // TIFF: little-endian "II" + 0x2A 0x00, OR big-endian "MM" + 0x00 0x2A
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte())
        {
            return ImageFormat.TIFF
        }
        if (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte())
        {
            return ImageFormat.TIFF
        }

        // WebP: "RIFF" .... "WEBP" at offset 8
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte())
        {
            return ImageFormat.WEBP
        }

        return ImageFormat.UNKNOWN
    }
}