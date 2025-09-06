package com.twogenidentity.keycloak.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

import static org.keycloak.events.admin.OperationType.CREATE;
import static org.keycloak.events.admin.OperationType.DELETE;

public class EventParser {

    private final AdminEvent event;
    private final KeycloakSession session;

    public static final String EVT_RESOURCE_USERS = "users";
    public static final String EVT_RESOURCE_GROUPS = "groups";
    public static final String EVT_RESOURCE_ROLES_BY_ID = "roles-by-id";
    public static final String OBJECT_TYPE_USER = "user";
    public static final String OBJECT_TYPE_ROLE = "role";
    public static final String OBJECT_TYPE_GROUP = "group";

    public EventParser(AdminEvent event, KeycloakSession session){
        this.event = event;
        this.session = session;
    }

    public String getTranslateUserId(){
        return getEventUserType().equals(OBJECT_TYPE_ROLE) ? findRoleNameInRealm(getEventUserId()) : getEventUserId();
    }

    public String getEventObjectType() {
        switch (event.getResourceType()) {
            case REALM_ROLE_MAPPING:
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
                return OBJECT_TYPE_USER;
            case EVT_RESOURCE_GROUPS:
                return OBJECT_TYPE_GROUP;
            case EVT_RESOURCE_ROLES_BY_ID:
                return OBJECT_TYPE_ROLE;
            default:
                throw new IllegalArgumentException("Resource type is not handled: " + event.getOperationType());
        }
    }

    public boolean isWriteOperation() {
        return this.event.getOperationType().equals(CREATE);
    }

    public boolean isDeleteOperation() {
        return this.event.getOperationType().equals(DELETE);
    }



    public String getEventUserId() {
        return this.event.getResourcePath().split("/")[1];
    }

    public String getEventResourceName() {
        return this.event.getResourcePath().split("/")[0];
    }

    public String getEventObjectName() {
        return getObjectByAttributeName("name");
    }

    private String getObjectByAttributeName(String attributeName) {
        ObjectMapper mapper = new ObjectMapper();
        String representation = event.getRepresentation().replaceAll("\\\\", ""); // Fixme: I should try to avoid the replace
        try {
            JsonNode jsonNode = mapper.readTree(representation);
            if(jsonNode.isArray()){
                return jsonNode.get(0).get(attributeName).asText();
            }
            return jsonNode.get(attributeName).asText();
        }
        catch (JsonMappingException e) {
            throw new RuntimeException(e); // Fixme: Improve exception handling
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public String findRoleNameInRealm(String roleId)  {
        return session.getContext().getRealm().getRoleById(roleId).getName();
    }

    public String getRealmId() {
        return event.getAuthDetails().getRealmId();
    }

    public String getSelectedRealmId() {
        return this.event.getRealmId();
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
