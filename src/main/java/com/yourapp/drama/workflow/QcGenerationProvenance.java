package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Copies the immutable generation settings of the reviewed artifact into every QC record. */
final class QcGenerationProvenance {
    private QcGenerationProvenance(){}
    static ObjectNode attach(ObjectNode qc,JsonNode artifact){
        JsonNode image=artifact.path("generationInputSnapshot"),video=artifact.path("inputSnapshot");
        qc.put("generationProfile",first(artifact.path("generationProfile"),image.path("generationProfile"),video.path("generationProfile")));
        String model=first(artifact.path("sourceModel"),artifact.path("modelId"),artifact.path("model"),image.path("modelId"),video.path("modelId"),video.path("model"));qc.put("model",model).put("generationModel",model);
        String resolution=first(artifact.path("resolution"),image.path("resolution"),video.path("resolution"),video.path("providerOptions").path("resolution"));qc.put("resolution",resolution).put("generationResolution",resolution);
        qc.put("imageSize",first(artifact.path("imageSize"),image.path("imageSize"),video.path("imageSize"),video.path("keyframeSnapshot").path("imageSize")));
        return qc;
    }
    private static String first(JsonNode... values){for(JsonNode value:values)if(value!=null&&!value.isMissingNode()&&!value.isNull()&&!value.asText().isBlank())return value.asText();return "UNKNOWN";}
}
