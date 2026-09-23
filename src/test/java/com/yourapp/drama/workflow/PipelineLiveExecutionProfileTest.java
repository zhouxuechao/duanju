package com.yourapp.drama.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineLiveExecutionProfileTest {
    @Test void fixedProfileMatchesTheFirstPhaseBProductionContract(){
        var profile=PipelineLiveExecutionProfile.phaseB();
        assertThat(profile.assetDependency()).isEqualTo("A1");
        assertThat(profile.assetImageRequests()).isEqualTo(4);assertThat(profile.keyframeRequests()).isEqualTo(4);assertThat(profile.imageMax()).isEqualTo(8);
        assertThat(profile.storyLlmRequests()).isEqualTo(6);assertThat(profile.directorLlmRequests()).isEqualTo(5);assertThat(profile.llmMax()).isEqualTo(11);
        assertThat(profile.videoMax()).isEqualTo(4);assertThat(profile.ttsMax()).isEqualTo(2);assertThat(profile.vlmMax()).isEqualTo(8);assertThat(profile.lipsyncMax()).isZero();
    }

    @Test void missingOrMockVoiceBlocksBeforeTheAdapterTouchesProductionServices(){
        var missing=new DefaultPipelineCanaryProductionAdapter(null,null,null,null,new MockEnvironment());
        assertThatThrownBy(()->missing.start(PipelineCanaryFixture.standard(),"missing-voice","CANARY"))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("PIPELINE_CANARY_TTS_VOICE_INVALID"));
        var mock=new DefaultPipelineCanaryProductionAdapter(null,null,null,null,new MockEnvironment().withProperty("CANARY_TTS_VOICE_ID","mock-voice"));
        assertThatThrownBy(()->mock.start(PipelineCanaryFixture.standard(),"mock-voice","CANARY"))
                .isInstanceOfSatisfying(WorkflowException.class,error->assertThat(error.code()).isEqualTo("PIPELINE_CANARY_TTS_VOICE_INVALID"));
    }
}
