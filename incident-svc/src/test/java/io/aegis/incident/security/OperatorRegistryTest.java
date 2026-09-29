package io.aegis.incident.security;

import io.aegis.incident.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity must come from the credential. These tests pin the three
 * behaviours the approval gate depends on: an operator key resolves to that
 * person, the shared key still works but is explicitly not a person, and an
 * unknown credential resolves to nothing at all.
 */
class OperatorRegistryTest {

    private static SecurityProperties withOperators() {
        return new SecurityProperties("shared-key", "dashboard", List.of(
                new SecurityProperties.Operator("bhav", "bhav-key"),
                new SecurityProperties.Operator("oncall", "oncall-key")), true, 60, null);
    }

    @Test
    void operatorKeyResolvesToThatPerson() {
        OperatorRegistry registry = new OperatorRegistry(withOperators());

        OperatorRegistry.Identity bhav = registry.authenticate("bhav-key").orElseThrow();
        assertEquals("bhav", bhav.name());
        assertTrue(bhav.attributable(), "a per-operator key names a person");

        OperatorRegistry.Identity oncall = registry.authenticate("oncall-key").orElseThrow();
        assertEquals("oncall", oncall.name());
        assertEquals(2, registry.attributableIdentityCount());
    }

    @Test
    void theSharedKeyStillWorksButIsNotAPerson() {
        OperatorRegistry registry = new OperatorRegistry(withOperators());

        OperatorRegistry.Identity shared = registry.authenticate("shared-key").orElseThrow();

        assertEquals("dashboard", shared.name());
        assertFalse(shared.attributable(),
                "the shared key authenticates the dashboard client, not a human, so it must "
                        + "not be able to count towards a two-person rule");
        assertEquals(3, registry.identityCount());
        assertEquals(2, registry.attributableIdentityCount());
    }

    @Test
    void unknownMissingAndPartialKeysAreRejected() {
        OperatorRegistry registry = new OperatorRegistry(withOperators());

        assertTrue(registry.authenticate("nope").isEmpty());
        assertTrue(registry.authenticate(null).isEmpty());
        assertTrue(registry.authenticate("").isEmpty());
        // A prefix or extension of a real key must not authenticate.
        assertTrue(registry.authenticate("bhav-ke").isEmpty());
        assertTrue(registry.authenticate("bhav-key-x").isEmpty());
    }

    @Test
    void withNoOperatorKeysThereIsNoAttributableIdentity() {
        OperatorRegistry registry = OperatorRegistry.ofSharedKey("only-key");

        assertTrue(registry.enabled());
        assertEquals("operator", registry.authenticate("only-key").orElseThrow().name());
        assertFalse(registry.identitiesAreAttributable());
        assertEquals(0, registry.attributableIdentityCount());
        assertEquals(1, registry.identityCount());
    }

    @Test
    void noConfigurationAtAllDisablesAuthentication() {
        OperatorRegistry registry = OperatorRegistry.ofSharedKey("   ");

        assertFalse(registry.enabled());
        assertTrue(registry.authenticate(null).isEmpty());
    }

    @Test
    void operatorsWithoutAKeyAreIgnoredRatherThanTrusted() {
        SecurityProperties properties = new SecurityProperties("shared-key", "dashboard", List.of(
                new SecurityProperties.Operator("blank-key", "  "),
                new SecurityProperties.Operator("", "some-key")), true, 60, null);

        OperatorRegistry registry = new OperatorRegistry(properties);

        assertEquals(1, registry.identityCount());
        assertEquals(0, registry.attributableIdentityCount());
        assertTrue(registry.authenticate("some-key").isEmpty());
        assertTrue(registry.authenticate("").isEmpty());
    }

    @Test
    void aDuplicateOperatorNameKeepsTheFirstCredential() {
        SecurityProperties properties = new SecurityProperties("shared-key", "dashboard", List.of(
                new SecurityProperties.Operator("bhav", "first-key"),
                new SecurityProperties.Operator("bhav", "second-key")), true, 60, null);

        OperatorRegistry registry = new OperatorRegistry(properties);

        assertEquals("bhav", registry.authenticate("first-key").orElseThrow().name());
        assertTrue(registry.authenticate("second-key").isEmpty(),
                "the ignored duplicate credential must not authenticate");
        assertEquals(1, registry.attributableIdentityCount());
    }
}
