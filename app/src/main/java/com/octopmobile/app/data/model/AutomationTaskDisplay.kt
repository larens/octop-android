package com.octopmobile.app.data.model

private val HtmlCommentPattern = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

/** Removes machine-only HTML comments while preserving the user-authored prompt text. */
internal fun cleanAutomationTaskText(value: String): String = value
    .replace(HtmlCommentPattern, "")
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString("\n")

internal fun automationTaskDisplayTitle(
    name: String,
    prompt: String,
    id: String,
    maxLength: Int = 60,
): String {
    val candidate = sequenceOf(name, prompt)
        .map(::cleanAutomationTaskText)
        .map { cleaned -> cleaned.lineSequence().firstOrNull().orEmpty() }
        .firstOrNull(String::isNotBlank)
        ?: id
    return candidate.take(maxLength)
}
