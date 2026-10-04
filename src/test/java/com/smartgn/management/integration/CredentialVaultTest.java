package com.smartgn.management.integration;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialVaultTest {
    private final CredentialVault vault = new CredentialVault(Base64.getEncoder().encodeToString(new byte[32]));

    @Test void ciphertextIsRandomizedAndBoundToTheDevice() {
        UUID device = UUID.randomUUID();
        String secret = vault.generate();
        String first = vault.protect(device, secret);
        String second = vault.protect(device, secret);
        assertThat(first).doesNotContain(secret).isNotEqualTo(second);
        assertThat(vault.reveal(device, first)).isEqualTo(secret);
        assertThatThrownBy(() -> vault.reveal(UUID.randomUUID(), first)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void corruptedCiphertextAndWrongKeyAreRejected() {
        UUID device = UUID.randomUUID();
        String protectedValue = vault.protect(device, "credential");
        byte[] otherKey = new byte[32]; otherKey[0] = 1;
        CredentialVault other = new CredentialVault(Base64.getEncoder().encodeToString(otherKey));
        assertThatThrownBy(() -> other.reveal(device, protectedValue)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vault.reveal(device, "bad.data")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialVault("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
