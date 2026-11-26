package com.twogenidentity.keycloak;

import com.twogenidentity.keycloak.event.EventParser;
import com.twogenidentity.keycloak.utils.OpenFgaHelper;
import com.twogenidentity.keycloak.utils.OpenFgaIdentifier;
import dev.openfga.sdk.api.client.JsonSerializer;
import dev.openfga.sdk.api.model.AuthorizationModel;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Shared test data: the repository's own authorization model (model.json), Keycloak admin events shaped
 * like the ones Keycloak emits, and a mocked realm with the roles, groups, users and clients the events refer to.
 */
public final class OpenFgaFixture {

    public static final String REALM_ID = "realm-id";

    // Roles (id -> name)
    public static final String ROLE_ADMIN_CATALOG_ID = "role-id-admin-catalog";
    public static final String ROLE_ADMIN_CATALOG = "admin-catalog";
    public static final String ROLE_VIEW_PRODUCT = "view-product";
    // A role name with a space, which OpenFGA does not accept as is
    public static final String ROLE_ACCOUNT_MANAGER_ID = "role-id-account-manager";
    public static final String ROLE_ACCOUNT_MANAGER = "Account Manager";

    // Groups
    public static final String GROUP_ID = "group-id-analysts";
    public static final String GROUP_NAME = "analysts";
    public static final String GROUP_WITH_SPACE_ID = "group-id-sales-team";
    public static final String GROUP_WITH_SPACE_NAME = "Sales Team";

    // A regular user
    public static final String USER_ID = "user-id-paula";
    public static final String USERNAME = "paula";

    // Organizations (alias is the OpenFGA id); one alias with a space
    public static final String ORG_ID = "org-id-org-a";
    public static final String ORG_ALIAS = "org-a";
    public static final String ORG_WITH_SPACE_ID = "org-id-acme-corp";
    public static final String ORG_WITH_SPACE_ALIAS = "Acme Corp";

    // A service account user linked to a confidential client
    public static final String SA_USER_ID = "user-id-service-account-store-api";
    public static final String SA_CLIENT_UUID = "client-uuid-store-api";
    public static final String SA_CLIENT_ID = "store-api";

    // A service account whose clientId is a SPIFFE id: contains ':', which OpenFGA does not accept as is
    public static final String SPIFFE_SA_USER_ID = "user-id-service-account-planner-agent";
    public static final String SPIFFE_CLIENT_UUID = "client-uuid-planner-agent";
    public static final String SPIFFE_CLIENT_ID = "spiffe://example.org/ns/agents/sa/planner-agent";

    private OpenFgaFixture() {
    }

    /** The authorization model shipped in the repository, as OpenFGA returns it. */
    public static AuthorizationModel model() throws Exception {
        return JsonSerializer.createDefault()
                .readValue(Files.readString(Path.of("model.json")), AuthorizationModel.class);
    }

    public static OpenFgaHelper helper() throws Exception {
        OpenFgaHelper helper = new OpenFgaHelper();
        helper.loadModel(model());
        return helper;
    }

    public static EventParser parser(AdminEvent event) {
        return new EventParser(event, session());
    }

    /** A parser that publishes with the given identifier: names (the default) or Keycloak ids. */
    public static EventParser parser(AdminEvent event, OpenFgaIdentifier identifier) {
        return new EventParser(event, session(), identifier);
    }

    /** An admin event as Keycloak emits it: resource path, operation and JSON representation. */
    public static AdminEvent event(ResourceType resourceType, OperationType operation, String resourcePath, String representation) {
        AuthDetails auth = new AuthDetails();
        auth.setRealmId("master");
        auth.setClientId("admin-cli");
        auth.setUserId("admin-user-id");
        auth.setIpAddress("127.0.0.1");

        AdminEvent event = new AdminEvent();
        event.setId(UUID.randomUUID().toString());
        event.setRealmId(REALM_ID);
        event.setAuthDetails(auth);
        event.setResourceType(resourceType);
        event.setOperationType(operation);
        event.setResourcePath(resourcePath);
        event.setRepresentation(representation);
        return event;
    }

    /** JSON array of role representations, as sent in role-mapping and composite events. */
    public static String roles(String... names) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"role-id-").append(names[i]).append("\",\"name\":\"").append(names[i])
              .append("\",\"composite\":false,\"clientRole\":false,\"containerId\":\"").append(REALM_ID).append("\"}");
        }
        return sb.append(']').toString();
    }

    /** An organization representation, as sent in organization membership events. */
    public static String organization(String id, String alias) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + alias + " Inc\",\"alias\":\"" + alias
                + "\",\"enabled\":true,\"domains\":[]}";
    }

    /**
     * An organization membership event as Keycloak emits it: the organization as representation, and the member's
     * username and email as details. Adding a member: organizations/{orgId}/members (the user id is only in the
     * request body). Removing one: organizations/{orgId}/members/{memberId}.
     */
    public static AdminEvent organizationMembership(OperationType operation, String orgId, String alias, String username, String memberId) {
        String path = "organizations/" + orgId + "/members" + (memberId != null ? "/" + memberId : "");
        AdminEvent event = event(ResourceType.ORGANIZATION_MEMBERSHIP, operation, path, organization(orgId, alias));
        event.setDetails(Map.of(UserModel.USERNAME, username, UserModel.EMAIL, username + "@example.org"));
        return event;
    }

    public static String group(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"path\":\"/" + name + "\"}";
    }

    /** A Keycloak session whose realm knows the fixture's roles, groups, users and clients. */
    public static KeycloakSession session() {
        RealmModel realm = mock(RealmModel.class);
        lenient().when(realm.getId()).thenReturn(REALM_ID);

        RoleModel adminCatalog = mock(RoleModel.class);
        lenient().when(adminCatalog.getName()).thenReturn(ROLE_ADMIN_CATALOG);
        lenient().when(realm.getRoleById(ROLE_ADMIN_CATALOG_ID)).thenReturn(adminCatalog);
        RoleModel accountManager = mock(RoleModel.class);
        lenient().when(accountManager.getName()).thenReturn(ROLE_ACCOUNT_MANAGER);
        lenient().when(realm.getRoleById(ROLE_ACCOUNT_MANAGER_ID)).thenReturn(accountManager);

        GroupModel analysts = mock(GroupModel.class);
        lenient().when(analysts.getName()).thenReturn(GROUP_NAME);
        lenient().when(realm.getGroupById(GROUP_ID)).thenReturn(analysts);
        GroupModel salesTeam = mock(GroupModel.class);
        lenient().when(salesTeam.getName()).thenReturn(GROUP_WITH_SPACE_NAME);
        lenient().when(realm.getGroupById(GROUP_WITH_SPACE_ID)).thenReturn(salesTeam);

        ClientModel storeApi = mock(ClientModel.class);
        lenient().when(storeApi.getId()).thenReturn(SA_CLIENT_UUID);
        lenient().when(storeApi.getClientId()).thenReturn(SA_CLIENT_ID);
        lenient().when(realm.getClientById(SA_CLIENT_UUID)).thenReturn(storeApi);
        ClientModel plannerAgent = mock(ClientModel.class);
        lenient().when(plannerAgent.getId()).thenReturn(SPIFFE_CLIENT_UUID);
        lenient().when(plannerAgent.getClientId()).thenReturn(SPIFFE_CLIENT_ID);
        lenient().when(realm.getClientById(SPIFFE_CLIENT_UUID)).thenReturn(plannerAgent);

        UserModel paula = mock(UserModel.class);
        lenient().when(paula.getId()).thenReturn(USER_ID);
        UserModel serviceAccount = mock(UserModel.class);
        lenient().when(serviceAccount.getId()).thenReturn(SA_USER_ID);
        lenient().when(serviceAccount.getServiceAccountClientLink()).thenReturn(SA_CLIENT_UUID);
        UserModel spiffeServiceAccount = mock(UserModel.class);
        lenient().when(spiffeServiceAccount.getId()).thenReturn(SPIFFE_SA_USER_ID);
        lenient().when(spiffeServiceAccount.getServiceAccountClientLink()).thenReturn(SPIFFE_CLIENT_UUID);

        UserProvider users = mock(UserProvider.class);
        lenient().when(users.getUserById(any(RealmModel.class), anyString())).thenReturn(null);
        lenient().when(users.getUserById(realm, USER_ID)).thenReturn(paula);
        lenient().when(users.getUserByUsername(realm, USERNAME)).thenReturn(paula);
        lenient().when(users.getUserById(realm, SA_USER_ID)).thenReturn(serviceAccount);
        lenient().when(users.getUserById(realm, SPIFFE_SA_USER_ID)).thenReturn(spiffeServiceAccount);

        RealmProvider realms = mock(RealmProvider.class);
        lenient().when(realms.getRealm(REALM_ID)).thenReturn(realm);

        KeycloakContext context = mock(KeycloakContext.class);
        lenient().when(context.getRealm()).thenReturn(realm);

        KeycloakSession session = mock(KeycloakSession.class);
        lenient().when(session.users()).thenReturn(users);
        lenient().when(session.realms()).thenReturn(realms);
        lenient().when(session.getContext()).thenReturn(context);
        return session;
    }
}
