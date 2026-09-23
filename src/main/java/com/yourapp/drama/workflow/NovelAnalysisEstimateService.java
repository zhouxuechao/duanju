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
    public ObjectNode estimate(String novelId){return estimate(novelId,"STANDARD");}
    public ObjectNode estimate(String novelId,String profile){ObjectNode source=store.get(NOVEL_SOURCE,novelId);var chunks=store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->novelId.equals(text(c,"novelId"))&&c.path("active").asBoolean(true)).toList();int chapters=store.list(NOVEL_CHAPTER,project(source),novelId).size(),groupSize="FAST".equalsIgnoreCase(profile)?15:"DEEP".equalsIgnoreCase(profile)?6:10,arcs=Math.max(1,(chapters+groupSize-1)/groupSize);long chars=chunks.stream().mapToLong(c->c.path("charCount").asLong()).sum(),total=chunks.size()+chapters+arcs+1,input=Math.max(total*4096L,Math.round(chars/2.4)+chapters*1200L+arcs*2400L),output=Math.max(4096,total*4096L);ObjectNode result=obj().put("novelId",novelId).put("profile",profile.toUpperCase()).put("chunks",chunks.size()).put("chapters",chapters).put("arcs",arcs).put("requests",chunks.size()).put("chunkRequests",chunks.size()).put("chapterRequests",chapters).put("arcRequests",arcs).put("globalGraphRequests",1).put("estimatedTotalRequests",total).put("inputCharacters",chars).put("estimatedInputTokens",input).put("estimatedOutputTokens",output).put("approxCost",0).put("pricingStatus","UNPRICED").put("currency","CNY").put("startsAutomatically",false);ObjectNode layers=result.putObject("layerLimits");layers.putObject("CHUNK_ANALYSIS").put("maxRequests",chunks.size());layers.putObject("CHAPTER_SYNTHESIS").put("maxRequests",chapters);layers.putObject("ARC_SYNTHESIS").put("maxRequests",arcs);layers.putObject("GLOBAL_GRAPH").put("maxRequests",1);return result;}
}
