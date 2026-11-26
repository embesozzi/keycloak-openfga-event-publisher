package com.twogenidentity.keycloak.service;

import com.twogenidentity.keycloak.utils.OpenFgaHelper;
import com.twogenidentity.keycloak.utils.OpenFgaIdentifier;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
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

    private static final Logger LOG = Logger.getLogger(OpenFgaClientHandler.class);

    public OpenFgaClientHandler(Config.Scope config ) throws FgaInvalidParameterException {
        this.config = config;

        ClientConfiguration configuration = new ClientConfiguration()
                .apiUrl(getOpenFgaApiUrl())
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5));

        // Explicit ids are used as given; the model itself is still read on first use (discoverClientConfiguration)
        if (StringUtil.isNotBlank(getOpenFgaOpenStoreId())) {
            configuration.storeId(getOpenFgaOpenStoreId());
            if (StringUtil.isNotBlank(getOpenFgaAuthorizationModelId())) {
                configuration.authorizationModelId(getOpenFgaAuthorizationModelId());
            }
        }

        LOG.infof("Publishing roles, groups and clients by %s", getOpenFgaIdentifier().name().toLowerCase(java.util.Locale.ROOT));
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
        try {
            return OpenFgaIdentifier.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid OpenFGA identifier '" + value + "': use 'name' or 'id'");
        }
    }

    public String getOpenFgaStoreName() {
        return StringUtil.isNotBlank(config.get(OPENFGA_STORE_NAME)) ? config.get(OPENFGA_STORE_NAME) : DEFAULT_STORE_NAME;
    }

    public String getOpenFgaAuthorizationModelId() {
        return config.get(OPENFGA_AUTHORIZATION_MODEL_ID) != null? config.get(OPENFGA_AUTHORIZATION_MODEL_ID) : "";
    }
}
