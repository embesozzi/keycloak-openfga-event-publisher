package com.twogenidentity.keycloak.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.twogenidentity.keycloak.OpenFgaFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.keycloak.events.admin.OperationType.CREATE;
import static org.keycloak.events.admin.ResourceType.*;

/** Edge cases of reading a Keycloak admin event. */
class EventParserTest {

    @Test
    @DisplayName("Unhandled resource types are rejected with IllegalArgumentException (the provider logs and skips them)")
    void unhandledResourceType() {
        var parser = parser(event(CLIENT, CREATE, "clients/" + SA_CLIENT_UUID, "{\"clientId\":\"x\"}"));

        assertThrows(IllegalArgumentException.class, parser::getEventObjectType);
    }

    @Test
    @DisplayName("Unhandled resource paths are rejected with IllegalArgumentException")
    void unhandledResourcePath() {
        var parser = parser(event(REALM_ROLE, CREATE, "roles/" + ROLE_ADMIN_CATALOG, roles(ROLE_ADMIN_CATALOG)));

        assertThrows(IllegalArgumentException.class, parser::getEventUserType);
    }

    @Test
    @DisplayName("Empty representation array is reported, not a NullPointerException")
    void emptyRepresentation() {
        var parser = parser(event(REALM_ROLE_MAPPING, CREATE, "users/" + USER_ID + "/role-mappings/realm", "[]"));

        assertThrows(IllegalArgumentException.class, parser::getEventObjectName);
    }

    @Test
    @DisplayName("Missing name attribute is reported, not a NullPointerException")
    void missingName() {
        var parser = parser(event(REALM_ROLE_MAPPING, CREATE, "users/" + USER_ID + "/role-mappings/realm", "[{\"id\":\"x\"}]"));

        assertThrows(IllegalArgumentException.class, parser::getEventObjectName);
    }

    @Test
    @DisplayName("A regular user is typed user, a service account user is typed client")
    void userTypes() {
        assertEquals("user", parser(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG))).getEventUserType());
        assertEquals("client", parser(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + SA_USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG))).getEventUserType());
    }

    @Test
    @DisplayName("Unknown user ids are treated as regular users")
    void unknownUser() {
        assertEquals("user", parser(event(REALM_ROLE_MAPPING, CREATE,
                "users/unknown/role-mappings/realm", roles(ROLE_ADMIN_CATALOG))).getEventUserType());
    }

    @Test
    @DisplayName("Without a session, users are treated as regular users")
    void noSession() {
        var parser = new EventParser(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertEquals("user", parser.getEventUserType());
        assertEquals(USER_ID, parser.getTranslateUserId());
    }
}
