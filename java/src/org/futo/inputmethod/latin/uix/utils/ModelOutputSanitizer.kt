package org.futo.inputmethod.latin.uix.utils

import org.futo.inputmethod.latin.RichInputMethodManager

data class TextContext(
    val beforeCursor: CharSequence?,
    val afterCursor: CharSequence?
)

object ModelOutputSanitizer {
    private fun Char.isClosingBracket(): Boolean {
        return this in setOf(')', ']', '}', '>')
    }

    private fun Char.isOpeningBracket(): Boolean {
        return this in setOf('(', '[', '{', '<')
    }

    private fun Char.isPunctuation(): Boolean {
        return this in setOf('.', ',', '!', '?', ':', ';')
    }

    private fun String.endsWithWhitespaceOrNewline(): Boolean {
        return this.isNotEmpty() && this.last().isWhitespace()
    }

    private fun String.startsWithWhitespaceOrNewline(): Boolean {
        return this.isNotEmpty() && this.first().isWhitespace()
    }

    private val englishIRegex = Regex("^I\\s.*")
    private val beforeEndsPunctRegex = Regex(".*[.:?!]$")

    // Whisper hallucination phrases produced on silence, background noise, or mic switching
    // (WrenBoard 1.9 item 10; first treated as a bug in 1.5). A result that is ONLY one of
    // these phrases is dropped entirely and never reaches the text field or the suggestion strip.
    // Kept conservative on purpose: short real utterances like "thank you" are never filtered.
    private val silencePhrases = setOf(
        "thanks for watching",
        "thank you for watching",
        "thank you so much for watching",
        "thank you for watching this video",
        "thanks for listening",
        "thank you for listening",
        "please subscribe",
        "like and subscribe",
        "subscribe to my channel",
        "subscribe to our channel",
        "the end",
        "music"
    )
    private val trailingPunctuationRegex = Regex("[.!\\u2026\\s]+$")

    private fun isSilencePhrase(text: String): Boolean {
        val normalized = text.trim().replace(trailingPunctuationRegex, "").lowercase()
        return silencePhrases.contains(normalized)
    }

    @JvmStatic
    fun sanitize(result: String, textContext: TextContext?): String {
        if (textContext == null) {
            return result
        }

        val locale = RichInputMethodManager.getInstance().getCurrentSubtypeLocale()

        var trimmed = result.trim()
        if (trimmed.isEmpty()) return ""

        // Item 10: drop dictation results that are only a silence phrase.
        if (isSilencePhrase(trimmed)) return ""

        val before = (textContext.beforeCursor?.toString() ?: "")
            .split("\n")
            .lastOrNull() ?: ""
        val after = (textContext.afterCursor?.toString() ?: "")
            .split("\n")
            .firstOrNull() ?: ""

        // Whisper tends to generate ellipsis at the end of phrases/sentences more often than appropriate. Which isn't so bad if it's at the very end but frustrating when inserting something inside of a sentence.
        if (trimmed.endsWith("...") && !after.isEmpty()) {
            trimmed = trimmed.dropLast(3)
        }

        if (trimmed.isEmpty()) return ""

        // Punctuation
        if (trimmed.last().isPunctuation() && !after.isEmpty()) {
            trimmed = trimmed.dropLast(1)
        }

        if (trimmed.isEmpty()) return ""

        // Capitalization - whisper always capitalizes first character
        val beforeTrimmed = before.trimEnd()
        val isAcronym = trimmed.length >= 2 &&
                trimmed[0].isUpperCase() &&
                trimmed[1].isUpperCase()
        val isEnglishI = locale.language == "en" && ((trimmed.length == 1 && trimmed.first() == 'I') || englishIRegex.matches(trimmed))
        val needsLowercase = !beforeTrimmed.isEmpty() && !beforeEndsPunctRegex.matches(beforeTrimmed) && !isAcronym && !isEnglishI
        if (needsLowercase) {
            trimmed = trimmed.replaceFirstChar { it.lowercase(locale) }
        }

        // Leading and trailing spaces
        val needsLeadingSpace = before.isNotEmpty() && !before.endsWithWhitespaceOrNewline() &&
                !before.last().isOpeningBracket() &&
                before.last() != '—' &&
                before.last() != '"' &&
                before.last() != '*'
        val needsTrailingSpace = after.isNotEmpty() &&
                !after.startsWithWhitespaceOrNewline() &&
                !after.first().isPunctuation() &&
                !after.first().isClosingBracket() &&
                after.first() != '—' &&
                after.first() != '"' &&
                after.first() != '*'

        val prefix = if (needsLeadingSpace) " " else ""
        val suffix = if (needsTrailingSpace) " " else ""

        return prefix + trimmed + suffix
    }
}