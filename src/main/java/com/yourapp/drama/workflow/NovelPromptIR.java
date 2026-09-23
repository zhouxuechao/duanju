package com.yourapp.drama.workflow;

import java.util.List;

/** Bounded, provider-neutral snapshot for one novel intelligence request. */
public record NovelPromptIR(
        String novelId,
        List<String> chapterIds,
        List<String> chunkIds,
        List<String> entityIds,
        List<String> factIds,
        String profile,
        String style,
        int targetDurationSec,
        String contextHash,
        String sourceBoundary,
        List<String> constraints,
        String sourcePayload) {
    public NovelPromptIR {
        chapterIds=chapterIds==null?List.of():List.copyOf(chapterIds);
        chunkIds=chunkIds==null?List.of():List.copyOf(chunkIds);
        entityIds=entityIds==null?List.of():List.copyOf(entityIds);
        factIds=factIds==null?List.of():List.copyOf(factIds);
        constraints=constraints==null?List.of():List.copyOf(constraints);
        if(!"UNTRUSTED_NOVEL_TEXT".equals(sourceBoundary))throw new IllegalArgumentException("小说来源必须标记为 UNTRUSTED_NOVEL_TEXT");
    }
}
