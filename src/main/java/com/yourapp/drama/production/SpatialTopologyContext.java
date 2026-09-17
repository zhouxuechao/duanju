package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.yourapp.drama.production.ProductionJson.text;

/** Provider-neutral topology contract for every kind of interior or exterior set. */
public final class SpatialTopologyContext {
    private SpatialTopologyContext() {}

    public static ObjectNode build(JsonNode shot,JsonNode assets){
        String locationId=text(shot,"locationId");
        JsonNode location=JsonNodeFactory.instance.objectNode();
        for(JsonNode candidate:assets.path("locations"))if(locationId.equals(text(candidate,"id"))){location=candidate;break;}
        ObjectNode result=JsonNodeFactory.instance.objectNode().put("locationId",locationId);
        result.set("authoritativeLayout",location.path("locationBible").deepCopy());
        ArrayNode relations=result.putArray("relationsToPreserve");
        relations.add("SURFACE_ASSIGNMENT").add("SAME_SURFACE").add("OPPOSITE_SURFACES")
            .add("ADJACENT_SURFACES").add("DEPTH_ORDER").add("CONTAINMENT");
        boolean establishesSpace="ESTABLISH_SPACE".equals(text(shot,"directorIntent"))
            || "ESTABLISHING".equals(text(shot,"relationToPrevious"))
            || "WIDE".equals(text(shot,"shotSize"));
        result.put("visibilityMode",establishesSpace?"REQUIRE_FRAMED_LANDMARKS":"PRESERVE_IF_VISIBLE");
        result.put("visibilityRule",establishesSpace
            ?"按导演方案与可见特征展示空间地标；每个入画设施保持原承载面和关系"
            :"不要求视锥外或被特写裁掉的非主体设施强行入画；只要设施进入画面，就必须保持承载面和拓扑关系");
        result.put("invariant","每个固定设施保留其所属承载面及与其他固定设施的同面、对立、相邻、前后和内外关系；景深只改变清晰度，不改变拓扑");
        return result;
    }
}
