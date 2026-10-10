package io.lightine.renovo

// Internal rather than private: a private constructor called from the nested class would get a
// public synthetic accessor, which would leak into the binary API.
/**
 * What is new in a version: one text for every language, or one text per language code.
 *
 * The text may use a small Markdown subset (bold, italic, bullets, links, line breaks), which
 * the update UI renders.
 *
 * ```
 * val single = ReleaseNotes("- Sync is **faster**")
 * val perLanguage = ReleaseNotes.of("az" to "- Xətalar aradan qaldırıldı", "en" to "- Bug fixes")
 * ```
 */
public class ReleaseNotes internal constructor(
    private val text: String?,
    private val byLanguage: List<Pair<String, String>>,
) {
    /**
     * Creates release notes with one text for every language.
     *
     * ```
     * val notes = ReleaseNotes("- Bug fixes")
     * ```
     */
    public constructor(text: String) : this(text, emptyList())

    /**
     * Returns the text for [language], a language code such as "az" or "en".
     *
     * Picks the exact language (ignoring case), then "en", then the first entry. Single-language
     * notes return their one text for every language.
     *
     * ```
     * val text = notes.forLanguage(Locale.getDefault().language)
     * ```
     */
    public fun forLanguage(language: String): String {
        text?.let { return it }
        return byLanguage.firstOrNull { it.first.equals(language, ignoreCase = true) }?.second
            ?: byLanguage.firstOrNull { it.first.equals(FALLBACK_LANGUAGE, ignoreCase = true) }?.second
            ?: byLanguage.first().second
    }

    // Lets the store write the notes without a public getter. Single-language notes are one entry
    // with an empty language code, which `of` rejects, so the two kinds cannot be confused.
    internal val entries: List<Pair<String, String>>
        get() = text?.let { listOf("" to it) } ?: byLanguage

    // Order matters for equality, because the first entry is the last fallback of forLanguage.
    override fun equals(other: Any?): Boolean = other is ReleaseNotes && other.text == text && other.byLanguage == byLanguage

    override fun hashCode(): Int = 31 * text.hashCode() + byLanguage.hashCode()

    // The texts are left out: they can be long and add nothing to a log line.
    override fun toString(): String = if (text != null) {
        "ReleaseNotes(single language)"
    } else {
        "ReleaseNotes(languages=${byLanguage.map { it.first }})"
    }

    /** Creates release notes with one text per language. */
    public companion object {
        private const val FALLBACK_LANGUAGE = "en"

        /**
         * Creates release notes with one text per language code, in the order given.
         *
         * The first entry is the fallback when neither the requested language nor "en" exists.
         * Throws [IllegalArgumentException] when no entry is given, a language code is blank, or a
         * language code appears twice.
         *
         * ```
         * val notes = ReleaseNotes.of("az" to "- Xətalar aradan qaldırıldı", "en" to "- Bug fixes")
         * ```
         */
        public fun of(vararg notes: Pair<String, String>): ReleaseNotes {
            require(notes.isNotEmpty()) { "ReleaseNotes.of needs at least one language." }
            require(notes.none { it.first.isBlank() }) { "A release notes language code must not be blank." }
            val codes = notes.map { it.first.lowercase() }
            require(codes.toSet().size == codes.size) { "Each release notes language code may appear only once." }
            return ReleaseNotes(null, notes.toList())
        }
    }
}
