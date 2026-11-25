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

	// Shared by all providers: holds the OpenFGA client and the discovered store and model
	private OpenFgaClientHandler client;

	@Override
	public EventListenerProvider create(KeycloakSession session) {
		// Keycloak calls create() once per session (request): the provider must use that session only,
		// a cached provider would keep using the first, already closed, session
		return new OpenFgaEventListenerProvider(client, session);
	}

	@Override
	public String getId() {
		return PROVIDER_ID;
	}

	@Override
	public void init(Scope config) {
		try {
			this.client = new OpenFgaClientHandler(config);
		} catch (FgaInvalidParameterException e) {
			throw new RuntimeException(e);
		}
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
