package com.angussoftware.letta.env

/**
 * Public feedback entry points.
 *
 * The GitHub mirror is the public issue tracker — users report bugs there
 * (issue templates live in .github/ISSUE_TEMPLATE). Pure URL logic only,
 * no Android framework types, so it is JVM-unit-testable like UpdateManager.
 * The new-issue URL pins the bug label + bug_report template; GitHub degrades
 * gracefully (plain new-issue form) when the template is absent.
 */
object FeedbackLinks {
    const val REPO_URL = "https://github.com/coda-rho-bot/agents-beamdown"
    const val ISSUE_LABEL = "bug"
    const val ISSUE_TEMPLATE = "bug_report.md"

    /** New-issue URL with the bug label + bug_report template preselected. */
    const val NEW_ISSUE_URL =
        "$REPO_URL/issues/new?labels=$ISSUE_LABEL&template=$ISSUE_TEMPLATE"

    /** Support page (Ko-fi — same ACTION_VIEW pattern as Fuel Dashboard). */
    const val KOFI_URL = "https://ko-fi.com/angussoftware"
}
