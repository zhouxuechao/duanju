package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class QcGenerationProfileTest {
    @Test void keyframeQcKeepsTestSeedreamAndImageSize(){
        ObjectNode qc=QcGenerationProvenance.attach(obj(),obj().put("generationProfile","TEST").put("sourceModel","doubao-seedream-5-0-260128").put("imageSize","2K"));
        assertThat(qc.path("generationProfile").asText()).isEqualTo("TEST");assertThat(qc.path("generationModel").asText()).isEqualTo("doubao-seedream-5-0-260128");assertThat(qc.path("imageSize").asText()).isEqualTo("2K");assertThat(qc.path("generationResolution").asText()).isEqualTo("UNKNOWN");
    }
    @Test void videoQcKeepsStandardFastModelResolutionAndSourceImageSize(){
        ObjectNode take=obj().put("generationProfile","STANDARD").put("modelId","doubao-seedance-2-0-fast-260128").put("resolution","720p");take.putObject("inputSnapshot").putObject("keyframeSnapshot").put("imageSize","2K");
        ObjectNode qc=QcGenerationProvenance.attach(obj(),take);
        assertThat(qc.path("generationProfile").asText()).isEqualTo("STANDARD");assertThat(qc.path("generationModel").asText()).isEqualTo("doubao-seedance-2-0-fast-260128");assertThat(qc.path("generationResolution").asText()).isEqualTo("720p");assertThat(qc.path("imageSize").asText()).isEqualTo("2K");
    }
    @Test void legacyMissingProvenanceIsExplicitlyUnknown(){
        ObjectNode qc=QcGenerationProvenance.attach(obj(),obj());for(String field:new String[]{"generationProfile","generationModel","generationResolution","imageSize"})assertThat(qc.path(field).asText()).isEqualTo("UNKNOWN");
    }
}
