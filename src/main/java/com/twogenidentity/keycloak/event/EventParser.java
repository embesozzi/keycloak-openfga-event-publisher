package com.twogenidentity.keycloak.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import com.twogenidentity.keycloak.utils.OpenFgaIdentifier;
import com.twogenidentity.keycloak.utils.OpenFgaIds;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import java.util.ArrayList;
import java.util.List;

import static org.keycloak.events.admin.OperationType.CREATE;
import static org.keycloak.events.admin.OperationType.DELETE;

public class EventParser {

    private AdminEvent event;
    private KeycloakSession session;
    private OpenFgaIdentifier identifier = OpenFgaIdentifier.NAME;
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

    public EventParser(AdminEvent event, KeycloakSession session, OpenFgaIdentifier identifier){
        this(event, session);
        this.identifier = identifier;
    }

    /**
     * The OpenFGA id of the event's user side: the user id, or the encoded name of the role, group or client.
     * Groups use their name, as group memberships do (issue #4).
     */
    public String getTranslateUserId() {
        String userType = getEventUserType();
        if (identifier == OpenFgaIdentifier.ID) {
            // the role or group id from the resource path, or the client's internal id; nothing to encode in UUIDs
            if (userType.equals(OBJECT_TYPE_CLIENT)) return OpenFgaIds.encode(getServiceAccountClientLink());
            return OpenFgaIds.encode(getEventUserId());
        }
        if (userType.equals(OBJECT_TYPE_ROLE))   return findRoleNameInRealm(getEventUserId());
        if (userType.equals(OBJECT_TYPE_CLIENT)) return OpenFgaIds.encode(findClientIdFromServiceAccount(getEventUserId()));
        if (userType.equals(OBJECT_TYPE_GROUP))  return OpenFgaIds.encode(findGroupNameInRealm(getEventUserId()));
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
        return getAttribute(getRepresentationElement(), "id");
    }

    /** The OpenFGA id of the event's first object; see {@link #getEventObjectNames()}. */
    public String getEventObjectName() {
        return getEventObjectNames().get(0);
    }

    /**
     * The OpenFGA ids of the event's objects, one per element of the representation (several roles can be
     * assigned in one call): the encoded group name, or the encoded role name. Client roles are prefixed with
     * their encoded clientId, role:<clientId>/<roleName>, so they do not collide with realm roles of the same name.
     */
    public List<String> getEventObjectNames() {
        List<String> names = new ArrayList<>();
        for (JsonNode element : getRepresentationElements()) {
            names.add(toObjectName(element));
        }
        return names;
    }

    private String toObjectName(JsonNode element) {
        if (identifier == OpenFgaIdentifier.ID) {
            return OpenFgaIds.encode(getAttribute(element, "id")); // role and group ids are unique: no clientId prefix
        }
        String name = OpenFgaIds.encode(getAttribute(element, "name"));
        if (!OBJECT_TYPE_ROLE.equals(getEventObjectType())) {
            return name;
        }
        String clientUuid = getEventClientUuid(element);
        return clientUuid == null ? name : OpenFgaIds.encode(findClientId(clientUuid)) + "/" + name;
    }

    // The internal id of the client owning the event's role, or null for a realm role
    private String getEventClientUuid(JsonNode element) {
        String[] path = event.getResourcePath().split("/");
        // users/{id}/role-mappings/clients/{clientUuid} or groups/{id}/role-mappings/clients/{clientUuid}
        if (path.length >= 5 && "role-mappings".equals(path[2]) && "clients".equals(path[3])) {
            return path[4];
        }
        // composites: the representation says whether the role belongs to a client
        JsonNode clientRole = element.get("clientRole");
        JsonNode containerId = element.get("containerId");
        return clientRole != null && clientRole.asBoolean() && containerId != null ? containerId.asText() : null;
    }

    private JsonNode getRepresentationElement() {
        return getRepresentationElements().get(0);
    }

    // The representation's elements: the array's elements, or the representation itself when it is one object
    private List<JsonNode> getRepresentationElements() {
        String representation = event.getRepresentation().replaceAll("\\\\", ""); // Fixme: I should try to avoid the replace
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(representation);
            if (!jsonNode.isArray()) {
                return List.of(jsonNode);
            }
            if (jsonNode.isEmpty()) {
                throw new IllegalArgumentException("Cannot parse the event: empty representation array for event id:" + event.getId());
            }
            List<JsonNode> elements = new ArrayList<>();
            jsonNode.forEach(elements::add);
            return elements;
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e); // Fixme: Improve exception handling
        }
    }

    private static String getAttribute(JsonNode element, String attributeName) {
        JsonNode attr = element.get(attributeName);
        if (attr == null) {
            throw new IllegalArgumentException("Attribute '" + attributeName + "' missing in: " + element);
        }
        return attr.asText();
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

    /** The OpenFGA id of a role: its encoded name, prefixed with the encoded clientId for a client role. */
    public String findRoleNameInRealm(String roleId)  {
        RoleModel role = getEventRealm().getRoleById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("No role found with id:" + roleId + " event id:" + event.getId());
        }
        String name = OpenFgaIds.encode(role.getName());
        if (role.isClientRole() && role.getContainer() instanceof ClientModel client) {
            return OpenFgaIds.encode(client.getClientId()) + "/" + name;
        }
        return name;
    }

    private String findGroupNameInRealm(String groupId) {
        GroupModel group = getEventRealm().getGroupById(groupId);
        if (group == null) {
            throw new IllegalArgumentException("No group found with id:" + groupId + " event id:" + event.getId());
        }
        return group.getName();
    }

    private String findClientId(String clientUuid) {
        ClientModel client = getEventRealm().getClientById(clientUuid);
        if (client == null) {
            throw new IllegalArgumentException("No client found with id:" + clientUuid + " event id:" + event.getId());
        }
        return client.getClientId();
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
