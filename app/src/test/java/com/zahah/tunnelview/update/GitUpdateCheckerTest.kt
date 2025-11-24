package com.zahah.tunnelview.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitUpdateCheckerTest {

    @Test
    fun `build raw url from github scp syntax`() {
        val url = GitUpdateChecker.buildRawGithubUrl(
            repoUrl = "git@github.com:example/demo.git",
            branch = "main",
            filePath = "releases/app.apk"
        )
        assertEquals(
            "https://raw.githubusercontent.com/example/demo/main/releases/app.apk",
            url
        )
    }

    @Test
    fun `build raw url from github https syntax`() {
        val url = GitUpdateChecker.buildRawGithubUrl(
            repoUrl = "https://github.com/example/demo.git",
            branch = "refs/heads/release",
            filePath = "/releases/app.apk"
        )
        assertEquals(
            "https://raw.githubusercontent.com/example/demo/release/releases/app.apk",
            url
        )
    }

    @Test
    fun `reject raw url for non github host`() {
        val url = GitUpdateChecker.buildRawGithubUrl(
            repoUrl = "git@gitlab.com:example/demo.git",
            branch = "main",
            filePath = "release.apk"
        )
        assertNull(url)
    }
}
