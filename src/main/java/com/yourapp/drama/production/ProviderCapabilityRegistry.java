package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import static com.yourapp.drama.workflow.Documents.obj;

/** Capabilities are restricted to parameters implemented by the current adapters. */
@Component
public class ProviderCapabilityRegistry {
    public static final String VERSION="volcengine-adapter-2026-09-21";
    public ObjectNode image(){ObjectNode value=obj().put("version",VERSION).put("source","ADAPTER_ALLOWLIST").put("supportsMultipleImages",true).put("supportsRegionEdit",false).put("maxImageRefs",10);value.putArray("supportedRatios").add("9:16").add("16:9").add("1:1");return value;}
    public ObjectNode video(){ObjectNode value=obj().put("version",VERSION).put("source","ADAPTER_ALLOWLIST").put("supportsMultipleImages",false).put("supportsReferenceVideo",true).put("supportsReferenceAudio",true).put("supportsRegionEdit",false).put("supportsNativeAudio",true).put("supportsStartEndFrame",true).put("maxImageRefs",1).put("maxVideoRefs",1).put("maxAudioRefs",1);value.putArray("supportedRatios").add("9:16").add("16:9").add("1:1");return value;}
}
