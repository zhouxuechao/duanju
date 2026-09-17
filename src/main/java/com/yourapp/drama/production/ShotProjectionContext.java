package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

import static com.yourapp.drama.production.ProductionJson.text;

/**
 * Binds world position, camera projection, anatomy and prop contact into one
 * camera-space contract. Keeping these facts together prevents a model from
 * drawing a correct prop with a mirrored limb or an impossible off-screen body.
 */
public final class ShotProjectionContext {
    private ShotProjectionContext() {}

    public static ObjectNode build(JsonNode shot) {
        ObjectNode result=JsonNodeFactory.instance.objectNode();
        String locationId=text(shot,"locationId");
        String locationView=shot.path("referenceViews").path(locationId).asText();
        if(LocationViewProjection.isPerspective(locationView))
            result.set("worldToScreenProjection",LocationViewProjection.describe(locationView));
        JsonNode blocking=shot.path("blocking"),camera=shot.path("cameraPlan"),start=shot.path("startState");
        result.put("cameraWorldPosition",first(text(blocking,"cameraWorldPosition"),text(camera,"position")));
        result.put("cameraHorizontalAngle",text(camera,"horizontalAngle"));
        result.put("subjectPlacement",text(camera,"subjectPlacement"));
        ArrayNode contacts=result.putArray("holderContacts");
        for(JsonNode propIdNode:shot.path("propIds")){
            String propId=propIdNode.asText(),holder=text(start.path("props").path(propId),"holder");
            if(holder.isBlank())continue;
            JsonNode character=start.path("characters").path(holder),prop=start.path("props").path(propId),frame=blockingActor(blocking,holder);
            String evidence=String.join("；",text(shot,"subject"),text(shot,"focus"),text(shot,"action"),
                text(shot.path("performancePlan"),"gesture"),prop.path("position").asText());
            ObjectNode contact=contacts.addObject().put("propId",propId).put("holderId",holder)
                .put("anatomicalSide",anatomicalSide(evidence))
                .put("worldPosition",text(character,"position"))
                .put("facing",text(character,"lookDirection"))
                .put("framePosition",text(frame,"framePosition"))
                .put("screenDirection",text(frame,"screenDirection"))
                .put("visibleBodyPart",first(text(frame,"visibleBodyPart"),anatomicalSide(evidence)))
                .put("bodyFrameSide",text(frame,"bodyFrameSide"))
                .put("limbEntrySide",text(frame,"limbEntrySide"))
                .put("contactPoint",text(frame,"contactPoint"))
                .put("propPosition",text(prop,"position"))
                .put("propState",text(prop,"state"))
                .put("gesture",text(shot.path("performancePlan"),"gesture"));
            contact.put("contactRule","道具必须在指定身体部位的实际接触点；可见肢体须自然连接回持物者身体投影位置，不得镜像左右侧、凭空断肢或让身体站位漂移");
        }
        return result;
    }

    private static JsonNode blockingActor(JsonNode blocking,String id){
        for(JsonNode actor:blocking.path("characters"))if(id.equals(text(actor,"characterId")))return actor;
        return JsonNodeFactory.instance.objectNode();
    }

    private static String anatomicalSide(String evidence){
        String value=evidence.toLowerCase(Locale.ROOT);
        if(value.contains("双手")||value.contains("both hands"))return "BOTH_HANDS";
        if(value.contains("右手")||value.contains("右掌")||value.contains("right hand")||value.contains("right palm"))return "RIGHT_HAND";
        if(value.contains("左手")||value.contains("左掌")||value.contains("left hand")||value.contains("left palm"))return "LEFT_HAND";
        if(value.contains("右臂")||value.contains("right arm"))return "RIGHT_ARM";
        if(value.contains("左臂")||value.contains("left arm"))return "LEFT_ARM";
        return "UNSPECIFIED";
    }

    private static String first(String primary,String fallback){return primary.isBlank()?fallback:primary;}
}
