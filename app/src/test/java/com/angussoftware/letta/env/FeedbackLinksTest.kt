package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feedback URL constants: the GitHub mirror is the public issue tracker,
 * so these URLs are contract — a typo here sends users to a 404.
 */
class FeedbackLinksTest {

    @Test
    fun repoUrlPointsAtPublicMirror() {
        assertEquals(
            "https://github.com/coda-rho-bot/letta-environment-android",
            FeedbackLinks.REPO_URL
        )
    }

    @Test
    fun newIssueUrlIsRepoRooted() {
        // The new-issue URL must live under the repo root — guards against a
        // future REPO_URL edit silently detaching the feedback entry point.
        assertTrue(
            FeedbackLinks.NEW_ISSUE_URL.startsWith(FeedbackLinks.REPO_URL + "/issues/new?")
        )
    }

    @Test
    fun newIssueUrlPresetsBugLabelAndTemplate() {
        assertTrue(FeedbackLinks.NEW_ISSUE_URL.contains("labels=bug"))
        assertTrue(FeedbackLinks.NEW_ISSUE_URL.contains("template=bug_report.md"))
    }

    @Test
    fun kofiUrlIsTheEstablishedAccount() {
        // Ko-fi account shared with Fuel Dashboard (SettingsPanel.kt) —
        // contract: the slug must stay angussoftware.
        assertEquals("https://ko-fi.com/angussoftware", FeedbackLinks.KOFI_URL)
    }

    @Test
    fun newIssueUrlIsWellFormedHttps() {
        // https scheme, host, no spaces — must open in a browser as-is.
        assertTrue(FeedbackLinks.NEW_ISSUE_URL.startsWith("https://"))
        assertTrue(FeedbackLinks.REPO_URL.startsWith("https://"))
        assertEquals(-1, FeedbackLinks.NEW_ISSUE_URL.indexOf(' '))
        assertEquals(-1, FeedbackLinks.REPO_URL.indexOf(' '))
        assertEquals(-1, FeedbackLinks.KOFI_URL.indexOf(' '))
    }
}
