package com.octopmobile.app.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdentityTest {
    private val base = "https://octop.example.com"

    @Test
    fun `fresh install does not reset local data`() {
        assertFalse(AccountIdentity(null, null, null).differsFrom(base, 2L, "larens"))
    }

    @Test
    fun `same user keeps local data`() {
        assertFalse(AccountIdentity(base, 2L, "larens").differsFrom(base, 2L, "larens"))
    }

    @Test
    fun `different user id resets even when username is reused`() {
        assertTrue(AccountIdentity(base, 2L, "larens").differsFrom(base, 3L, "larens"))
    }

    @Test
    fun `legacy session without user id falls back to username`() {
        assertTrue(AccountIdentity(base, null, "larens").differsFrom(base, 3L, "friend"))
        assertFalse(AccountIdentity(base, null, "larens").differsFrom(base, 2L, "Larens".lowercase()))
    }

    @Test
    fun `different host resets local data`() {
        assertTrue(
            AccountIdentity(base, 2L, "larens")
                .differsFrom("https://other.example.com", 2L, "larens"),
        )
    }
}
