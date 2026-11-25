package com.twogenidentity.keycloak;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import dev.openfga.sdk.api.client.ApiClient;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Tuple;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * Real Keycloak with the packaged provider jar, real OpenFGA with the repository's model.json.
 * Each test makes changes through the Keycloak admin client (the same Admin REST API the Admin Console uses),
 * then asks OpenFGA what it now holds. All tests share one Keycloak, so they also cover several events in a row.
 *
 * Run with `mvn verify` (the jar must be packaged first). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class OpenFgaEventPublisherIntegrationTest {

    private static final String KEYCLOAK_IMAGE = System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:25.0.1");
    private static final String OPENFGA_IMAGE = System.getProperty("openfga.image", "openfga/openfga:v1.5.5");
    private static final String PROVIDER_JAR = "target/keycloak-openfga-event-publisher.jar";
    private static final String REALM = "it";

    private static final Network NETWORK = Network.newNetwork();

    // The extension's default API URL is http://openfga:8080, so the alias is all the configuration it needs
    @Container
    static final GenericContainer<?> OPENFGA = new GenericContainer<>(OPENFGA_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("openfga")
            .withCommand("run")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/healthz").forPort(8080));

    @Container
    static final KeycloakContainer KEYCLOAK = new KeycloakContainer(KEYCLOAK_IMAGE)
            .withNetwork(NETWORK)
            .dependsOn(OPENFGA)
            .withProviderLibsFrom(List.of(new File(PROVIDER_JAR)))
            // Realm "it": plain HTTP allowed, OpenFGA publisher and admin events on, and an admin client for the tests
            .withRealmImportFile("/integration-test-realm.json")
            .withEnv("KC_LOG_LEVEL", "info,com.twogenidentity.keycloak:debug")
            .withLogConsumer(frame -> {
                String line = frame.getUtf8String();
                if (line.contains("twogenidentity")) System.out.print("[keycloak] " + line);
            });

    private static RealmResource realm;
    private static OpenFgaClient openfga;

    @BeforeAll
    static void setUp() throws Exception {
        // Every await() polls OpenFGA every 250 ms, for up to 15 s
        Awaitility.setDefaultTimeout(Duration.ofSeconds(15));
        Awaitility.setDefaultPollInterval(Duration.ofMillis(250));

        // The store and model must exist before the first event: the extension discovers them on first use
        openfga = new OpenFgaClient(new ClientConfiguration()
                .apiUrl("http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080)));
        openfga.setStoreId(openfga.createStore(new CreateStoreRequest().name("keycloak")).get().getId());
        WriteAuthorizationModelRequest model = new ApiClient().getObjectMapper()
                .readValue(Files.readString(Path.of("model.json")), WriteAuthorizationModelRequest.class);
        openfga.setAuthorizationModelId(openfga.writeAuthorizationModel(model).get().getAuthorizationModelId());

        // Admin client logged in to the test realm itself (client credentials), so the master realm is not involved
        realm = KeycloakBuilder.builder()
                .serverUrl(KEYCLOAK.getAuthServerUrl())
                .realm(REALM)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId("it-admin")
                .clientSecret("it-admin-secret")
                .build()
                .realm(REALM);
    }

    @Test
    @DisplayName("User role assignment writes user:<id> assignee role:<name>; removal deletes it")
    void userRoleAssignment() throws Exception {
        String userId = createUser("paula");
        RoleRepresentation role = createRole("analyst-catalog");

        realm.users().get(userId).roles().realmLevel().add(List.of(role));
        await("tuple written").until(() -> hasTuple("user:" + userId, "assignee", "role:analyst-catalog"));

        realm.users().get(userId).roles().realmLevel().remove(List.of(role));
        await("tuple deleted").until(() -> !hasTuple("user:" + userId, "assignee", "role:analyst-catalog"));
    }

    @Test
    @DisplayName("Composite role: the user of the parent role is an assignee of the child role")
    void compositeRole() throws Exception {
        String userId = createUser("richard");
        RoleRepresentation parent = createRole("admin-catalog");
        RoleRepresentation child = createRole("edit-product");

        // roles-by-id, as the Admin Console does
        realm.rolesById().addComposites(parent.getId(), List.of(child));
        realm.users().get(userId).roles().realmLevel().add(List.of(parent));

        await("composite tuple written").until(() -> hasTuple("role:admin-catalog", "parent", "role:edit-product"));
        await("assignee inherited").until(() -> check("user:" + userId, "assignee", "role:edit-product"));
    }

    @Test
    @DisplayName("Group membership writes user:<id> assignee group:<name>; leaving deletes it")
    void groupMembership() throws Exception {
        String userId = createUser("peter");
        String groupId = createGroup("analysts");

        realm.users().get(userId).joinGroup(groupId);
        await("tuple written").until(() -> hasTuple("user:" + userId, "assignee", "group:analysts"));

        realm.users().get(userId).leaveGroup(groupId);
        await("tuple deleted").until(() -> !hasTuple("user:" + userId, "assignee", "group:analysts"));
    }

    @Test
    @Disabled("Bug, issue #4 (BACKLOG.md): group role mappings publish group:<id>, memberships publish group:<name>")
    @DisplayName("Group role: members of the group are assignees of the role")
    void groupRole() throws Exception {
        String userId = createUser("mary");
        String groupId = createGroup("viewers");
        RoleRepresentation role = createRole("view-product");

        realm.users().get(userId).joinGroup(groupId);
        realm.groups().group(groupId).roles().realmLevel().add(List.of(role));

        await("assignee inherited").until(() -> check("user:" + userId, "assignee", "role:view-product"));
    }

    @Test
    @DisplayName("Service account realm role writes client:<clientId> assignee role:<name>")
    void serviceAccountRealmRole() throws Exception {
        String clientUuid = createServiceAccountClient("store-api");
        String serviceAccountId = realm.clients().get(clientUuid).getServiceAccountUser().getId();
        RoleRepresentation role = createRole("catalog-reader");

        realm.users().get(serviceAccountId).roles().realmLevel().add(List.of(role));
        await("tuple written").until(() -> hasTuple("client:store-api", "assignee", "role:catalog-reader"));
    }

    @Test
    @DisplayName("Service account client role writes client:<clientId> assignee role:<name>")
    void serviceAccountClientRole() throws Exception {
        String clientUuid = createServiceAccountClient("orders-api");
        String serviceAccountId = realm.clients().get(clientUuid).getServiceAccountUser().getId();
        RoleRepresentation rep = new RoleRepresentation();
        rep.setName("read-orders");
        realm.clients().get(clientUuid).roles().create(rep);
        RoleRepresentation role = realm.clients().get(clientUuid).roles().get("read-orders").toRepresentation();

        realm.users().get(serviceAccountId).roles().clientLevel(clientUuid).add(List.of(role));
        await("tuple written").until(() -> hasTuple("client:orders-api", "assignee", "role:read-orders"));
    }

    // --- Keycloak ---

    private static String createUser(String username) {
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(username);
        rep.setEnabled(true);
        try (Response response = realm.users().create(rep)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static RoleRepresentation createRole(String name) {
        RoleRepresentation rep = new RoleRepresentation();
        rep.setName(name);
        realm.roles().create(rep);
        return realm.roles().get(name).toRepresentation();
    }

    private static String createGroup(String name) {
        GroupRepresentation rep = new GroupRepresentation();
        rep.setName(name);
        try (Response response = realm.groups().add(rep)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String createServiceAccountClient(String clientId) {
        ClientRepresentation rep = new ClientRepresentation();
        rep.setClientId(clientId);
        rep.setEnabled(true);
        rep.setPublicClient(false);
        rep.setServiceAccountsEnabled(true);
        try (Response response = realm.clients().create(rep)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    // --- OpenFGA ---

    private static boolean hasTuple(String user, String relation, String object) throws Exception {
        List<Tuple> tuples = openfga.read(new ClientReadRequest().user(user).relation(relation)._object(object))
                .get().getTuples();
        return tuples != null && !tuples.isEmpty();
    }

    private static boolean check(String user, String relation, String object) throws Exception {
        return Boolean.TRUE.equals(openfga.check(new ClientCheckRequest().user(user).relation(relation)._object(object))
                .get().getAllowed());
    }
}
