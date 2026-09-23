package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

import static com.yourapp.drama.workflow.Documents.obj;

/** Immutable model-specific image capabilities; unknown models must never inherit another model's limits. */
public record ImageModelProfile(
        String modelId,String family,String profileVersion,String capabilityFingerprint,String verificationStatus,
        List<String> supportedSizes,String defaultSize,List<String> supportedRatios,List<ImageOutputProfile> outputProfiles,
        boolean supportsReferenceImage,boolean supportsMultipleImages,int maxImageRefs,
        boolean supportsSequentialGeneration,boolean supportsWatermark,boolean supportsRegionEdit) {
    public ImageModelProfile {supportedSizes=List.copyOf(supportedSizes);supportedRatios=List.copyOf(supportedRatios);outputProfiles=List.copyOf(outputProfiles);}
    public ObjectNode toJson(){
        ObjectNode value=obj().put("version",profileVersion).put("profileVersion",profileVersion).put("modelId",modelId).put("modelFamily",family)
                .put("capabilityFingerprint",capabilityFingerprint).put("verificationStatus",verificationStatus).put("source","MODEL_PROFILE")
                .put("defaultSize",defaultSize).put("supportsReferenceImage",supportsReferenceImage).put("supportsMultipleImages",supportsMultipleImages)
                .put("maxImageRefs",maxImageRefs).put("supportsSequentialGeneration",supportsSequentialGeneration).put("supportsWatermark",supportsWatermark).put("supportsRegionEdit",supportsRegionEdit);
        ArrayNode sizes=value.putArray("supportedSizes");supportedSizes.forEach(sizes::add);ArrayNode ratios=value.putArray("supportedRatios");supportedRatios.forEach(ratios::add);ArrayNode outputs=value.putArray("outputProfiles");outputProfiles.forEach(output->outputs.add(output.toJson()));return value;
    }
    public void requireSize(String size){if(!supportedSizes.contains(size))throw new IllegalArgumentException("IMAGE_SIZE_UNSUPPORTED_BY_PROFILE: "+size);}
}
