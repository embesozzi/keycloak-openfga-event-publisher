package com.twogenidentity.keycloak.service;

import com.twogenidentity.keycloak.utils.OpenFgaHelper;
import com.twogenidentity.keycloak.utils.OpenFgaIdentifier;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ApiToken;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.ClientCredentials;
import dev.openfga.sdk.api.configuration.Credentials;
import dev.openfga.sdk.api.configuration.ClientListStoresOptions;
import dev.openfga.sdk.api.configuration.ClientWriteOptions;
import dev.openfga.sdk.api.model.*;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import com.twogenidentity.keycloak.event.EventParser;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.utils.StringUtil;

import java.time.Duration;
import java.util.concurrent.ExecutionException;

public class OpenFgaClientHandler {

    protected final Config.Scope config;
    private final OpenFgaClient fgaClient;
    private final OpenFgaHelper fgaHelper;
    // Shared across Keycloak request threads: discovery runs once, under the lock in discoverClientConfiguration()
    private volatile boolean isClientInitialized = false;

    private final ClientWriteOptions clientWriteOptions;

    protected static final String OPENFGA_API_URL = "openfgaApiUrl";
    protected static final String OPENFGA_STORE_ID = "openfgaStoreId";
    protected static final String OPENFGA_AUTHORIZATION_MODEL_ID = "openfgaAuthorizationModelId";
    protected static final String OPENFGA_STORE_NAME = "openfgaStoreName";
    protected static final String DEFAULT_STORE_NAME = "keycloak";
    protected static final String OPENFGA_IDENTIFIER = "openfgaIdentifier";
    protected static final String OPENFGA_CREDENTIALS_METHOD = "openfgaCredentialsMethod";
    protected static final String OPENFGA_API_TOKEN = "openfgaApiToken";
    protected static final String OPENFGA_CLIENT_ID = "openfgaClientId";
    protected static final String OPENFGA_CLIENT_SECRET = "openfgaClientSecret";
    protected static final String OPENFGA_API_TOKEN_ISSUER = "openfgaApiTokenIssuer";
    protected static final String OPENFGA_API_AUDIENCE = "openfgaApiAudience";
    protected static final String OPENFGA_SCOPES = "openfgaScopes";

    private static final Logger LOG = Logger.getLogger(OpenFgaClientHandler.class);

    public OpenFgaClientHandler(Config.Scope config ) throws FgaInvalidParameterException {
        this.config = config;

        Credentials credentials = credentials(config);
        ClientConfiguration configuration = new ClientConfiguration()
                .apiUrl(getOpenFgaApiUrl())
                .credentials(credentials)
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5));
        LOG.infof("Authenticating to OpenFGA with: %s", credentials.getCredentialsMethod());

        // Explicit ids are used as given; the model itself is still read on first use (discoverClientConfiguration)
        if (StringUtil.isNotBlank(getOpenFgaOpenStoreId())) {
            configuration.storeId(getOpenFgaOpenStoreId());
            if (StringUtil.isNotBlank(getOpenFgaAuthorizationModelId())) {
                configuration.authorizationModelId(getOpenFgaAuthorizationModelId());
            }
        }

        LOG.infof("Publishing roles, groups and clients by %s", getOpenFgaIdentifier());
        this.fgaHelper = new OpenFgaHelper();
        this.clientWriteOptions = new ClientWriteOptions();
        this.fgaClient = new OpenFgaClient(configuration);
    }

    public void publish(String eventId , EventParser event) throws FgaInvalidParameterException, ExecutionException, InterruptedException {
        if(!this.isClientInitialized && !this.discoverClientConfiguration()) {
            LOG.errorf("Unable to initialized OpenFga client. Discarding  event %s, %s",  eventId, event.toString());
        }
        else {
            ClientWriteRequest request  = fgaHelper.toClientWriteRequest(event);
            if(fgaHelper.isAvailableClientRequest(request)) {
                LOG.debugf("Publishing event id %s", eventId);
                var response = fgaClient.write(request, this.clientWriteOptions).get();
                LOG.debugf("Successfully sent tuple key to OpenFga, response: %s", response);
            }
        }
    }

    /**
     * Finds the store and the authorization model once, then loads the model:
     * the configured store id, or else the store named openfgaStoreName (default "keycloak");
     * the configured model id, or else the latest model of that store.
     */
    private synchronized boolean discoverClientConfiguration() throws FgaInvalidParameterException, ExecutionException, InterruptedException {
        if (this.isClientInitialized) {
            return true; // another thread discovered it while this one waited
        }
        if (StringUtil.isBlank(getOpenFgaOpenStoreId())) {
            Store store = findStoreByName(getOpenFgaStoreName());
            if (store == null) {
                LOG.errorf("No OpenFGA store named '%s'. Create it, or set the store name or store id in the configuration", getOpenFgaStoreName());
                return false;
            }
            LOG.infof("Found store '%s', id: %s", store.getName(), store.getId());
            this.fgaClient.setStoreId(store.getId());
        }

        AuthorizationModel model;
        if (StringUtil.isNotBlank(getOpenFgaAuthorizationModelId())) {
            model = fgaClient.readAuthorizationModel().get().getAuthorizationModel();
        } else {
            var models = fgaClient.readAuthorizationModels().get().getAuthorizationModels();
            model = models.isEmpty() ? null : models.get(0); // the latest model comes first
        }
        if (model == null) {
            LOG.errorf("No authorization model found in the OpenFGA store (store id: '%s', name: '%s')",
                    getOpenFgaOpenStoreId(), getOpenFgaStoreName());
            return false;
        }
        LOG.infof("Using authorization model id: %s", model.getId());
        this.fgaClient.setAuthorizationModelId(model.getId());
        this.fgaHelper.loadModel(model);
        this.isClientInitialized = true;
        return true;
    }

    private Store findStoreByName(String name) throws FgaInvalidParameterException, ExecutionException, InterruptedException {
        String continuationToken = null;
        do {
            ClientListStoresOptions options = new ClientListStoresOptions();
            if (StringUtil.isNotBlank(continuationToken)) {
                options.continuationToken(continuationToken);
            }
            ListStoresResponse response = fgaClient.listStores(options).get();
            for (Store store : response.getStores()) {
                if (name.equals(store.getName())) {
                    return store;
                }
            }
            continuationToken = response.getContinuationToken();
        } while (StringUtil.isNotBlank(continuationToken));
        return null;
    }

    /**
     * How to authenticate to OpenFGA (openfgaCredentialsMethod):
     * <ul>
     *   <li>none (default): no credentials</li>
     *   <li>api_token: a pre-shared key (openfgaApiToken), sent as a bearer token</li>
     *   <li>client_credentials: an OAuth 2.0 access token (openfgaClientId, openfgaClientSecret, openfgaApiTokenIssuer,
     *       and optionally openfgaApiAudience and openfgaScopes)</li>
     * </ul>
     * A missing setting fails at startup with its name; values are never put in messages or logs.
     */
    static Credentials credentials(Config.Scope config) {
        String method = StringUtil.isBlank(config.get(OPENFGA_CREDENTIALS_METHOD)) ? "none" : config.get(OPENFGA_CREDENTIALS_METHOD).trim();
        if (method.equalsIgnoreCase("none")) {
            return new Credentials();
        }
        if (method.equalsIgnoreCase("api_token")) {
            return new Credentials(new ApiToken(required(config, OPENFGA_API_TOKEN, "api_token")));
        }
        if (method.equalsIgnoreCase("client_credentials")) {
            return new Credentials(new ClientCredentials()
                    .clientId(required(config, OPENFGA_CLIENT_ID, "client_credentials"))
                    .clientSecret(required(config, OPENFGA_CLIENT_SECRET, "client_credentials"))
                    .apiTokenIssuer(required(config, OPENFGA_API_TOKEN_ISSUER, "client_credentials"))
                    .apiAudience(optional(config, OPENFGA_API_AUDIENCE))
                    .scopes(optional(config, OPENFGA_SCOPES)));
        }
        throw new IllegalArgumentException("Invalid OpenFGA credentials method '" + method
                + "': use none, api_token or client_credentials");
    }

    private static String required(Config.Scope config, String key, String method) {
        String value = config.get(key);
        if (StringUtil.isBlank(value)) {
            throw new IllegalArgumentException("Missing OpenFGA setting " + key + ", required by the credentials method " + method);
        }
        return value.trim();
    }

    private static String optional(Config.Scope config, String key) {
        String value = config.get(key);
        return StringUtil.isBlank(value) ? null : value.trim();
    }

    public String getOpenFgaApiUrl() {
        return config.get(OPENFGA_API_URL) != null ? config.get(OPENFGA_API_URL) : "http://openfga:8080";
    }

    public String getOpenFgaOpenStoreId() {
        return config.get(OPENFGA_STORE_ID) != null ? config.get(OPENFGA_STORE_ID) : "";
    }

    /** What identifies roles, groups and clients in tuples: "name" (default) or "id". */
    public OpenFgaIdentifier getOpenFgaIdentifier() {
        String value = config.get(OPENFGA_IDENTIFIER);
        if (StringUtil.isBlank(value)) {
            return OpenFgaIdentifier.NAME;
        }
        for (OpenFgaIdentifier identifier : OpenFgaIdentifier.values()) {
            if (identifier.name().equalsIgnoreCase(value.trim())) {
                return identifier;
            }
        }
        throw new IllegalArgumentException("Invalid OpenFGA identifier '" + value + "': use 'name' or 'id'");
    }

    public String getOpenFgaStoreName() {
        return StringUtil.isNotBlank(config.get(OPENFGA_STORE_NAME)) ? config.get(OPENFGA_STORE_NAME) : DEFAULT_STORE_NAME;
    }

    public String getOpenFgaAuthorizationModelId() {
        return config.get(OPENFGA_AUTHORIZATION_MODEL_ID) != null? config.get(OPENFGA_AUTHORIZATION_MODEL_ID) : "";
    }
}
