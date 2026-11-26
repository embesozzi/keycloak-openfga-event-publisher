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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which OpenFGA store the extension publishes to when OpenFGA holds more than one store.
 * Each test starts its own OpenFGA and Keycloak, because the stores must exist before Keycloak starts.
 *
 * Run with `mvn verify` (the jar must be packaged first). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class StoreSelectionIntegrationTest {

    private static final String KEYCLOAK_IMAGE = System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:26.8.2");
    private static final String OPENFGA_IMAGE = System.getProperty("openfga.image", "openfga/openfga:v1.23.0");
    private static final String PROVIDER_JAR = "target/keycloak-openfga-event-publisher.jar";
    private static final String CONFIG = "KC_SPI_EVENTS_LISTENER_OPENFGA_EVENTS_PUBLISHER_";
    private static final String REALM = "it";

    /** A store with the repository's model, as the extension would find it. */
    private record Store(String id, String modelId) {
    }

    @Test
    @DisplayName("Without configuration, events go to the store named 'keycloak', not to the first store")
    void defaultStoreName() throws Exception {
        try (Network network = Network.newNetwork(); GenericContainer<?> openfga = openfga(network)) {
            OpenFgaClient fga = client(openfga);
            Store other = createStore(fga, "other-app"); // created first, so OpenFGA lists it first
            Store keycloak = createStore(fga, "keycloak");

            try (KeycloakContainer kc = keycloak(network, Map.of())) {
                String userId = assignRole(kc, "paula", "analyst-catalog");

                awaitTuple(fga, keycloak, "user:" + userId, "role:analyst-catalog");
                assertFalse(hasTuple(fga, other, "user:" + userId, "role:analyst-catalog"), "tuple written to the wrong store");
            }
        }
    }

    @Test
    @DisplayName("The store name can be configured")
    void configuredStoreName() throws Exception {
        try (Network network = Network.newNetwork(); GenericContainer<?> openfga = openfga(network)) {
            OpenFgaClient fga = client(openfga);
            createStore(fga, "keycloak");
            Store tenant = createStore(fga, "tenant-a");

            try (KeycloakContainer kc = keycloak(network, Map.of(CONFIG + "OPENFGA_STORE_NAME", "tenant-a"))) {
                String userId = assignRole(kc, "peter", "analyst-catalog");

                awaitTuple(fga, tenant, "user:" + userId, "role:analyst-catalog");
            }
        }
    }

    @Test
    @DisplayName("Explicit store and model ids are used, and the model is loaded")
    void explicitStoreAndModelIds() throws Exception {
        try (Network network = Network.newNetwork(); GenericContainer<?> openfga = openfga(network)) {
            OpenFgaClient fga = client(openfga);
            createStore(fga, "keycloak");
            Store configured = createStore(fga, "configured");

            try (KeycloakContainer kc = keycloak(network, Map.of(
                    CONFIG + "OPENFGA_STORE_ID", configured.id(),
                    CONFIG + "OPENFGA_AUTHORIZATION_MODEL_ID", configured.modelId()))) {
                String userId = assignRole(kc, "richard", "admin-catalog");

                awaitTuple(fga, configured, "user:" + userId, "role:admin-catalog");
            }
        }
    }

    // --- containers ---

    // Started here, not by @Container: each test needs its own OpenFGA
    private static GenericContainer<?> openfga(Network network) {
        GenericContainer<?> openfga = new GenericContainer<>(OPENFGA_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("openfga")
                .withCommand("run")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/healthz").forPort(8080));
        openfga.start();
        return openfga;
    }

    private static KeycloakContainer keycloak(Network network, Map<String, String> config) {
        KeycloakContainer keycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(network)
                .withProviderLibsFrom(List.of(new File(PROVIDER_JAR)))
                .withRealmImportFile("/it-realm.json")
                .withEnv("KC_LOG_LEVEL", "info,com.twogenidentity.keycloak:debug")
                .withEnv(config)
                .withLogConsumer(frame -> {
                    String line = frame.getUtf8String();
                    if (line.contains("twogenidentity")) System.out.print("[keycloak] " + line);
                });
        keycloak.start();
        return keycloak;
    }

    // --- Keycloak ---

    /** Creates a user and a realm role, assigns the role, and returns the user id. */
    private static String assignRole(KeycloakContainer kc, String username, String roleName) {
        RealmResource realm = KeycloakBuilder.builder()
                .serverUrl(kc.getAuthServerUrl())
                .realm(REALM)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId("it-admin")
                .clientSecret("it-admin-secret")
                .build()
                .realm(REALM);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        String userId;
        try (Response response = realm.users().create(user)) {
            userId = CreatedResponseUtil.getCreatedId(response);
        }
        RoleRepresentation role = new RoleRepresentation();
        role.setName(roleName);
        realm.roles().create(role);
        realm.users().get(userId).roles().realmLevel().add(List.of(realm.roles().get(roleName).toRepresentation()));
        return userId;
    }

    // --- OpenFGA ---

    private static OpenFgaClient client(GenericContainer<?> openfga) throws Exception {
        return new OpenFgaClient(new ClientConfiguration()
                .apiUrl("http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080)));
    }

    private static Store createStore(OpenFgaClient fga, String name) throws Exception {
        String storeId = fga.createStore(new CreateStoreRequest().name(name)).get().getId();
        fga.setStoreId(storeId);
        WriteAuthorizationModelRequest model = JsonSerializer.createDefault()
                .readValue(Files.readString(Path.of("model.json")), WriteAuthorizationModelRequest.class);
        return new Store(storeId, fga.writeAuthorizationModel(model).get().getAuthorizationModelId());
    }

    private static boolean hasTuple(OpenFgaClient fga, Store store, String user, String object) throws Exception {
        fga.setStoreId(store.id()); // reads have no per-request store id in the SDK
        List<Tuple> tuples = fga.read(new ClientReadRequest().user(user).relation("assignee")._object(object))
                .get().getTuples();
        return tuples != null && !tuples.isEmpty();
    }

    private static void awaitTuple(OpenFgaClient fga, Store store, String user, String object) {
        Awaitility.await("tuple written to store " + store.id())
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> hasTuple(fga, store, user, object));
    }
}
