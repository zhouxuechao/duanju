package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.yourapp.drama.workflow.Documents.obj;

/** Blocks Phase B unless the exact Phase A capability cells it consumes were live verified. */
public final class PipelineCanaryCapabilityGate {
    private final ObjectMapper mapper;

    public PipelineCanaryCapabilityGate(ObjectMapper mapper){this.mapper=mapper;}

    public ObjectNode requireReady(Path snapshotPath){
        if(!Files.isRegularFile(snapshotPath))
            throw new WorkflowException("PIPELINE_CANARY_CAPABILITY_MISSING","Phase A capability snapshot is missing");
        try{
            JsonNode snapshot=mapper.readTree(snapshotPath.toFile());
            String image=snapshot.path("imageOutputs").path("2K").path("9:16").path("status").asText();
            String video=snapshot.path("videoOutputs").path("480p").path("5s").path("status").asText();
            if(!"LIVE_VERIFIED".equals(image))
                throw new WorkflowException("PIPELINE_CANARY_IMAGE_CAPABILITY_BLOCKED","Image 2K/9:16 is not LIVE_VERIFIED");
            if(!"LIVE_VERIFIED".equals(video))
                throw new WorkflowException("PIPELINE_CANARY_VIDEO_CAPABILITY_BLOCKED","Video 480p/5s is not LIVE_VERIFIED");
            return obj().put("ready",true).put("overall",snapshot.path("verificationStatus").asText())
                    .put("image",image).put("video",video);
        }catch(IOException error){
            throw new WorkflowException("PIPELINE_CANARY_CAPABILITY_INVALID","Phase A capability snapshot cannot be read");
        }
    }
}
