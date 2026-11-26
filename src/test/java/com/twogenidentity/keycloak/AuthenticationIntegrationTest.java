package com.twogenidentity.keycloak;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import dev.openfga.sdk.api.client.JsonSerializer;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.configuration.ApiToken;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientCredentials;
import dev.openfga.sdk.api.configuration.Credentials;
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
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The extension authenticates to an OpenFGA server that requires it:
 * a pre-shared key (api_token), or an OIDC access token from Keycloak itself (client_credentials).
 * Each test starts its own OpenFGA and Keycloak, because the authentication is set when they start.
 *
 * Run with `mvn verify` (the jar must be packaged first). Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuthenticationIntegrationTest {

    private static final String KEYCLOAK_IMAGE = System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:26.8.2");
    private static final String OPENFGA_IMAGE = System.getProperty("openfga.image", "openfga/openfga:v1.23.0");
    private static final String PROVIDER_JAR = "target/keycloak-openfga-event-publisher.jar";
    private static final String CONFIG = "KC_SPI_EVENTS_LISTENER_OPENFGA_EVENTS_PUBLISHER_";
    private static final String REALM = "it";

    // Keycloak as seen from the other containers; also the issuer of every token (KC_HOSTNAME), so tokens
    // requested from the host through the mapped port carry the same issuer that OpenFGA checks
    private static final String KEYCLOAK_URL = "http://keycloak:8080";
    private static final String ISSUER = KEYCLOAK_URL + "/realms/" + REALM;
    private static final String TOKEN_ENDPOINT_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";

    @Test
    @DisplayName("api_token: publishes to an OpenFGA protected by a pre-shared key")
    void preSharedKey() throws Exception {
        try (Network network = Network.newNetwork();
             GenericContainer<?> openfga = openfga(network, Map.of(
                     "OPENFGA_AUTHN_METHOD", "preshared",
                     "OPENFGA_AUTHN_PRESHARED_KEYS", "it-preshared-key"))) {

            assertUnauthenticatedRejected(openfga);
            OpenFgaClient fga = client(openfga, new Credentials(new ApiToken("it-preshared-key")));
            createStore(fga);

            try (KeycloakContainer kc = keycloak(network, Map.of(
                    CONFIG + "OPENFGA_CREDENTIALS_METHOD", "api_token",
                    CONFIG + "OPENFGA_API_TOKEN", "it-preshared-key"))) {
                String userId = assignRole(kc, "paula", "admin-catalog");
                awaitTuple(fga, "user:" + userId, "role:admin-catalog");
            }
        }
    }

    @Test
    @DisplayName("client_credentials: publishes to an OpenFGA protected by OIDC, with a token from Keycloak")
    void oidcClientCredentials() throws Exception {
        try (Network network = Network.newNetwork();
             // Keycloak first: OpenFGA reads the issuer's OIDC discovery document
             KeycloakContainer kc = keycloak(network, Map.of(
                     CONFIG + "OPENFGA_CREDENTIALS_METHOD", "client_credentials",
                     CONFIG + "OPENFGA_CLIENT_ID", "openfga-publisher",
                     CONFIG + "OPENFGA_CLIENT_SECRET", "openfga-publisher-secret",
                     CONFIG + "OPENFGA_API_TOKEN_ISSUER", KEYCLOAK_URL + TOKEN_ENDPOINT_PATH,
                     CONFIG + "OPENFGA_API_AUDIENCE", "openfga"));
             GenericContainer<?> openfga = openfga(network, Map.of(
                     "OPENFGA_AUTHN_METHOD", "oidc",
                     "OPENFGA_AUTHN_OIDC_ISSUER", ISSUER,
                     "OPENFGA_AUTHN_OIDC_AUDIENCE", "openfga"))) {

            assertUnauthenticatedRejected(openfga);
            // The test gets its token the same way, from the host through the mapped port
            OpenFgaClient fga = client(openfga, new Credentials(new ClientCredentials()
                    .clientId("openfga-publisher")
                    .clientSecret("openfga-publisher-secret")
                    .apiTokenIssuer(kc.getAuthServerUrl() + TOKEN_ENDPOINT_PATH)
                    .apiAudience("openfga")));
            createStore(fga);

            String userId = assignRole(kc, "peter", "view-orders");
            awaitTuple(fga, "user:" + userId, "role:view-orders");
        }
    }

    // --- containers ---

    private static GenericContainer<?> openfga(Network network, Map<String, String> authentication) {
        GenericContainer<?> openfga = new GenericContainer<>(OPENFGA_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("openfga")
                .withCommand("run")
                .withEnv(authentication)
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/healthz").forPort(8080));
        openfga.start();
        return openfga;
    }

    private static KeycloakContainer keycloak(Network network, Map<String, String> config) {
        KeycloakContainer keycloak = new KeycloakContainer(KEYCLOAK_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("keycloak")
                .withProviderLibsFrom(List.of(new File(PROVIDER_JAR)))
                .withRealmImportFile("/it-realm.json")
                .withEnv("KC_HOSTNAME", KEYCLOAK_URL)
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

    private static String apiUrl(GenericContainer<?> openfga) {
        return "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
    }

    private static OpenFgaClient client(GenericContainer<?> openfga, Credentials credentials) throws Exception {
        return new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl(openfga)).credentials(credentials));
    }

    /** Proves the server really requires authentication, so the test cannot pass without it. */
    private static void assertUnauthenticatedRejected(GenericContainer<?> openfga) throws Exception {
        OpenFgaClient anonymous = new OpenFgaClient(new ClientConfiguration().apiUrl(apiUrl(openfga)));
        assertThrows(ExecutionException.class, () -> anonymous.listStores().get());
    }

    private static void createStore(OpenFgaClient fga) throws Exception {
        fga.setStoreId(fga.createStore(new CreateStoreRequest().name("keycloak")).get().getId());
        WriteAuthorizationModelRequest model = JsonSerializer.createDefault()
                .readValue(Files.readString(Path.of("model.json")), WriteAuthorizationModelRequest.class);
        fga.setAuthorizationModelId(fga.writeAuthorizationModel(model).get().getAuthorizationModelId());
    }

    private static boolean hasTuple(OpenFgaClient fga, String user, String object) throws Exception {
        List<Tuple> tuples = fga.read(new ClientReadRequest().user(user).relation("assignee")._object(object))
                .get().getTuples();
        return tuples != null && !tuples.isEmpty();
    }

    private static void awaitTuple(OpenFgaClient fga, String user, String object) {
        Awaitility.await("tuple written with authentication")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> hasTuple(fga, user, object));
    }
}
