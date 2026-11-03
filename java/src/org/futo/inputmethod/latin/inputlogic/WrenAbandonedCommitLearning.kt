package org.futo.inputmethod.latin.inputlogic

/**
 * When an auto-correct commit is abandoned because the suggestion pass missed its time
 * budget (GeneralIME.ensureSuggestionsCompleted returns false), the typed text is still
 * finalized into the field as-is. That word must be learned exactly as a typed-word
 * commit would learn it, or a slow device silently stops building typing history.
 *
 * rev11 target trace: under CPU starvation only 1 of 3 identical 'zebra'+space commits
 * reached the learning path. The leading hypothesis (unproven until the rev13
 * ensureCompleted/abandonCommit trace lands) is that the other two spaces were processed
 * while the word's suggestion job was still in flight and the 450ms bounded wait elapsed,
 * taking the abandon branch which finalized the text but skipped learning entirely.
 * This helper's behavior is correct for the abandon branch whenever it is taken.
 */
object WrenAbandonedCommitLearning {
    /** Word to learn from an abandoned auto-correct commit, or null when there is none. */
    @JvmStatic
    fun wordToLearn(typedWord: String): String? = typedWord.ifEmpty { null }

    /** N-gram lookback matching commitChosenWord's pre-commit rule: skip the still-composing word. */
    @JvmStatic
    fun nthPreviousWord(isComposingWord: Boolean): Int = if (isComposingWord) 2 else 1
}
