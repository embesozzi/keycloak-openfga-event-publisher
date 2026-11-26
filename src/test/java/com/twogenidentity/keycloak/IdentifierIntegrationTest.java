package com.twogenidentity.keycloak;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import dev.openfga.sdk.api.client.JsonSerializer;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Tuple;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import jakarta.ws.rs.core.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
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
 * Identifier "id" (KC_SPI_..._OPENFGA_IDENTIFIER=id): roles, groups and clients are published by their Keycloak ids
 * instead of their names. The default, "name", is covered by OpenFgaEventPublisherIntegrationTest.
 *
 * Run with `mvn verify` (the jar must be packaged first). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class IdentifierIntegrationTest {

    private static final String KEYCLOAK_IMAGE = System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:26.8.2");
    private static final String OPENFGA_IMAGE = System.getProperty("openfga.image", "openfga/openfga:v1.23.0");
    private static final String PROVIDER_JAR = "target/keycloak-openfga-event-publisher.jar";
    private static final String REALM = "it";

    private static final Network NETWORK = Network.newNetwork();

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
            .withRealmImportFile("/it-realm.json")
            .withEnv("KC_SPI_EVENTS_LISTENER_OPENFGA_EVENTS_PUBLISHER_OPENFGA_IDENTIFIER", "id")
            .withEnv("KC_LOG_LEVEL", "info,com.twogenidentity.keycloak:debug")
            .withLogConsumer(frame -> {
                String line = frame.getUtf8String();
                if (line.contains("twogenidentity")) System.out.print("[keycloak] " + line);
            });

    private static RealmResource realm;
    private static OpenFgaClient openfga;

    @BeforeAll
    static void setUp() throws Exception {
        Awaitility.setDefaultTimeout(Duration.ofSeconds(15));
        Awaitility.setDefaultPollInterval(Duration.ofMillis(250));

        openfga = new OpenFgaClient(new ClientConfiguration()
                .apiUrl("http://" + OPENFGA.getHost() + ":" + OPENFGA.getMappedPort(8080)));
        openfga.setStoreId(openfga.createStore(new CreateStoreRequest().name("keycloak")).get().getId());
        WriteAuthorizationModelRequest model = JsonSerializer.createDefault()
                .readValue(Files.readString(Path.of("model.json")), WriteAuthorizationModelRequest.class);
        openfga.setAuthorizationModelId(openfga.writeAuthorizationModel(model).get().getAuthorizationModelId());

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
    @DisplayName("Realm role and composite are published by role id")
    void roles() throws Exception {
        String userId = createUser("paula");
        RoleRepresentation parent = createRole("Account Manager");
        RoleRepresentation child = createRole("view-transactions");

        realm.rolesById().addComposites(parent.getId(), List.of(child));
        realm.users().get(userId).roles().realmLevel().add(List.of(parent));

        await("composite by id").until(() -> hasTuple("role:" + parent.getId(), "parent", "role:" + child.getId()));
        await("assignment by id").until(() -> hasTuple("user:" + userId, "assignee", "role:" + parent.getId()));
    }

    @Test
    @DisplayName("Group membership and group role are published by group id")
    void groups() throws Exception {
        String userId = createUser("peter");
        String groupId = createGroup("Sales Team");
        RoleRepresentation role = createRole("view-orders");

        realm.users().get(userId).joinGroup(groupId);
        realm.groups().group(groupId).roles().realmLevel().add(List.of(role));

        await("membership by id").until(() -> hasTuple("user:" + userId, "assignee", "group:" + groupId));
        await("group role by id").until(() -> hasTuple("group:" + groupId, "parent_group", "role:" + role.getId()));
    }

    @Test
    @DisplayName("Service account is published by the client's internal id, and a client role by its role id")
    void serviceAccount() throws Exception {
        String clientUuid = createServiceAccountClient("spiffe://example.org/ns/agents/sa/planner-agent");
        String serviceAccountId = realm.clients().get(clientUuid).getServiceAccountUser().getId();
        RoleRepresentation rep = new RoleRepresentation();
        rep.setName("call-tools");
        realm.clients().get(clientUuid).roles().create(rep);
        RoleRepresentation role = realm.clients().get(clientUuid).roles().get("call-tools").toRepresentation();

        realm.users().get(serviceAccountId).roles().clientLevel(clientUuid).add(List.of(role));
        await("client role by id").until(() -> hasTuple("client:" + clientUuid, "assignee", "role:" + role.getId()));
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
}
