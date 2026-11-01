package com.nexus.agent.data.connectors

import com.nexus.agent.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleOAuthBuildIdentityTest {
    @Test fun androidApkMustAlwaysIncludePublicOAuthClientId() {
        assertTrue("Build cannot ship with empty Google OAuth Client ID",
            BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank())
        assertTrue("Expected a Google OAuth client ID",
            Regex("^[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com$")
                .matches(BuildConfig.GOOGLE_OAUTH_CLIENT_ID))
    }

    @Test fun oauthPackageNameMustMatchRegisteredAndroidClient() {
        assertEquals("com.nexus.agent", BuildConfig.APPLICATION_ID)
    }
}
