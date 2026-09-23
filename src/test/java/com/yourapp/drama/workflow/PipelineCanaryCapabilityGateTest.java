package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanaryCapabilityGateTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void partialOverallPassesWhenTheExactConsumedCellsAreLiveVerified() throws Exception {
        Path snapshot=write("LIVE_VERIFIED","LIVE_VERIFIED");

        ObjectNode result=new PipelineCanaryCapabilityGate(mapper).requireReady(snapshot);

        assertThat(result.path("ready").asBoolean()).isTrue();
        assertThat(result.path("overall").asText()).isEqualTo("PARTIAL_LIVE_VERIFIED");
        assertThat(result.path("image").asText()).isEqualTo("LIVE_VERIFIED");
        assertThat(result.path("video").asText()).isEqualTo("LIVE_VERIFIED");
    }

    @Test void imageCellThatIsNotLiveVerifiedBlocksBeforeProviderWork() throws Exception {
        Path snapshot=write("STATIC_UNVERIFIED","LIVE_VERIFIED");

        assertThatThrownBy(()->new PipelineCanaryCapabilityGate(mapper).requireReady(snapshot))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("Image 2K/9:16");
    }

    @Test void videoCellThatIsNotLiveVerifiedBlocksBeforeProviderWork() throws Exception {
        Path snapshot=write("LIVE_VERIFIED","STATIC_UNVERIFIED");

        assertThatThrownBy(()->new PipelineCanaryCapabilityGate(mapper).requireReady(snapshot))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("Video 480p/5s");
    }

    private Path write(String image,String video) throws Exception {
        ObjectNode snapshot=obj().put("verificationStatus","PARTIAL_LIVE_VERIFIED");
        snapshot.putObject("imageOutputs").putObject("2K").putObject("9:16").put("status",image);
        snapshot.putObject("videoOutputs").putObject("480p").putObject("5s").put("status",video);
        Path path=temporary.resolve("provider-capability-snapshot.json");
        mapper.writeValue(path.toFile(),snapshot);
        return path;
    }
}
