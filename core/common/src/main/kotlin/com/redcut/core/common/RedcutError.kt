package com.redcut.core.common

/**
 * A failure the user can be told about (spec §4.1).
 *
 * [code] is what code switches on; [message] is what a human reads. Splitting them is
 * what stops the UI from parsing prose to decide whether to offer a "relink source"
 * button (R7) or a "this codec is not supported" explanation (FR-1.4).
 *
 * [cause] exists for the diagnostic path — the throwable from a platform call, logged
 * in debug builds and never shown as-is. Note that it participates in `equals`, and
 * `Throwable` compares by identity, so two errors that read identically are only equal
 * when they wrap the same throwable instance. That is the intent: an error value is a
 * description of a specific failure, not a string.
 */
data class RedcutError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
) {
    init {
        // A blank message is always a bug in the layer that built the error: the user
        // would see an empty dialog and the log would say nothing.
        require(message.isNotBlank()) { "message must not be blank" }
    }

    companion object {
        /**
         * Named constructor, so a call site reads as prose:
         * `RedcutError.of(ErrorCode.SOURCE_NOT_FOUND, "…")`.
         */
        fun of(code: ErrorCode, message: String, cause: Throwable? = null): RedcutError =
            RedcutError(code, message, cause)
    }
}

/**
 * The failure vocabulary (spec §4.1, FR-1.4, §9.1).
 *
 * Deliberately short. An enum that grows an entry per discovered cause ends up as an
 * enum nobody switches on exhaustively; anything that does not map onto one of these
 * is [UNKNOWN] with a message that carries the detail.
 */
enum class ErrorCode {
    /** The user (or the OS) took away access to a source — a revoked SAF grant, FR-1.5. */
    PERMISSION_DENIED,

    /** The media is real but we will not pretend to handle it: FR-1.4's rejection path. */
    UNSUPPORTED_MEDIA,

    /** A `sourceId` no longer resolves: the file was deleted or unmounted (R7). */
    SOURCE_NOT_FOUND,

    /** The decoder refused the stream — corrupt file, or a codec quirk. */
    DECODE_FAILED,

    /** The encoder refused the stream. */
    ENCODE_FAILED,

    /**
     * The hardware codec pool was exhausted (§9.1).
     *
     * Named separately from [DECODE_FAILED]/[ENCODE_FAILED] because the right response
     * is different: retry through the [MediaResourceBroker]'s queue rather than
     * refusing the media.
     */
    CODEC_EXHAUSTED,

    /** Storage, MediaStore, or a project file failed to read or write. */
    IO,

    /** Anything not yet classified. A message is always attached. */
    UNKNOWN,
}
