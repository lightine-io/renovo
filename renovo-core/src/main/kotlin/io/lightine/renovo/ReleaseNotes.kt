package io.lightine.renovo

// The constructor is internal, not private. A private constructor called from the companion
// object would make Kotlin add a hidden public method, and that method would become part of the
// public API.
/**
 * What is new in a version: one text for every language, or one text per language code.
 *
 * The text may use a small part of Markdown: bold, italic, bullets, links, and line breaks. The
 * update UI shows them formatted.
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
     * Returns the text for [language], a language tag or code such as "pt-BR", "pt_BR", or "pt".
     *
     * Case does not matter, and "-" and "_" count as the same. The lookup tries, in this order:
     * 1. The whole tag, then its language and region: "pt-BR" picks the entry "pt-BR" or "pt_br",
     *    and so does "pt-BR-u-mu-celsius".
     * 2. The language alone: first the plain language ("pt"), then the first entry in that language
     *    with any region, in file order ("pt-BR" when the device asks for "pt" or "pt-PT").
     * 3. English: "en", then the first entry whose code starts with "en-".
     * 4. The first entry.
     *
     * Single-language notes return their one text for every language.
     *
     * ```
     * val text = notes.forLanguage(Locale.getDefault().toLanguageTag())
     * ```
     */
    public fun forLanguage(language: String): String {
        text?.let { return it }
        val wanted = normalizedTag(language)
        val wantedLanguage = languageOf(wanted)
        return textWhere { it == wanted }
            ?: regionTagOf(wanted)?.let { tag -> textWhere { it == tag } }
            ?: textWhere { it == wantedLanguage }
            ?: textWhere { languageOf(it) == wantedLanguage }
            ?: textWhere { it == FALLBACK_LANGUAGE }
            ?: textWhere { languageOf(it) == FALLBACK_LANGUAGE }
            ?: byLanguage.first().second
    }

    private fun textWhere(matches: (String) -> Boolean): String? = byLanguage.firstOrNull { matches(normalizedTag(it.first)) }?.second

    // Lets the store save the notes without a public getter. Single-language notes become one entry
    // with an empty language code. `of` rejects an empty code, so the two kinds cannot be mixed up.
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
         * A code may carry a region, such as "pt-BR", and keeps the spelling given. The first entry
         * is the fallback when neither the requested language nor English exists. Throws
         * [IllegalArgumentException] when no entry is given, a language code is blank, or two codes
         * are the same once case is ignored and "-" and "_" count as the same ("pt-BR" and "pt_br").
         *
         * ```
         * val notes = ReleaseNotes.of("pt-BR" to "- Correções de erros", "en" to "- Bug fixes")
         * ```
         */
        public fun of(vararg notes: Pair<String, String>): ReleaseNotes {
            require(notes.isNotEmpty()) { "ReleaseNotes.of needs at least one language." }
            require(notes.none { it.first.isBlank() }) { "A release notes language code must not be blank." }
            val codes = notes.map { normalizedTag(it.first) }
            require(codes.toSet().size == codes.size) { "Each release notes language code may appear only once." }
            return ReleaseNotes(null, notes.toList())
        }
    }
}

// Authors write "pt-BR" or "pt_br", and Locale.toString() uses "_", so both separators and any case
// must lead to the same entry. Kotlin's lowercase() ignores the device locale, so a Turkish device
// does not turn "ID" into a dotless "ıd".
private fun normalizedTag(tag: String): String = tag.replace('_', '-').lowercase()

private fun languageOf(normalizedTag: String): String = normalizedTag.substringBefore('-')

// The update UI passes Locale.toLanguageTag(), which can carry a script and extensions, such as
// "sr-Latn-RS" or "en-US-u-mu-celsius". Version file keys usually name a language and at most a
// region, so after the whole tag the first lookup step tries those two parts. A one-letter subtag such as "u" or "x" starts
// the extensions, and a region is two letters or three digits.
private fun regionTagOf(normalizedTag: String): String? {
    val subtags = normalizedTag.split('-')
    val region =
        subtags.drop(1).takeWhile { it.length > 1 }.firstOrNull {
            (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit))
        } ?: return null
    return "${subtags.first()}-$region"
}
