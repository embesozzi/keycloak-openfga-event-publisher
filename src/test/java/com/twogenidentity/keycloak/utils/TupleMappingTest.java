package com.twogenidentity.keycloak.utils;

import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.events.admin.AdminEvent;

import static com.twogenidentity.keycloak.OpenFgaFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.keycloak.events.admin.OperationType.CREATE;
import static org.keycloak.events.admin.OperationType.DELETE;
import static org.keycloak.events.admin.OperationType.UPDATE;
import static org.keycloak.events.admin.ResourceType.*;

/** Keycloak admin event in, OpenFGA tuple out, using the repository's model.json. */
class TupleMappingTest {

    private OpenFgaHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        helper = helper();
    }

    private ClientWriteRequest map(AdminEvent event) {
        return helper.toClientWriteRequest(parser(event));
    }

    private static void assertTuple(ClientTupleKeyWithoutCondition tuple, String user, String relation, String object) {
        assertEquals(user + " " + relation + " " + object,
                tuple.getUser() + " " + tuple.getRelation() + " " + tuple.getObject());
    }

    private static ClientTupleKeyWithoutCondition onlyWrite(ClientWriteRequest request) {
        assertNull(request.getDeletes(), "no deletes expected");
        assertNotNull(request.getWrites(), "a write expected");
        assertEquals(1, request.getWrites().size());
        return request.getWrites().get(0);
    }

    private static ClientTupleKeyWithoutCondition onlyDelete(ClientWriteRequest request) {
        assertNull(request.getWrites(), "no writes expected");
        assertNotNull(request.getDeletes(), "a delete expected");
        assertEquals(1, request.getDeletes().size());
        return request.getDeletes().get(0);
    }

    @Test
    @DisplayName("User role assignment: user:<id> assignee role:<name>")
    void userRoleAssignment() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("User role removal deletes the tuple")
    void userRoleRemoval() {
        var request = map(event(REALM_ROLE_MAPPING, DELETE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyDelete(request), "user:" + USER_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("Role to role (composite): role:<parent> parent role:<child>")
    void compositeRole() {
        var request = map(event(REALM_ROLE, CREATE,
                "roles-by-id/" + ROLE_ADMIN_CATALOG_ID + "/composites", roles(ROLE_VIEW_PRODUCT)));

        assertTuple(onlyWrite(request), "role:" + ROLE_ADMIN_CATALOG, "parent", "role:" + ROLE_VIEW_PRODUCT);
    }

    @Test
    @DisplayName("User group membership: user:<id> assignee group:<name>")
    void userGroupMembership() {
        var request = map(event(GROUP_MEMBERSHIP, CREATE,
                "users/" + USER_ID + "/groups/" + GROUP_ID, group(GROUP_ID, GROUP_NAME)));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "group:" + GROUP_NAME);
    }

    @Test
    @Disabled("Bug, issue #4 (BACKLOG.md): group role mappings publish group:<id>, memberships publish group:<name>")
    @DisplayName("Group role assignment uses the same group identifier as group membership")
    void groupRoleAssignment() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "groups/" + GROUP_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(request), "group:" + GROUP_NAME, "parent_group", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("Service account realm role: client:<clientId> assignee role:<name>")
    void serviceAccountRealmRole() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + SA_USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(request), "client:" + SA_CLIENT_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("Service account client role: client:<clientId> assignee role:<name>")
    void serviceAccountClientRole() {
        // Current behaviour: client roles share the role:<name> namespace with realm roles (BACKLOG.md, Bugs)
        var request = map(event(CLIENT_ROLE_MAPPING, CREATE,
                "users/" + SA_USER_ID + "/role-mappings/clients/" + SA_CLIENT_UUID, roles("read-products")));

        assertTuple(onlyWrite(request), "client:" + SA_CLIENT_ID, "assignee", "role:read-products");
    }

    @Test
    @DisplayName("Service account role removal deletes the client tuple")
    void serviceAccountRoleRemoval() {
        var request = map(event(REALM_ROLE_MAPPING, DELETE,
                "users/" + SA_USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyDelete(request), "client:" + SA_CLIENT_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("Operations other than create/delete produce no tuple change")
    void updateIsIgnored() {
        var request = map(event(REALM_ROLE_MAPPING, UPDATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertFalse(helper.isAvailableClientRequest(request));
    }

    @Test
    @DisplayName("A relation missing from the model produces no tuple change")
    void relationNotInModel() {
        // client is a type in the model, but no type allows client in group#assignee
        var request = map(event(GROUP_MEMBERSHIP, CREATE,
                "users/" + SA_USER_ID + "/groups/" + GROUP_ID, group(GROUP_ID, GROUP_NAME)));

        assertFalse(helper.isAvailableClientRequest(request));
    }
}
