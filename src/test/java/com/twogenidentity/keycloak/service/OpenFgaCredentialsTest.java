package com.twogenidentity.keycloak.service;

import dev.openfga.sdk.api.configuration.CredentialsMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How the extension authenticates to OpenFGA, from its configuration (openfgaCredentialsMethod and friends). */
class OpenFgaCredentialsTest {

    private static Config.Scope config(String... keyValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        Config.Scope scope = mock(Config.Scope.class);
        when(scope.get(anyString())).thenAnswer(call -> values.get(call.<String>getArgument(0)));
        return scope;
    }

    @Test
    @DisplayName("Without configuration, no credentials are sent (method none)")
    void noneByDefault() {
        var credentials = OpenFgaClientHandler.credentials(config());

        assertEquals(CredentialsMethod.NONE, credentials.getCredentialsMethod());
    }

    @Test
    @DisplayName("api_token: sends the pre-shared key as a bearer token")
    void apiToken() {
        var credentials = OpenFgaClientHandler.credentials(config(
                "openfgaCredentialsMethod", "api_token",
                "openfgaApiToken", "my-preshared-key"));

        assertEquals(CredentialsMethod.API_TOKEN, credentials.getCredentialsMethod());
        assertEquals("my-preshared-key", credentials.getApiToken().getToken());
    }

    @Test
    @DisplayName("client_credentials: gets a token from the issuer with the client id and secret")
    void clientCredentials() {
        var credentials = OpenFgaClientHandler.credentials(config(
                "openfgaCredentialsMethod", "client_credentials",
                "openfgaClientId", "openfga-publisher",
                "openfgaClientSecret", "s3cret",
                "openfgaApiTokenIssuer", "http://keycloak:8080/realms/it/protocol/openid-connect/token",
                "openfgaApiAudience", "openfga",
                "openfgaScopes", "openfga:write"));

        assertEquals(CredentialsMethod.CLIENT_CREDENTIALS, credentials.getCredentialsMethod());
        var client = credentials.getClientCredentials();
        assertEquals("openfga-publisher", client.getClientId());
        assertEquals("s3cret", client.getClientSecret());
        assertEquals("http://keycloak:8080/realms/it/protocol/openid-connect/token", client.getApiTokenIssuer());
        assertEquals("openfga", client.getApiAudience());
        assertEquals("openfga:write", client.getScopes());
    }

    @Test
    @DisplayName("The method is case-insensitive")
    void methodCaseInsensitive() {
        var credentials = OpenFgaClientHandler.credentials(config(
                "openfgaCredentialsMethod", "API_TOKEN",
                "openfgaApiToken", "key"));

        assertEquals(CredentialsMethod.API_TOKEN, credentials.getCredentialsMethod());
    }

    @Test
    @DisplayName("An unknown method fails at startup and names the valid ones")
    void unknownMethod() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OpenFgaClientHandler.credentials(config("openfgaCredentialsMethod", "basic")));

        assertTrue(error.getMessage().contains("none, api_token or client_credentials"), error.getMessage());
    }

    @Test
    @DisplayName("api_token without a token fails at startup and names the missing setting")
    void apiTokenMissing() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OpenFgaClientHandler.credentials(config("openfgaCredentialsMethod", "api_token")));

        assertTrue(error.getMessage().contains("openfgaApiToken"), error.getMessage());
    }

    @Test
    @DisplayName("client_credentials without an issuer fails at startup and names the missing setting")
    void clientCredentialsMissingIssuer() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OpenFgaClientHandler.credentials(config(
                        "openfgaCredentialsMethod", "client_credentials",
                        "openfgaClientId", "openfga-publisher",
                        "openfgaClientSecret", "s3cret")));

        assertTrue(error.getMessage().contains("openfgaApiTokenIssuer"), error.getMessage());
    }

    @Test
    @DisplayName("Error messages never contain the secret or the token")
    void secretsNotInErrors() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OpenFgaClientHandler.credentials(config(
                        "openfgaCredentialsMethod", "client_credentials",
                        "openfgaClientSecret", "s3cret-value")));

        assertFalse(error.getMessage().contains("s3cret-value"), error.getMessage());
    }
}
