package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class NovelAnalysisEstimateService {
    private final DocumentStore store;
    public NovelAnalysisEstimateService(DocumentStore store){this.store=store;}
    public ObjectNode estimate(String novelId){ObjectNode source=store.get(NOVEL_SOURCE,novelId);var chunks=store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->novelId.equals(text(c,"novelId"))&&c.path("active").asBoolean(true)).toList();long chars=chunks.stream().mapToLong(c->c.path("charCount").asLong()).sum();return obj().put("novelId",novelId).put("chunks",chunks.size()).put("requests",chunks.size()).put("inputCharacters",chars).put("estimatedInputTokens",Math.max(1,Math.round(chars/2.4))).put("approxCost",Math.round(chars/10000d*0.02d*10000d)/10000d).put("currency","CNY").put("startsAutomatically",false);}
}
