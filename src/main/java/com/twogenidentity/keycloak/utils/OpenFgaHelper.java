package com.twogenidentity.keycloak.utils;

import com.twogenidentity.keycloak.event.EventParser;
import dev.openfga.sdk.api.client.ClientTupleKey;
import dev.openfga.sdk.api.client.ClientWriteRequest;
import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.RelationReference;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.Userset;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenFgaHelper {

    private static final Logger LOG = Logger.getLogger(OpenFgaHelper.class);
    private AuthorizationModel model;
    private Map<String, String> modelTypeObjectAndRelation;

    public void loadModel(AuthorizationModel model) {
        this.model = model;
        loadModelAsTypeObjectRelationshipMap();
    }

    private Boolean isTypeDefinitionHandled(String eventObjectType) {
        return this.model.getTypeDefinitions().stream()
                .filter(r -> r.getType().equalsIgnoreCase(eventObjectType))
                .findFirst().isEmpty();
    }

    private String getRelationFromModel(String typeDefinition, String objectType){
        return modelTypeObjectAndRelation.get(typeDefinition + objectType); // Easy way to return the relation
    }

    public ClientWriteRequest toClientWriteRequest(EventParser event) {
        ClientWriteRequest client = new ClientWriteRequest();

        String eventObjectType  = event.getEventObjectType();
        String eventObjectId = event.getEventObjectName();
        String eventUserType = event.getEventUserType();
        String eventUserId   = event.getTranslateUserId();

        // Check if the authorization model is prepared to handle
        // this object type a.k.a TypeDefinition
        if(isTypeDefinitionHandled(eventObjectType)) {
            // Obtain the relation based the event objectType and eventUserType
            // For now, this combination is UNIQUE in the authorization model
            String relation = getRelationFromModel(eventObjectType, eventUserType);

            ClientTupleKey tuple = new ClientTupleKey()
                    .user(eventUserType + ":" +  eventUserId)
                    .relation(relation)
                    ._object(eventObjectType +":"+ eventObjectId);

            if(event.isWriteOperation()) {
                client.writes(List.of(tuple));
            }
            else if(event.isDeleteOperation()) {
                client.deletes(List.of(tuple));
            }
        }
        else {
            LOG.warn("[OpenFgaEventPublisher] Event not handled in OpenFGA, event: " + eventObjectType + " is not present in model.");
        }
        return client;
    }

    private void loadModelAsTypeObjectRelationshipMap(){
        this.modelTypeObjectAndRelation = new HashMap<>();
        for (TypeDefinition typeDef : this.model.getTypeDefinitions()) {
            for (Map.Entry<String, Userset> us : typeDef.getRelations().entrySet()) {
                if (typeDef.getMetadata() != null
                        && !typeDef.getMetadata().getRelations().isEmpty()
                        && typeDef.getMetadata().getRelations().containsKey(us.getKey())) {
                    for(RelationReference metadata: typeDef.getMetadata().getRelations().get(us.getKey()).getDirectlyRelatedUserTypes()) {
                        this.modelTypeObjectAndRelation.put(typeDef.getType() + metadata.getType(), us.getKey());
                    }
                }
            }
        }
        LOG.debug("[OpenFgaEventPublisher] Internal model as Map(TypeObject:Relation): " + modelTypeObjectAndRelation);
    }
}
