package com.twogenidentity.keycloak.utils;

import com.twogenidentity.keycloak.event.EventParser;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.RelationReference;
import dev.openfga.sdk.api.model.TypeDefinition;
import dev.openfga.sdk.api.model.Userset;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenFgaHelper {

    private static final Logger LOG = Logger.getLogger(OpenFgaHelper.class);
    private AuthorizationModel model;
    private Map<String, String> modelTypeObjectAndRelation;

    public OpenFgaHelper() {
        this.modelTypeObjectAndRelation = new HashMap<>();
    }

    public void loadModel(AuthorizationModel model) {
        this.model = model;
        this.loadModelAsTypeObjectRelationshipMap();
    }

    private Boolean isTypeDefinitionInModel(String eventObjectType) {
        return !this.model.getTypeDefinitions().stream()
                .filter(r -> r.getType().equalsIgnoreCase(eventObjectType))
                .findFirst().isEmpty();
    }

    private Boolean isRelationAvailableInModel(String typeDefinition, String objectType) {
        return this.modelTypeObjectAndRelation.containsKey(typeDefinition + objectType);
    }

    private String getRelationFromModel(String typeDefinition, String objectType){
        return this.modelTypeObjectAndRelation.get(typeDefinition + objectType); // Easy way to return the relation
    }

    public ClientWriteRequest toClientWriteRequest(EventParser event) {
        ClientWriteRequest client = new ClientWriteRequest();

        String eventObjectType  = event.getEventObjectType();
        String eventUserType = event.getEventUserType();

        // Check if the authorization model handles this object type a.k.a TypeDefinition
        // Check if we have the relation for this object type and user type
        if(isTypeDefinitionInModel(eventObjectType)
            && isRelationAvailableInModel(eventObjectType, eventUserType)) {

            String relation = getRelationFromModel(eventObjectType, eventUserType);
            String user = eventUserType + ":" + event.getTranslateUserId();

            // One tuple per object: several roles can be assigned or removed in one call
            List<ClientTupleKey> tuples = new ArrayList<>();
            for (String eventObjectId : event.getEventObjectNames()) {
                ClientTupleKey tuple = new ClientTupleKey()
                        .user(user)
                        .relation(relation)
                        ._object(eventObjectType + ":" + eventObjectId);
                LOG.debugf("Tuple %s %s %s",  tuple.getUser(), tuple.getRelation(), tuple.getObject());
                tuples.add(tuple);
            }

            if(event.isWriteOperation()) {
                client.writes(tuples);
            }
            else if(event.isDeleteOperation()) {
                client.deletes(new ArrayList<ClientTupleKeyWithoutCondition>(tuples));
            }
        }
        else {
            LOG.warnf("Event not handled in OpenFGA. Event: %s %s is not present in model %s.", eventObjectType, eventUserType, this.modelTypeObjectAndRelation);
        }
        return client;
    }

    private void loadModelAsTypeObjectRelationshipMap(){
        LOG.debugf("Loading internal model");
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
        LOG.debugf("Internal model as Map(TypeObject:Relation): %s",  this.modelTypeObjectAndRelation);
    }

    public Boolean isAvailableClientRequest(ClientWriteRequest request) {
        return ((request.getWrites() != null && !request.getWrites().isEmpty())
                || (request.getDeletes() !=null && !request.getDeletes().isEmpty()));
    }
}
