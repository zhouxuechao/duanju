package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.yourapp.drama.workflow.Documents.obj;

/** Converts explicit Live Canary evidence into an auditable capability snapshot. */
public final class ProviderCapabilityContract {
    /** Verifies only the exact output combinations observed by the low-cost Phase A canary. */
    public ObjectNode verifyCanary(ImageModelProfile imageProfile,VideoModelProfile videoProfile,JsonNode evidence){
        requireText(evidence,"provider","VOLCENGINE");requireText(evidence,"evidenceSource","LIVE_CANARY");
        try{Instant.parse(required(evidence,"checkedAt"));}catch(RuntimeException error){throw invalid("checkedAt must be an ISO-8601 instant");}
        JsonNode image=evidence.path("image"),video=evidence.path("video");
        requireText(image,"modelId",imageProfile.modelId());requireText(video,"modelId",videoProfile.modelId());
        required(image,"providerRequestId");required(video,"providerRequestId");required(video,"providerTaskId");
        String quality=required(image,"requestedImageQuality"),ratio=required(image,"aspectRatioIntent"),providerSize=required(image,"providerSize"),resolution=required(video,"requestedResolution");
        imageProfile.requireSize(quality);if(imageProfile.outputProfiles().stream().noneMatch(output->output.imageQuality().equals(quality)&&output.aspectRatioIntent().equals(ratio)&&output.providerSize().equals(providerSize)))throw invalid("image output profile was not configured");
        videoProfile.requireResolution(resolution);int requestedDuration=video.path("requestedDuration").asInt();if(videoProfile.providerDuration(requestedDuration)!=requestedDuration)throw invalid("requestedDuration was not an exact supported duration");
        int imageWidth=positive(image,"providerReturnedWidth"),imageHeight=positive(image,"providerReturnedHeight"),videoWidth=positive(video,"actualWidth"),videoHeight=positive(video,"actualHeight");long actualDuration=video.path("actualDurationMs").asLong();
        double actualRatio=imageWidth/(double)imageHeight,targetRatio=ratio(ratio);boolean imageVerified=Math.abs(actualRatio-targetRatio)<=.01;
        boolean resolutionVerified=resolutionMatches(resolution,videoWidth,videoHeight);
        boolean durationVerified=actualDuration>0&&Math.abs(actualDuration-requestedDuration*1000L)<=1000;
        boolean handoffVerified=video.path("providerUrlHandoff").asBoolean()&&!required(video,"firstFrameFingerprint").isBlank()&&required(video,"firstFrameFingerprint").equals(required(video,"sourceImageFingerprint"));
        List<String> failures=new ArrayList<>();if(!imageVerified)failures.add("IMAGE_ASPECT_RATIO_MISMATCH");if(!resolutionVerified)failures.add("VIDEO_RESOLUTION_MISMATCH");if(!durationVerified)failures.add("VIDEO_DURATION_MISMATCH");if(!handoffVerified)failures.add("PROVIDER_URL_HANDOFF_MISMATCH");
        ObjectNode snapshot=obj().put("provider","VOLCENGINE").put("checkedAt",required(evidence,"checkedAt"))
                .put("verificationStatus",failures.isEmpty()?"PARTIAL_LIVE_VERIFIED":"STATIC_UNVERIFIED")
                .put("imageModel",imageProfile.modelId()).put("imageCapabilityFingerprint",imageProfile.capabilityFingerprint())
                .put("videoModel",videoProfile.modelId()).put("videoCapabilityFingerprint",videoProfile.capabilityFingerprint());
        ObjectNode imageOutputs=snapshot.putObject("imageOutputs");for(ImageOutputProfile output:imageProfile.outputProfiles()){
            boolean tested=output.imageQuality().equals(quality)&&output.aspectRatioIntent().equals(ratio);ObjectNode sizes=imageOutputs.withObject("/"+escape(output.imageQuality()));
            ObjectNode cell=obj().put("status",tested&&imageVerified?"LIVE_VERIFIED":"STATIC_UNVERIFIED");if(tested)cell.put("providerRequestId",required(image,"providerRequestId")).put("providerSize",providerSize).put("actualWidth",imageWidth).put("actualHeight",imageHeight).put("actualRatio",actualRatio);sizes.set(output.aspectRatioIntent(),cell);
        }
        ObjectNode videoOutputs=snapshot.putObject("videoOutputs");for(String supportedResolution:videoProfile.supportedResolutions())for(int duration:durations(videoProfile)){
            boolean tested=supportedResolution.equals(resolution)&&duration==requestedDuration;ObjectNode byDuration=videoOutputs.withObject("/"+escape(supportedResolution));ObjectNode cell=obj().put("status",tested&&resolutionVerified&&durationVerified&&handoffVerified?"LIVE_VERIFIED":"STATIC_UNVERIFIED");if(tested)cell.put("providerRequestId",required(video,"providerRequestId")).put("providerTaskId",required(video,"providerTaskId")).put("actualWidth",videoWidth).put("actualHeight",videoHeight).put("actualDurationMs",actualDuration).put("providerUrlHandoff",handoffVerified);byDuration.set(duration+"s",cell);
        }
        var failureArray=snapshot.putArray("failures");failures.forEach(failureArray::add);snapshot.putArray("providerRequestIds").add(required(image,"providerRequestId")).add(required(video,"providerRequestId"));return snapshot;
    }

    public ObjectNode verify(VideoModelProfile profile, JsonNode evidence) {
        requireText(evidence, "provider", "VOLCENGINE");
        requireText(evidence, "model", profile.modelId());
        requireText(evidence, "evidenceSource", "LIVE_CANARY");
        try { Instant.parse(required(evidence, "checkedAt")); }
        catch (RuntimeException error) { throw invalid("checkedAt must be an ISO-8601 instant"); }
        JsonNode expected = profile.toJson();
        requireEqual(evidence, expected, "supportedDurations");
        requireEqual(evidence, expected, "durationMode");
        requireEqual(evidence, expected, "minDuration");
        requireEqual(evidence, expected, "maxDuration");
        requireEqual(evidence, expected, "durationStep");
        requireEqual(evidence, expected, "supportedResolutions");
        requireEqual(evidence, expected, "supportedRatios");
        requireEqual(evidence, expected, "supportedTaskTypes");
        if (!evidence.path("referenceLimits").equals(expected.path("hardLimits"))) throw invalid("referenceLimits differ from the configured profile");
        for (String feature : List.of("firstFrame", "firstLastFrame", "referenceImage", "referenceVideo", "referenceAudio", "nativeAudio", "providerOptions"))
            if (!evidence.path(feature).asBoolean(false)) throw invalid(feature + " was not verified");
        if (!evidence.path("providerRequestIds").isArray() || evidence.path("providerRequestIds").isEmpty()) throw invalid("providerRequestIds are required");
        ObjectNode snapshot = obj().put("provider", "VOLCENGINE").put("model", profile.modelId())
                .put("checkedAt", required(evidence, "checkedAt")).put("verificationStatus", "LIVE_VERIFIED")
                .put("profileVersion", profile.profileVersion()).put("capabilityFingerprint", profile.capabilityFingerprint());
        for (String field : List.of("supportedDurations", "durationMode", "minDuration", "maxDuration", "durationStep", "supportedResolutions", "supportedRatios", "supportedTaskTypes")) snapshot.set(field, expected.path(field).deepCopy());
        snapshot.set("referenceLimits", expected.path("hardLimits").deepCopy());
        snapshot.set("providerRequestIds", evidence.path("providerRequestIds").deepCopy());
        return snapshot;
    }

    private void requireEqual(JsonNode actual, JsonNode expected, String field) {
        if (!actual.path(field).equals(expected.path(field))) throw invalid(field + " differ from the configured profile");
    }
    private int positive(JsonNode node,String field){int value=node.path(field).asInt();if(value<=0)throw invalid(field+" must be positive");return value;}
    private double ratio(String value){String[] pair=value.split(":");if(pair.length!=2)throw invalid("aspectRatioIntent is invalid");try{double width=Double.parseDouble(pair[0]),height=Double.parseDouble(pair[1]);if(width<=0||height<=0)throw new NumberFormatException();return width/height;}catch(NumberFormatException error){throw invalid("aspectRatioIntent is invalid");}}
    private List<Integer> durations(VideoModelProfile profile){if(!"RANGE".equals(profile.durationMode()))return profile.supportedDurations();List<Integer> values=new ArrayList<>();for(int value=profile.minDuration();value<=profile.maxDuration();value+=Math.max(1,profile.durationStep()))values.add(value);return values;}
    private boolean resolutionMatches(String resolution,int width,int height){try{if(!resolution.toLowerCase().endsWith("p"))return false;int expected=Integer.parseInt(resolution.substring(0,resolution.length()-1));return Math.min(width,height)==expected;}catch(RuntimeException ignored){return false;}}
    private String escape(String value){return value.replace("~","~0").replace("/","~1");}
    private void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(node.path(field).asText())) throw invalid(field + " must be " + expected);
    }
    private String required(JsonNode node, String field) {
        String value=node.path(field).asText();if(value.isBlank())throw invalid(field+" is required");return value;
    }
    private IllegalArgumentException invalid(String message) { return new IllegalArgumentException("CAPABILITY_EVIDENCE_INVALID: " + message); }
}
