package com.cosmic.ai.data

import com.cosmic.ai.data.local.keystore.SecretMasker
import org.junit.Assert.assertEquals
import org.junit.Test

class SecretMaskerTest {
    @Test fun missingSecretDisplaysNothing() {
        assertEquals("", SecretMasker.mask(null))
    }

    @Test fun shortSecretIsFullyMasked() {
        assertEquals("••••••••", SecretMasker.mask("12345678"))
    }

    @Test fun longSecretShowsOnlyBoundedPrefixAndSuffix() {
        assertEquals("abcd••••wxyz", SecretMasker.mask("abcdefghijklmnopqrstuvwxwxyz"))
    }
}
