package com.twogenidentity.keycloak.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import static org.keycloak.events.admin.OperationType.CREATE;
import static org.keycloak.events.admin.OperationType.DELETE;

public class EventParser {

    private AdminEvent event;
    private KeycloakSession session;
    private boolean serviceAccountResolved = false;
    private String serviceAccountClientLink;

    public static final String EVT_RESOURCE_USERS = "users";
    public static final String EVT_RESOURCE_GROUPS = "groups";
    public static final String EVT_RESOURCE_ROLES_BY_ID = "roles-by-id";
    public static final String OBJECT_TYPE_USER = "user";
    public static final String OBJECT_TYPE_ROLE = "role";
    public static final String OBJECT_TYPE_GROUP = "group";
    public static final String OBJECT_TYPE_CLIENT = "client";

    private static final Logger LOG = Logger.getLogger(EventParser.class);

    public EventParser(AdminEvent event){
        this.event = event;
    }

    public EventParser(AdminEvent event, KeycloakSession session){
        this.event = event;
        this.session = session;
    }

    public String getTranslateUserId() {
        String userType = getEventUserType();
        if (userType.equals(OBJECT_TYPE_ROLE))   return findRoleNameInRealm(getEventUserId());
        if (userType.equals(OBJECT_TYPE_CLIENT)) return findClientIdFromServiceAccount(getEventUserId());
        return getEventUserId();
    }

    public String getEventObjectType() {
        switch (event.getResourceType()) {
            case REALM_ROLE_MAPPING:
            case CLIENT_ROLE_MAPPING:
            case REALM_ROLE:
                return OBJECT_TYPE_ROLE;
            case GROUP_MEMBERSHIP:
                return OBJECT_TYPE_GROUP;
            default:
                throw new IllegalArgumentException("Event is not handled, id:" + event.getId() + " resource: " + event.getResourceType().name());
        }
    }

    public String getEventUserType() {
        switch (getEventResourceName()) {
            case EVT_RESOURCE_USERS:
                return isServiceAccountUser() ? OBJECT_TYPE_CLIENT : OBJECT_TYPE_USER;
            case EVT_RESOURCE_GROUPS:
                return OBJECT_TYPE_GROUP;
            case EVT_RESOURCE_ROLES_BY_ID:
                return OBJECT_TYPE_ROLE;
            default:
                throw new IllegalArgumentException("Resource type is not handled: " + event.getOperationType());
        }
    }

    public boolean isWriteOperation() {
        return this.event.getOperationType().equals(CREATE) ? true : false;
    }

    public boolean isDeleteOperation() {
        return this.event.getOperationType().equals(DELETE) ? true : false;
    }


    public String getEventAuthenticatedUserId() {
        return this.event.getAuthDetails().getUserId();
    }

    public String getEventUserId() {
        return this.event.getResourcePath().split("/")[1];
    }

    public String getEventResourceName() {
        return this.event.getResourcePath().split("/")[0];
    }

    public Boolean isUserEvent() {
        return getEventResourceName().equalsIgnoreCase(EVT_RESOURCE_USERS);
    }

    public Boolean isRoleEvent() {
        return getEventResourceName().equalsIgnoreCase(EVT_RESOURCE_ROLES_BY_ID);
    }

    public Boolean isGroupEvent() {
        return getEventResourceName().equalsIgnoreCase(EVT_RESOURCE_GROUPS);
    }

    public String getEventObjectId() {
        return getObjectByAttributeName("id");
    }

    public String getEventObjectName() {
        return getObjectByAttributeName("name");
    }

    private String getObjectByAttributeName(String attributeName) {
        ObjectMapper mapper = new ObjectMapper();
        String representation = event.getRepresentation().replaceAll("\\\\", ""); // Fixme: I should try to avoid the replace
        try {
            JsonNode jsonNode = mapper.readTree(representation);
            if (jsonNode.isArray()) {
                JsonNode element = jsonNode.get(0);
                if (element == null) {
                    throw new IllegalArgumentException("Cannot parse '" + attributeName + "': empty representation array for event id:" + event.getId());
                }
                JsonNode attr = element.get(attributeName);
                if (attr == null) {
                    throw new IllegalArgumentException("Attribute '" + attributeName + "' missing in: " + element);
                }
                return attr.asText();
            }
            JsonNode attr = jsonNode.get(attributeName);
            if (attr == null) {
                throw new IllegalArgumentException("Attribute '" + attributeName + "' missing in: " + jsonNode);
            }
            return attr.asText();
        }
        catch (JsonMappingException e) {
            throw new RuntimeException(e); // Fixme: Improve exception handling
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isServiceAccountUser() {
        return getServiceAccountClientLink() != null;
    }

    public String findClientIdFromServiceAccount(String userId) {
        String internalClientId = getServiceAccountClientLink();
        ClientModel client = internalClientId != null ? getEventRealm().getClientById(internalClientId) : null;
        if (client == null) {
            throw new IllegalArgumentException("No client found for service account user id:" + userId + " event id:" + event.getId());
        }
        return client.getClientId();
    }

    // Lazily resolves (once per event) the internal client id linked to the event's user, or null if not a service account
    private String getServiceAccountClientLink() {
        if (!serviceAccountResolved) {
            serviceAccountResolved = true;
            RealmModel realm = getEventRealm();
            if (realm != null) {
                UserModel user = session.users().getUserById(realm, getEventUserId());
                serviceAccountClientLink = user != null ? user.getServiceAccountClientLink() : null;
            }
        }
        return serviceAccountClientLink;
    }

    // The realm the admin event targets (not necessarily the admin's realm, e.g. master)
    private RealmModel getEventRealm() {
        if (session == null) return null;
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        return realm != null ? realm : session.getContext().getRealm();
    }

    public String findRoleNameInRealm(String roleId)  {
        return session.getContext().getRealm().getRoleById(roleId).getName();
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("AdminEvent resourceType=");
        sb.append(event.getResourceType());
        sb.append(", operationType=");
        sb.append(event.getOperationType());
        sb.append(", realmId=");
        sb.append(event.getAuthDetails().getRealmId());
        sb.append(", clientId=");
        sb.append(event.getAuthDetails().getClientId());
        sb.append(", userId=");
        sb.append(event.getAuthDetails().getUserId());
        sb.append(", ipAddress=");
        sb.append(event.getAuthDetails().getIpAddress());
        sb.append(", resourcePath=");
        sb.append(event.getResourcePath());
        if (event.getError() != null) {
            sb.append(", error=");
            sb.append(event.getError());
        }
        return sb.toString();
    }
}
