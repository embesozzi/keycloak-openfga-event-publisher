package com.twogenidentity.keycloak;

import dev.openfga.sdk.errors.FgaInvalidParameterException;
import com.twogenidentity.keycloak.event.EventParser;
import com.twogenidentity.keycloak.service.OpenFgaClientHandler;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

import java.util.concurrent.ExecutionException;

public class OpenFgaEventListenerProvider implements EventListenerProvider {
	private static final Logger LOG = Logger.getLogger(OpenFgaEventListenerProvider.class);
	private OpenFgaClientHandler client;
	private KeycloakSession session;

	public OpenFgaEventListenerProvider(OpenFgaClientHandler client, KeycloakSession session) {
		this.client = client;
		this.session = session;
	}

	@Override
	public void onEvent(Event event) {
		LOG.debug("Discarding onEvent() type: " + event.getType().toString());
	}

	@Override
	public void onEvent(AdminEvent adminEvent, boolean includeRepresentation) {
		LOG.debugf("Admin event received onEvent(): %s ", adminEvent.toString());

		try {
			EventParser event = new EventParser(adminEvent, session);
			LOG.debugf("Event parsed: %s ", event.toString());
			client.publish(adminEvent.getId(), event);
		} catch (IllegalArgumentException e) {
			LOG.warn(e.getMessage());
		}
		catch (ExecutionException | InterruptedException e) {
			throw new RuntimeException(e);
		} catch (FgaInvalidParameterException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void close() {
		// ignore
	}
}
