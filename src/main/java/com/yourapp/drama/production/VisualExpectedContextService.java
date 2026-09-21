package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Produces the bounded contract a visual reviewer must verify for one shot. */
@Service
public class VisualExpectedContextService {
    private static final Set<String> INFRASTRUCTURE_FIELDS=Set.of(
        "projectId","parentId","revision","createdAt","updatedAt","generationJobId","providerRequestId","providerTaskId",
        "providerUrl","providerUrlExpiresAt","archiveUrl","archiveKey","archiveContentType","archiveStatus","referenceImageUrl",
        "approvedViews","referenceViewIds","sourceSnapshot","model","simulated","stale","providerStatus");
    private final ObjectMapper mapper;
    public VisualExpectedContextService(ObjectMapper mapper){this.mapper=mapper;}

    public ObjectNode build(JsonNode generationContext){
        JsonNode shot=generationContext.path("shot"),assets=generationContext.path("assets");
        ObjectNode result=mapper.createObjectNode(),required=result.putObject("requiredConstraints");
        required.set("characterIdentity",visualOnly(assets.path("characters")));
        ObjectNode clothing=mapper.createObjectNode();clothing.set("looks",visualOnly(assets.path("looks")));clothing.set("states",visualOnly(generationContext.path("characterStates")));
        clothing.set("shotStart",visualOnly(shot.path("startState").path("characters")));required.set("clothing",clothing);
        ObjectNode location=mapper.createObjectNode().put("locationId",shot.path("locationId").asText());location.set("assets",visualOnly(assets.path("locations")));
        location.set("state",visualOnly(generationContext.path("locationState")));required.set("location",location);
        ObjectNode props=mapper.createObjectNode();props.set("assets",visualOnly(assets.path("props")));props.set("propIds",shot.path("propIds").deepCopy());
        props.set("shotStart",visualOnly(shot.path("startState").path("props")));props.set("previousState",visualOnly(generationContext.path("previousState").path("props")));required.set("props",props);
        ObjectNode composition=mapper.createObjectNode().put("shotSize",shot.path("shotSize").asText()).put("cameraAngle",shot.path("cameraAngle").asText());
        composition.set("cameraPlan",shot.path("cameraPlan").deepCopy());
        String locationView=shot.path("referenceViews").path(shot.path("locationId").asText()).asText();
        if(LocationViewProjection.isPerspective(locationView))composition.set("worldToScreenProjection",LocationViewProjection.describe(locationView));
        composition.set("interactionGeometry",ShotProjectionContext.build(shot));
        composition.set("surfaceTopology",SpatialTopologyContext.build(shot,assets));
        required.set("composition",composition);
        ObjectNode compliance=mapper.createObjectNode();for(String field:List.of("directorIntent","shotPurpose","subject","secondarySubjects","cameraMovement","eyeLine","focus","blocking","performancePlan","visibilityPlan","transition","directorPlanVersion","dramaticBeatVersion","shotPlanVersion"))if(shot.has(field))compliance.set(field,visualOnly(shot.path(field)));required.set("directorCompliance",compliance);
        required.set("action",mapper.createObjectNode().put("action",shot.path("action").asText()).put("emotion",shot.path("emotion").asText()));
        required.set("shotStart",visualOnly(shot.path("startState")));
        required.set("shotEnd",visualOnly(shot.path("endState")));
        if(generationContext.path("previousTake").path("observedState").isObject())required.set("previousAcceptedEnd",visualOnly(generationContext.path("previousTake").path("observedState")));
        required.put("style",generationContext.path("style").asText(assets.path("style").asText()));
        String revisionFeedback=generationContext.path("revisionFeedback").asText();
        if(!revisionFeedback.isBlank())required.put("priorFailureToRecheck",revisionFeedback);
        result.put("shotId",shot.path("shotId").asText(shot.path("id").asText()));return result;
    }
    private JsonNode visualOnly(JsonNode value){
        if(value.isArray()){ArrayNode result=mapper.createArrayNode();value.forEach(item->result.add(visualOnly(item)));return result;}
        if(value.isObject()){ObjectNode result=mapper.createObjectNode();value.fields().forEachRemaining(field->{String name=field.getKey();if(!INFRASTRUCTURE_FIELDS.contains(name)&&!name.endsWith("Url")&&!name.endsWith("URL"))result.set(name,visualOnly(field.getValue()));});return result;}
        return value.deepCopy();
    }
}
