package com.twogenidentity.keycloak.service;

import com.twogenidentity.keycloak.event.EventParser;
import com.twogenidentity.keycloak.utils.OpenFgaHelper;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.*;
import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.ListStoresResponse;
import dev.openfga.sdk.api.model.ReadAuthorizationModelsResponse;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.utils.StringUtil;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

public class OpenFgaClientHandler {

    protected final Config.Scope config;
    protected final KeycloakSession session;
    private final Map<String, OpenFgaClient> fgaClients = new ConcurrentHashMap<>();
    private final OpenFgaHelper fgaHelper;
    private final ClientWriteOptions clientWriteOptions;

    protected static final String OPENFGA_CREDENTIALS_METHOD = "openfga-credentials-method";
    protected static final String OPENFGA_API_URL = "openfga-api-url";
    protected static final String OPENFGA_API_TOKEN = "openfga-api-token";
    protected static final String OPENFGA_CLIENT_ID = "openfga-client-id";
    protected static final String OPENFGA_CLIENT_SECRET = "openfga-client-secret";
    protected static final String OPENFGA_API_TOKEN_ISSUER = "openfga-api-token-issuer";
    protected static final String OPENFGA_AUDIENCE = "openfga-audience";

    private static final Logger LOG = Logger.getLogger(OpenFgaClientHandler.class);

    public OpenFgaClientHandler(Config.Scope config, KeycloakSession session) throws FgaInvalidParameterException {
        this.config = config;
        this.session = session;
        this.fgaHelper = new OpenFgaHelper();
        this.clientWriteOptions = new ClientWriteOptions();
    }

    public void publish(String eventId, EventParser event) throws FgaInvalidParameterException, ExecutionException, InterruptedException {
        if (!this.fgaClients.containsKey(event.getSelectedRealmId()) && !this.discoverClientConfiguration(event.getSelectedRealmId())) {
            LOG.errorf("Unable to initialized OpenFga client. Discarding  event %s, %s", eventId, event.toString());
        } else {
            ClientWriteRequest request = fgaHelper.toClientWriteRequest(event);
            if (fgaHelper.isAvailableClientRequest(request)) {
                LOG.debugf("Publishing event id %", eventId);
                var response = fgaClients.get(event.getSelectedRealmId()).write(request, this.clientWriteOptions).get();
                LOG.debugf("Successfully sent tuple key to OpenFga, response: %s", response);
            }
        }
    }

    private OpenFgaClient getOpenFGAClient() throws FgaInvalidParameterException {
        ClientConfiguration configuration = new ClientConfiguration()
                .apiUrl(getOpenFgaApiUrl())
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5));

        if ((getCredentialMethod() == CredentialsMethod.API_TOKEN)) {
            LOG.info("API Token provided in config, will use it for authentication with OpenFGA");
            ApiToken token = new ApiToken(getOpenFgaApiToken());
            Credentials credentials = new Credentials(token);
            configuration.credentials(credentials);
        } else if (getCredentialMethod() == CredentialsMethod.CLIENT_CREDENTIALS) {
            LOG.info("Client Credentials provided in config, will use it for authentication with OpenFGA");
            ClientCredentials clientCredentials = getOpenFgaApiClientCredentials();
            Credentials credentials = new Credentials(clientCredentials);
            configuration.credentials(credentials);
        } else {
            LOG.warn("No OpenFGA API Token or Client Credentials provided in the configuration, using default credentials");
        }

        return new OpenFgaClient(configuration);
    }

    private boolean discoverClientConfiguration(String realName) throws FgaInvalidParameterException, ExecutionException, InterruptedException {
        LOG.info("Discover store and authorization model");
        OpenFgaClient fgaClient = getOpenFGAClient();
        ListStoresResponse stores = fgaClient.listStores().get();
        if (!stores.getStores().isEmpty()) {
            Store store = stores.getStores().stream().filter(s -> s.getName().equalsIgnoreCase(realName)).findFirst()
                    .orElseThrow(() -> new FgaInvalidParameterException("No store found for realm: " + realName));
            LOG.infof("Found store id: %s", store.getId());
            fgaClient.setStoreId(store.getId());
            ReadAuthorizationModelsResponse authorizationModels = fgaClient.readAuthorizationModels().get();
            if (!authorizationModels.getAuthorizationModels().isEmpty()) {
                AuthorizationModel model = authorizationModels.getAuthorizationModels().get(0);
                LOG.infof("Found authorization model id: %s", model.getId());
                fgaClient.setAuthorizationModelId(model.getId());
                this.fgaHelper.loadModel(model);
                fgaClients.put(realName, fgaClient);
                return true;
            }
        }
        return false;
    }

    public String getOpenFgaApiUrl() {
        var api_url = config.get(OPENFGA_API_URL);
        LOG.infof("OpenFGA API URL: %s", api_url);
        if (StringUtil.isBlank(api_url)) {
            LOG.warnf("OpenFGA API URL is not provided in the configuration");
        }
        return api_url;
    }

    public String getOpenFgaApiToken() throws FgaInvalidParameterException {
        if (StringUtil.isBlank(config.get(OPENFGA_API_TOKEN))) {
            LOG.warn("OpenFGA API Token is not provided in the configuration");
            throw new FgaInvalidParameterException("OpenFGA client credentials are not provided in the configuration");
        }
        return config.get(OPENFGA_API_TOKEN) != null ? config.get(OPENFGA_API_TOKEN) : "";
    }

    public CredentialsMethod getCredentialMethod() {
        return config.get(OPENFGA_CREDENTIALS_METHOD) != null ? CredentialsMethod.valueOf(config.get(OPENFGA_CREDENTIALS_METHOD)) : CredentialsMethod.NONE;
    }

    public ClientCredentials getOpenFgaApiClientCredentials() throws FgaInvalidParameterException {
        String clientId = config.get(OPENFGA_CLIENT_ID);
        String clientSecret = config.get(OPENFGA_CLIENT_SECRET);
        String issuer = config.get(OPENFGA_API_TOKEN_ISSUER);
        String audience = config.get(OPENFGA_AUDIENCE);
        if (StringUtil.isBlank(clientId) || StringUtil.isBlank(clientSecret) || StringUtil.isBlank(issuer) || StringUtil.isBlank(audience)) {
            LOG.error("OpenFGA client credentials are not provided in the configuration");
            throw new FgaInvalidParameterException("OpenFGA client credentials are not provided in the configuration");
        }
        LOG.infof("OpenFGA Client ID: %s", clientId);
        LOG.infof("OpenFGA Token Issuer: %s", issuer);
        LOG.infof("OpenFGA Client audience: %s", audience);
        return new ClientCredentials()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .apiTokenIssuer(issuer)
                .apiAudience(audience);
    }
}
