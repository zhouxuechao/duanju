package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoRequestSnapshotTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void snapshotPersistsModelRouteMaterialsMappingParametersAndRuleFingerprint() {
        ProviderCapabilityRegistry registry=new ProviderCapabilityRegistry();
        VideoRequestPlanner planner=new VideoRequestPlanner(mapper,registry,new MaterialActivationPlan(mapper),new ReferenceBudgeter(),
                new MaterialPreflight(),new ReferenceConflictValidator(),new ReferenceIndexValidator(mapper),new VideoRequestContractValidator(),
                new VideoRequestRouteResolver(),new KeyframeTimelineValidator(),new ProviderRulePackResolver(new RuntimeRulePackLoader()));
        ObjectNode context=mapper.createObjectNode();
        context.set("providerCapabilities",registry.profile("doubao-seedance-2-5-test").toJson());
        ObjectNode shot=context.putObject("shot").put("shotId","SHOT_1").put("duration",5).put("locationId","LOC_1").put("sequenceRelation","CUT");
        shot.putArray("characterIds").add("CHAR_1");shot.putArray("propIds");
        ObjectNode keyframe=mapper.createObjectNode().put("id","KF_1").put("providerUrl","https://media.example.com/start.png").put("semanticRole","START_FRAME").put("stateVersion",1);
        ObjectNode prepared=mapper.createObjectNode().put("strategy","INDEPENDENT_CUT");
        prepared.putArray("references").add(mapper.createObjectNode().put("referenceId","KF_1").put("assetId","ASSET_1").put("entityId","CHAR_1").put("role","KEYFRAME_PROVIDER").put("mediaType","IMAGE").put("url","https://media.example.com/start.png").put("authorityPriority",95));
        ObjectNode body=mapper.createObjectNode().put("videoTaskType","FIRST_FRAME_GENERATE");
        body.set("providerOptions",mapper.createObjectNode().put("duration",5).put("ratio","9:16"));

        ObjectNode snapshot=planner.plan(prepared,context,keyframe,mapper.createObjectNode(),body);

        assertThat(snapshot.path("modelId").asText()).contains("seedance-2-5");
        assertThat(snapshot.path("modelProfileVersion").asText()).isNotBlank();
        assertThat(snapshot.path("taskType").asText()).isEqualTo("FIRST_FRAME_GENERATE");
        assertThat(snapshot.path("lockMode").asText()).isEqualTo("LOCKED");
        assertThat(snapshot.path("route").asText()).isEqualTo("NATIVE_FIRST_FRAME");
        assertThat(snapshot.path("videoRequestRoute").asText()).isEqualTo("NATIVE_FIRST_FRAME");
        assertThat(snapshot.path("activatedMaterials")).hasSize(1);
        assertThat(snapshot.path("referenceMapping").get(0).path("providerRef").asText()).isEqualTo("@image1");
        assertThat(snapshot.path("referenceMapping").get(0).path("assetId").asText()).isEqualTo("ASSET_1");
        assertThat(snapshot.path("referenceMapping").get(0).path("entityId").asText()).isEqualTo("CHAR_1");
        assertThat(snapshot.path("providerParameters").path("ratio").asText()).isEqualTo("9:16");
        assertThat(snapshot.path("rulePackFingerprint").asText()).hasSize(64);
        assertThat(snapshot.path("audioGenerationPolicy").asText()).isEqualTo("POST_ONLY");
    }
}
