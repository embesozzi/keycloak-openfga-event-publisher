package com.twogenidentity.keycloak;


import dev.openfga.sdk.errors.FgaInvalidParameterException;
import com.twogenidentity.keycloak.service.OpenFgaClientHandler;
import org.keycloak.Config.Scope;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class OpenFgaEventListenerProviderFactory implements EventListenerProviderFactory {

	private static final String PROVIDER_ID = "openfga-events-publisher";
	private OpenFgaEventListenerProvider instance;
	private Scope config;
	private OpenFgaClientHandler client;

	@Override
	public EventListenerProvider create(KeycloakSession session) {
		if (client == null) {
			try {
				client = new OpenFgaClientHandler(config);
			} catch (FgaInvalidParameterException e) {
				throw new RuntimeException(e);
			}
			instance = new OpenFgaEventListenerProvider(client, session);
		}
		return instance;
	}

	@Override
	public String getId() {
		return PROVIDER_ID;
	}

	@Override
	public void init(Scope config) {
		this.config = config;

	}

	@Override
	public void postInit(KeycloakSessionFactory arg0) {
		// ignore
	}

	@Override
	public void close() {
		// ignore
	}
}
