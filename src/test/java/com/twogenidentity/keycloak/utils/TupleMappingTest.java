package com.twogenidentity.keycloak.utils;

import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import org.junit.jupiter.api.BeforeEach;
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
    @DisplayName("Group role assignment uses the group name, as group membership does (issue #4)")
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
    @DisplayName("Service account group membership: client:<clientId> assignee group:<name>")
    void serviceAccountGroupMembership() {
        var request = map(event(GROUP_MEMBERSHIP, CREATE,
                "users/" + SA_USER_ID + "/groups/" + GROUP_ID, group(GROUP_ID, GROUP_NAME)));

        assertTuple(onlyWrite(request), "client:" + SA_CLIENT_ID, "assignee", "group:" + GROUP_NAME);
    }

    @Test
    @DisplayName("Service account client role: client:<clientId> assignee role:<clientId>/<name>")
    void serviceAccountClientRole() {
        var request = map(event(CLIENT_ROLE_MAPPING, CREATE,
                "users/" + SA_USER_ID + "/role-mappings/clients/" + SA_CLIENT_UUID, roles("read-products")));

        assertTuple(onlyWrite(request), "client:" + SA_CLIENT_ID, "assignee", "role:" + SA_CLIENT_ID + "/read-products");
    }

    @Test
    @DisplayName("User client role: client roles are namespaced by clientId, so they do not collide with realm roles")
    void userClientRole() {
        var request = map(event(CLIENT_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/clients/" + SA_CLIENT_UUID, roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:" + SA_CLIENT_ID + "/" + ROLE_ADMIN_CATALOG);
    }

    // --- Several roles in one event (the Admin Console assigns them in one call) ---

    @Test
    @DisplayName("Several roles assigned in one call: one tuple per role")
    void severalRolesAssigned() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG, ROLE_VIEW_PRODUCT)));

        assertNull(request.getDeletes(), "no deletes expected");
        assertEquals(2, request.getWrites().size());
        assertTuple(request.getWrites().get(0), "user:" + USER_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG);
        assertTuple(request.getWrites().get(1), "user:" + USER_ID, "assignee", "role:" + ROLE_VIEW_PRODUCT);
    }

    @Test
    @DisplayName("Several roles removed in one call: one delete per role")
    void severalRolesRemoved() {
        var request = map(event(REALM_ROLE_MAPPING, DELETE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG, ROLE_VIEW_PRODUCT)));

        assertNull(request.getWrites(), "no writes expected");
        assertEquals(2, request.getDeletes().size());
        assertTuple(request.getDeletes().get(0), "user:" + USER_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG);
        assertTuple(request.getDeletes().get(1), "user:" + USER_ID, "assignee", "role:" + ROLE_VIEW_PRODUCT);
    }

    @Test
    @DisplayName("Several children added to a composite role in one call: one parent tuple per child")
    void severalCompositeChildren() {
        var request = map(event(REALM_ROLE, CREATE,
                "roles-by-id/" + ROLE_ADMIN_CATALOG_ID + "/composites", roles(ROLE_VIEW_PRODUCT, "edit-product")));

        assertEquals(2, request.getWrites().size());
        assertTuple(request.getWrites().get(0), "role:" + ROLE_ADMIN_CATALOG, "parent", "role:" + ROLE_VIEW_PRODUCT);
        assertTuple(request.getWrites().get(1), "role:" + ROLE_ADMIN_CATALOG, "parent", "role:edit-product");
    }

    // --- Names with characters OpenFGA does not accept (space, ':', '#') are percent-encoded ---

    @Test
    @DisplayName("Role name with a space is percent-encoded: role:Account%20Manager")
    void roleNameWithSpace() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ACCOUNT_MANAGER)));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:Account%20Manager");
    }

    @Test
    @DisplayName("Composite role with spaces in both names is percent-encoded on both sides")
    void compositeRoleWithSpaces() {
        var request = map(event(REALM_ROLE, CREATE,
                "roles-by-id/" + ROLE_ACCOUNT_MANAGER_ID + "/composites", roles("view transactions")));

        assertTuple(onlyWrite(request), "role:Account%20Manager", "parent", "role:view%20transactions");
    }

    @Test
    @DisplayName("Group name with a space is percent-encoded, for membership and for group roles")
    void groupNameWithSpace() {
        var membership = map(event(GROUP_MEMBERSHIP, CREATE,
                "users/" + USER_ID + "/groups/" + GROUP_WITH_SPACE_ID, group(GROUP_WITH_SPACE_ID, GROUP_WITH_SPACE_NAME)));
        var groupRole = map(event(REALM_ROLE_MAPPING, CREATE,
                "groups/" + GROUP_WITH_SPACE_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(membership), "user:" + USER_ID, "assignee", "group:Sales%20Team");
        assertTuple(onlyWrite(groupRole), "group:Sales%20Team", "parent_group", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("SPIFFE clientId is percent-encoded, '/' included: client:spiffe%3A%2F%2F...")
    void spiffeServiceAccount() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + SPIFFE_SA_USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(request), "client:spiffe%3A%2F%2Fexample.org%2Fns%2Fagents%2Fsa%2Fplanner-agent", "assignee", "role:" + ROLE_ADMIN_CATALOG);
    }

    @Test
    @DisplayName("SPIFFE client role: the clientId part of role:<clientId>/<name> is percent-encoded")
    void spiffeClientRole() {
        var request = map(event(CLIENT_ROLE_MAPPING, CREATE,
                "users/" + SPIFFE_SA_USER_ID + "/role-mappings/clients/" + SPIFFE_CLIENT_UUID, roles("read")));

        assertTuple(onlyWrite(request), "client:spiffe%3A%2F%2Fexample.org%2Fns%2Fagents%2Fsa%2Fplanner-agent", "assignee",
                "role:spiffe%3A%2F%2Fexample.org%2Fns%2Fagents%2Fsa%2Fplanner-agent/read");
    }

    @Test
    @DisplayName("'#' and '%' are percent-encoded too, so the encoding can be reversed")
    void reservedCharacters() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles("a#b:100%")));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:a%23b%3A100%25");
    }

    @Test
    @DisplayName("Names without those characters are published unchanged")
    void plainNamesUnchanged() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles("view_product.v2-ñ")));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:view_product.v2-ñ");
    }

    @Test
    @DisplayName("'/' in a realm role name is percent-encoded, so it never looks like role:<clientId>/<roleName>")
    void slashInRealmRoleName() {
        var request = map(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles("store-api/read-products")));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:store-api%2Fread-products");
    }

    // --- Identifier "id": Keycloak ids instead of names (no clientId prefix, nothing to encode) ---

    private ClientWriteRequest mapById(AdminEvent event) {
        return helper.toClientWriteRequest(parser(event, OpenFgaIdentifier.ID));
    }

    @Test
    @DisplayName("id: user role assignment publishes the role id")
    void idUserRole() {
        var request = mapById(event(REALM_ROLE_MAPPING, CREATE,
                "users/" + USER_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "assignee", "role:" + ROLE_ADMIN_CATALOG_ID);
    }

    @Test
    @DisplayName("id: composite role publishes the ids of both roles")
    void idCompositeRole() {
        var request = mapById(event(REALM_ROLE, CREATE,
                "roles-by-id/" + ROLE_ADMIN_CATALOG_ID + "/composites", roles(ROLE_VIEW_PRODUCT)));

        assertTuple(onlyWrite(request), "role:" + ROLE_ADMIN_CATALOG_ID, "parent", "role:role-id-" + ROLE_VIEW_PRODUCT);
    }

    @Test
    @DisplayName("id: group membership and group role publish the group id")
    void idGroup() {
        var membership = mapById(event(GROUP_MEMBERSHIP, CREATE,
                "users/" + USER_ID + "/groups/" + GROUP_WITH_SPACE_ID, group(GROUP_WITH_SPACE_ID, GROUP_WITH_SPACE_NAME)));
        var groupRole = mapById(event(REALM_ROLE_MAPPING, CREATE,
                "groups/" + GROUP_WITH_SPACE_ID + "/role-mappings/realm", roles(ROLE_ADMIN_CATALOG)));

        assertTuple(onlyWrite(membership), "user:" + USER_ID, "assignee", "group:" + GROUP_WITH_SPACE_ID);
        assertTuple(onlyWrite(groupRole), "group:" + GROUP_WITH_SPACE_ID, "parent_group", "role:" + ROLE_ADMIN_CATALOG_ID);
    }

    @Test
    @DisplayName("id: service account publishes the client's internal id, and a client role its role id without prefix")
    void idServiceAccountClientRole() {
        var request = mapById(event(CLIENT_ROLE_MAPPING, CREATE,
                "users/" + SPIFFE_SA_USER_ID + "/role-mappings/clients/" + SPIFFE_CLIENT_UUID, roles("read")));

        assertTuple(onlyWrite(request), "client:" + SPIFFE_CLIENT_UUID, "assignee", "role:role-id-read");
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
    @DisplayName("A relation missing from the model produces no tuple change (1.2 model: group#assignee is [user] only)")
    void relationNotInModel() throws Exception {
        var oldModel = model();
        oldModel.getTypeDefinitions().stream().filter(t -> t.getType().equals("group")).findFirst().orElseThrow()
                .getMetadata().getRelations().get("assignee").getDirectlyRelatedUserTypes()
                .removeIf(r -> r.getType().equals("client"));
        var oldHelper = new OpenFgaHelper();
        oldHelper.loadModel(oldModel);

        var request = oldHelper.toClientWriteRequest(parser(event(GROUP_MEMBERSHIP, CREATE,
                "users/" + SA_USER_ID + "/groups/" + GROUP_ID, group(GROUP_ID, GROUP_NAME))));

        assertFalse(oldHelper.isAvailableClientRequest(request));
    }

    @Test
    @DisplayName("Organization member added: user:<id> member organization:<alias>, member found by username")
    void organizationMemberAdded() {
        var request = map(organizationMembership(CREATE, ORG_ID, ORG_ALIAS, USERNAME, null));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "member", "organization:" + ORG_ALIAS);
    }

    @Test
    @DisplayName("Organization member removed: the member id comes from the path, the tuple is deleted")
    void organizationMemberRemoved() {
        var request = map(organizationMembership(DELETE, ORG_ID, ORG_ALIAS, USERNAME, USER_ID));

        assertTuple(onlyDelete(request), "user:" + USER_ID, "member", "organization:" + ORG_ALIAS);
    }

    @Test
    @DisplayName("Organization alias with a space is published percent-encoded")
    void organizationAliasEncoded() {
        var request = map(organizationMembership(CREATE, ORG_WITH_SPACE_ID, ORG_WITH_SPACE_ALIAS, USERNAME, null));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "member", "organization:Acme%20Corp");
    }

    @Test
    @DisplayName("Identifier id: user:<id> member organization:<orgId>")
    void idOrganization() {
        var request = helper.toClientWriteRequest(parser(
                organizationMembership(CREATE, ORG_ID, ORG_ALIAS, USERNAME, null), OpenFgaIdentifier.ID));

        assertTuple(onlyWrite(request), "user:" + USER_ID, "member", "organization:" + ORG_ID);
    }

    @Test
    @DisplayName("A model without the organization type produces no tuple change (3.1 model)")
    void organizationNotInModel() throws Exception {
        var oldModel = model();
        oldModel.getTypeDefinitions().removeIf(t -> t.getType().equals("organization"));
        var oldHelper = new OpenFgaHelper();
        oldHelper.loadModel(oldModel);

        var request = oldHelper.toClientWriteRequest(parser(organizationMembership(CREATE, ORG_ID, ORG_ALIAS, USERNAME, null)));

        assertFalse(oldHelper.isAvailableClientRequest(request));
    }
}
