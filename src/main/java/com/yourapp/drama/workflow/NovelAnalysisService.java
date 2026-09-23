package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class NovelAnalysisService {
    private static final Set<String> PROFILES=Set.of("FAST","STANDARD","DEEP");
    private final DocumentStore store;
    private final NovelIntelligenceExecutor ai;
    private final NovelPromptCompiler prompts;
    private final NovelEntityResolutionService entities;
    private final NovelAnalysisEstimateService estimates;
    private final NovelIntelligenceQualityValidator quality;
    private final NovelIntelligenceRunService runs;

    public NovelAnalysisService(DocumentStore store,NovelIntelligenceExecutor ai,NovelPromptCompiler prompts,
                                NovelEntityResolutionService entities,NovelAnalysisEstimateService estimates,
                                NovelIntelligenceQualityValidator quality,NovelIntelligenceRunService runs){
        this.store=store;this.ai=ai;this.prompts=prompts;this.entities=entities;this.estimates=estimates;this.quality=quality;this.runs=runs;
    }
    public ObjectNode estimate(String novelId){return estimates.estimate(novelId);}
    public ObjectNode estimate(String novelId,String profile){String normalized=profile==null?"STANDARD":profile.toUpperCase(Locale.ROOT);if(!PROFILES.contains(normalized))throw new IllegalArgumentException("分析档位只支持 FAST、STANDARD、DEEP");return estimates.estimate(novelId,normalized);}
    public ObjectNode start(String novelId,ObjectNode request){
        if(!request.path("confirmed").asBoolean(false))throw new WorkflowException("ANALYSIS_CONFIRMATION_REQUIRED","小说分析必须由用户明确开始");
        return executeRun(novelId,request,false);
    }
    public ObjectNode resume(String novelId,ObjectNode request){return executeRun(novelId,request,true);}

    private ObjectNode executeRun(String novelId,ObjectNode request,boolean resume){ObjectNode source=store.get(NOVEL_SOURCE,novelId);String profile=request.path("profile").asText("STANDARD").toUpperCase(Locale.ROOT);ObjectNode estimate=estimates.estimate(novelId,profile);NovelIntelligenceRunService.Estimate budget=new NovelIntelligenceRunService.Estimate(estimate.path("estimatedTotalRequests").asLong(),estimate.path("estimatedInputTokens").asLong()*2,estimate.path("estimatedOutputTokens").asLong(),0,(ObjectNode)estimate.path("layerLimits").deepCopy());return runs.execute(source,"ANALYSIS",profile,request,resume,budget,()->run(novelId,request,resume));}

    private ObjectNode run(String novelId,ObjectNode request,boolean resume){
        ObjectNode source=store.get(NOVEL_SOURCE,novelId);
        if(!Set.of("READY","ANALYZED").contains(text(source,"status")))throw new WorkflowException("NOVEL_NOT_READY","小说尚未完成解析");
        String profile=request.path("profile").asText("STANDARD").toUpperCase(Locale.ROOT);
        if(!PROFILES.contains(profile))throw new IllegalArgumentException("分析档位只支持 FAST、STANDARD、DEEP");
        Set<String> failures=new HashSet<>();request.path("failChunkIds").forEach(node->failures.add(node.asText()));
        List<ObjectNode> chunks=activeChunks(source);
        for(ObjectNode chunk:chunks){
            ObjectNode latest=latestJob(project(source),id(chunk));String inputHash=chunkHash(chunk,profile);
            boolean same=latest!=null&&inputHash.equals(text(latest,"inputHash"));
            if(same&&"SUCCEEDED".equals(text(latest,"status")))continue;
            if(same&&"FAILED".equals(text(latest,"status"))&&!resume)continue;
            if(resume&&same&&!"FAILED".equals(text(latest,"status")))continue;
            analyzeChunk(source,chunk,profile,inputHash,failures.contains(id(chunk)));
            runs.progress("CHUNK_ANALYSIS",progress(novelId).path("succeededChunks").asLong(),chunks.size());
        }
        ObjectNode state=progress(novelId);
        if(state.path("failedChunks").asInt()==0&&state.path("pendingChunks").asInt()==0){runs.progress("HIERARCHICAL_SYNTHESIS",0,1);aggregate(source,profile);runs.progress("GLOBAL_GRAPH",1,1);}
        state=progress(novelId);
        state.put("status",state.path("failedChunks").asInt()>0?"PARTIAL_FAILED":state.path("pendingChunks").asInt()>0?"RUNNING":"SUCCEEDED");
        return state;
    }

    private void analyzeChunk(ObjectNode source,ObjectNode chunk,String profile,String inputHash,boolean fail){
        ObjectNode chapter=store.get(NOVEL_CHAPTER,required(chunk,"chapterId"));
        int version=store.list(NOVEL_ANALYSIS_JOB,project(source),null).stream().filter(job->id(chunk).equals(text(job,"chunkId"))).mapToInt(job->job.path("analysisVersion").asInt()).max().orElse(0)+1;
        String key="novel:"+id(source)+":chunk:"+id(chunk)+":"+inputHash;
        ObjectNode reusable=store.list(NOVEL_ANALYSIS_JOB,project(source),null).stream().filter(candidate->id(chunk).equals(text(candidate,"chunkId"))&&inputHash.equals(text(candidate,"inputHash"))&&"FAILED".equals(text(candidate,"status"))).findFirst().orElse(null);
        if(reusable!=null)version=reusable.path("analysisVersion").asInt(version);
        ObjectNode job;
        if(reusable!=null)job=store.update(NOVEL_ANALYSIS_JOB,id(reusable),revision(reusable),reusable.deepCopy().put("status","RUNNING").put("attempt",reusable.path("attempt").asInt(1)+1).put("startedAt",Instant.now().toString()).remove(List.of("failureCode","failureReason","finishedAt")));
        else{job=store.create(NOVEL_ANALYSIS_JOB,obj().put("projectId",project(source)).put("novelId",id(source)).put("chapterId",id(chapter)).put("chunkId",id(chunk)).put("status","PENDING").put("idempotencyKey",key).put("contentHash",text(chunk,"contentHash")).put("inputHash",inputHash).put("profile",profile).put("analysisVersion",version).put("attempt",1));job=store.update(NOVEL_ANALYSIS_JOB,id(job),revision(job),job.deepCopy().put("status","RUNNING").put("startedAt",Instant.now().toString()));}
        if(fail){
            store.update(NOVEL_ANALYSIS_JOB,id(job),revision(job),job.deepCopy().put("status","FAILED").put("failureCode","INJECTED_CHUNK_FAILURE").put("finishedAt",Instant.now().toString()));
            return;
        }
        NovelPromptIR ir=ir(source,profile,"",0,inputHash,List.of(id(chapter)),List.of(id(chunk)),List.of(),List.of(),text(chunk,"normalizedText"));
        var result=ai.execute(project(source),ir,prompts.compileChunkAnalysis(ir));ObjectNode output=result.value();quality.chunk(output);
        ObjectNode document=output.deepCopy().put("projectId",project(source)).put("novelId",id(source)).put("chapterId",id(chapter)).put("chunkId",id(chunk)).put("contentHash",text(chunk,"contentHash")).put("inputHash",inputHash).put("analysisVersion",version).put("profile",profile).put("sourceBoundary","UNTRUSTED_NOVEL_TEXT").put("requestInputChars",text(chunk,"normalizedText").length()).put("requestSchemaVersion",1).put("model",result.model()).put("providerRequestId",result.requestId()).put("compilerVersion",NovelPromptCompiler.CHUNK_ANALYSIS_VERSION);
        document.putArray("sourceRefs").add(id(chunk));ObjectNode saved=store.create(NOVEL_CHUNK_ANALYSIS,document);
        materialize(source,chunk,chapter,saved);
        ObjectNode running=store.get(NOVEL_ANALYSIS_JOB,id(job));
        store.update(NOVEL_ANALYSIS_JOB,id(job),revision(running),running.deepCopy().put("status","SUCCEEDED").put("analysisId",id(saved)).put("model",result.model()).put("providerRequestId",result.requestId()).put("finishedAt",Instant.now().toString()));
    }

    private void aggregate(ObjectNode source,String profile){
        List<ObjectNode> chunks=activeChunks(source);
        String aggregateHash=NovelIngestionService.hashText(profile+"|"+NovelPromptCompiler.CHAPTER_SYNTHESIS_VERSION+"|"+NovelPromptCompiler.ARC_SYNTHESIS_VERSION+"|"+NovelPromptCompiler.GLOBAL_GRAPH_VERSION+"|"+ai.cacheIdentity("novel_analysis")+"|"+String.join("|",chunks.stream().map(c->text(c,"contentHash")).toList()));
        if(store.list(NOVEL_STORY_GRAPH,project(source),id(source)).stream().anyMatch(g->aggregateHash.equals(text(g,"inputHash"))))return;
        ObjectNode current=store.get(NOVEL_SOURCE,id(source));int analysisRevision=current.path("analysisRevision").asInt(0)+1;
        List<ObjectNode> chapters=chapters(source),chapterAnalyses=new ArrayList<>();
        for(ObjectNode chapter:chapters){
            List<ObjectNode> children=store.list(NOVEL_CHUNK,project(source),id(chapter)).stream().filter(c->c.path("active").asBoolean(true)).map(c->currentAnalysis(project(source),c,profile)).filter(Objects::nonNull).toList();
            String hash=NovelIngestionService.hashText(profile+"|"+NovelPromptCompiler.CHAPTER_SYNTHESIS_VERSION+"|"+ai.cacheIdentity("novel_analysis")+"|"+String.join("|",children.stream().map(Documents::id).toList()));
            NovelPromptIR ir=ir(source,profile,"",0,hash,List.of(id(chapter)),children.stream().flatMap(a->strings(a.path("sourceRefs"))).toList(),List.of(),List.of(),array(children).toString());
            var result=ai.execute(project(source),ir,prompts.compileChapterSynthesis(ir));ObjectNode value=result.value();quality.chapter(value);
            value.put("projectId",project(source)).put("novelId",id(source)).put("chapterId",id(chapter)).put("analysisRevision",analysisRevision).put("profile",profile).put("inputHash",hash).put("model",result.model()).put("providerRequestId",result.requestId()).put("compilerVersion",NovelPromptCompiler.CHAPTER_SYNTHESIS_VERSION);
            value.putArray("sourceRefs").addAll(ids(children));chapterAnalyses.add(store.create(NOVEL_CHAPTER_ANALYSIS,value));
        }
        List<ObjectNode> arcs=new ArrayList<>();int groupSize=switch(profile){case "FAST"->15;case "DEEP"->6;default->10;};
        for(int from=0;from<chapters.size();from+=groupSize){
            int to=Math.min(chapters.size(),from+groupSize);List<ObjectNode> selected=chapterAnalyses.subList(from,to);
            String hash=NovelIngestionService.hashText(profile+"|"+NovelPromptCompiler.ARC_SYNTHESIS_VERSION+"|"+ai.cacheIdentity("novel_analysis")+"|"+String.join("|",selected.stream().map(Documents::id).toList()));
            NovelPromptIR ir=ir(source,profile,"",0,hash,chapters.subList(from,to).stream().map(Documents::id).toList(),List.of(),List.of(),List.of(),array(selected).toString());
            var result=ai.execute(project(source),ir,prompts.compileArcSynthesis(ir));ObjectNode arc=result.value();quality.arc(arc);
            arc.put("projectId",project(source)).put("novelId",id(source)).put("startChapter",chapters.get(from).path("chapterNo").asInt()).put("endChapter",chapters.get(to-1).path("chapterNo").asInt()).put("analysisRevision",analysisRevision).put("profile",profile).put("inputHash",hash).put("model",result.model()).put("providerRequestId",result.requestId()).put("compilerVersion",NovelPromptCompiler.ARC_SYNTHESIS_VERSION);
            arc.set("sourceChapterAnalysisIds",ids(selected));arcs.add(store.create(NOVEL_STORY_ARC,arc));
        }
        List<String> entityIds=new ArrayList<>();for(ResourceKind kind:List.of(CHARACTER,LOCATION,PROP))store.list(kind,project(source),null).stream().filter(e->id(source).equals(text(e,"novelId"))).map(Documents::id).forEach(entityIds::add);
        List<String> factIds=store.list(STORY_FACT,project(source),null).stream().filter(e->id(source).equals(text(e,"novelId"))).map(Documents::id).toList();
        NovelPromptIR graphIr=ir(source,profile,"",0,aggregateHash,chapters.stream().map(Documents::id).toList(),List.of(),entityIds,factIds,array(arcs).toString());
        var graphResult=ai.execute(project(source),graphIr,prompts.compileGlobalGraph(graphIr));ObjectNode graph=graphResult.value();quality.graph(graph);
        graph.put("projectId",project(source)).put("novelId",id(source)).put("analysisRevision",analysisRevision).put("profile",profile).put("inputHash",aggregateHash).put("model",graphResult.model()).put("providerRequestId",graphResult.requestId()).put("compilerVersion",NovelPromptCompiler.GLOBAL_GRAPH_VERSION);
        graph.set("canonicalCharacters",idsOf(source,CHARACTER));graph.set("canonicalLocations",idsOf(source,LOCATION));graph.set("canonicalProps",idsOf(source,PROP));graph.set("storyFacts",idsOf(source,STORY_FACT));graph.set("relationships",idsOf(source,RELATIONSHIP));graph.set("characterKnowledge",idsOf(source,CHARACTER_KNOWLEDGE));graph.set("storyArcs",ids(arcs));
        store.create(NOVEL_STORY_GRAPH,graph);
        current=store.get(NOVEL_SOURCE,id(source));store.update(NOVEL_SOURCE,id(source),revision(current),current.deepCopy().put("analysisRevision",analysisRevision).put("analysisProfile",profile).put("analysisStatus","SUCCEEDED"));
    }

    private void materialize(ObjectNode source,ObjectNode chunk,ObjectNode chapter,ObjectNode analysis){
        Map<String,String> characters=new LinkedHashMap<>();
        for(JsonNode mention:analysis.path("characterMentions")){String name=mentionName(mention);ObjectNode resolved=entities.resolve(project(source),id(source),"CHARACTER",name,aliases(mention),mention.path("confidence").asDouble(.75),List.of(id(chunk)));if(resolved.hasNonNull("canonicalEntityId"))characters.put(name,text(resolved,"canonicalEntityId"));}
        for(JsonNode mention:analysis.path("locationMentions")){String name=mentionName(mention);entities.resolve(project(source),id(source),"LOCATION",name,aliases(mention),mention.path("confidence").asDouble(.75),List.of(id(chunk)));}
        for(JsonNode mention:analysis.path("propMentions")){String name=mentionName(mention);entities.resolve(project(source),id(source),"PROP",name,aliases(mention),mention.path("confidence").asDouble(.75),List.of(id(chunk)));}
        List<ObjectNode> facts=new ArrayList<>();int factNo=0;
        for(JsonNode factNode:analysis.path("storyFacts")){
            String statement=factNode.isObject()?text(factNode,"statement"):factNode.asText();if(statement.isBlank())continue;
            String key="novel:"+id(source)+":chunk:"+id(chunk)+":fact:"+(++factNo)+":"+NovelIngestionService.hashText(statement);
            ObjectNode existing=store.list(STORY_FACT,project(source),null).stream().filter(f->key.equals(text(f,"factKey"))).findFirst().orElse(null);if(existing!=null){facts.add(existing);continue;}
            ObjectNode fact=obj().put("projectId",project(source)).put("novelId",id(source)).put("factKey",key).put("statement",statement).put("predicate",factNode.path("predicate").asText("SOURCE_ASSERTION")).put("validFromStoryTime",chapter.path("chapterNo").asDouble()).put("status","ACTIVE").put("source","NOVEL");fact.putArray("sourceRefs").add(id(chunk));facts.add(store.create(STORY_FACT,fact));
        }
        for(JsonNode change:analysis.path("knowledgeChanges")){String characterId=characters.get(text(change,"character")),statement=text(change,"fact");ObjectNode fact=facts.stream().filter(item->text(item,"statement").contains(statement)).findFirst().orElse(facts.isEmpty()?null:facts.getFirst());if(characterId!=null&&fact!=null){ObjectNode knowledge=obj().put("projectId",project(source)).put("novelId",id(source)).put("characterId",characterId).put("factId",id(fact)).put("knowledgeState",change.path("state").asText("KNOWN")).put("knownFromStoryTime",chapter.path("chapterNo").asDouble()).put("source","NOVEL");knowledge.putArray("sourceRefs").add(id(chunk));store.create(CHARACTER_KNOWLEDGE,knowledge);}}
        for(JsonNode change:analysis.path("relationshipChanges")){String subject=characters.get(text(change,"subject")),object=characters.get(text(change,"object"));if(subject!=null&&object!=null){ObjectNode relation=obj().put("projectId",project(source)).put("novelId",id(source)).put("subjectCharacterId",subject).put("objectCharacterId",object).put("relationshipType",change.path("type").asText("RELATED")).put("state",change.path("type").asText("RELATED")).put("validFromStoryTime",chapter.path("chapterNo").asDouble()).put("source","NOVEL");relation.putArray("sourceRefs").add(id(chunk));store.create(RELATIONSHIP,relation);}}
    }

    public ObjectNode progress(String novelId){
        ObjectNode source=store.get(NOVEL_SOURCE,novelId);List<ObjectNode> chunks=activeChunks(source);int succeeded=0,failed=0;
        for(ObjectNode chunk:chunks){ObjectNode job=latestJob(project(source),id(chunk));if(job!=null&&chunkHash(chunk,text(job,"profile")).equals(text(job,"inputHash"))){if("SUCCEEDED".equals(text(job,"status")))succeeded++;else if("FAILED".equals(text(job,"status")))failed++;}}
        int chapterDone=(int)store.list(NOVEL_CHAPTER_ANALYSIS,project(source),null).stream().filter(a->novelId.equals(text(a,"novelId"))&&a.path("analysisRevision").asInt()==source.path("analysisRevision").asInt()).count();
        int arcs=(int)store.list(NOVEL_STORY_ARC,project(source),novelId).stream().filter(a->a.path("analysisRevision").asInt()==source.path("analysisRevision").asInt()).count();
        return obj().put("novelId",novelId).put("parsedChapters",source.path("chapterCount").asInt()).put("totalChapters",source.path("chapterCount").asInt()).put("semanticChunks",chunks.size()).put("totalChunks",chunks.size()).put("succeededChunks",succeeded).put("failedChunks",failed).put("pendingChunks",chunks.size()-succeeded-failed).put("chapterSummaries",chapterDone).put("storyArcs",arcs).put("globalGraph",store.list(NOVEL_STORY_GRAPH,project(source),novelId).isEmpty()?"NOT_STARTED":"SUCCEEDED");
    }

    private String chunkHash(ObjectNode chunk,String profile){return NovelIngestionService.hashText(text(chunk,"contentHash")+"|"+NovelPromptCompiler.CHUNK_ANALYSIS_VERSION+"|"+ai.cacheIdentity("novel_analysis")+"|"+profile);}
    private List<ObjectNode> activeChunks(ObjectNode source){Set<String> chapterIds=new HashSet<>();chapters(source).forEach(c->chapterIds.add(id(c)));return store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->id(source).equals(text(c,"novelId"))&&c.path("active").asBoolean(true)&&chapterIds.contains(text(c,"chapterId"))).sorted(Comparator.comparingInt(c->store.get(NOVEL_CHAPTER,text(c,"chapterId")).path("chapterNo").asInt())).toList();}
    private List<ObjectNode> chapters(ObjectNode source){return store.list(NOVEL_CHAPTER,project(source),id(source)).stream().filter(c->c.path("active").asBoolean(true)).sorted(Comparator.comparingDouble(c->c.path("displayOrder").asDouble(c.path("chapterNo").asDouble()))).toList();}
    private ObjectNode latestJob(String projectId,String chunkId){return store.list(NOVEL_ANALYSIS_JOB,projectId,null).stream().filter(job->chunkId.equals(text(job,"chunkId"))).max(Comparator.comparingInt(job->job.path("analysisVersion").asInt())).orElse(null);}
    private ObjectNode currentAnalysis(String projectId,ObjectNode chunk,String profile){String expected=chunkHash(chunk,profile);return store.list(NOVEL_CHUNK_ANALYSIS,projectId,id(chunk)).stream().filter(a->expected.equals(text(a,"inputHash"))).max(Comparator.comparingInt(a->a.path("analysisVersion").asInt())).orElse(null);}
    private NovelPromptIR ir(ObjectNode source,String profile,String style,int duration,String hash,List<String> chapters,List<String> chunks,List<String> entityIds,List<String> factIds,String payload){return new NovelPromptIR(id(source),chapters,chunks,entityIds,factIds,profile,style,duration,hash,"UNTRUSTED_NOVEL_TEXT",List.of("bounded input","bounded output","source ids required"),payload);}
    private ArrayNode idsOf(ObjectNode source,ResourceKind kind){ArrayNode out=array();store.list(kind,project(source),null).stream().filter(e->id(source).equals(text(e,"novelId"))).forEach(e->out.add(id(e)));return out;}
    private static ArrayNode ids(List<ObjectNode> values){ArrayNode out=array();values.forEach(v->out.add(id(v)));return out;}
    private static ArrayNode array(List<ObjectNode> values){ArrayNode out=array();values.forEach(v->out.add(v.deepCopy()));return out;}
    private static ArrayNode array(){return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();}
    private static Stream<String> strings(JsonNode values){List<String> out=new ArrayList<>();values.forEach(v->out.add(v.asText()));return out.stream();}
    private static String mentionName(JsonNode mention){return mention.isObject()?text(mention,"name"):mention.asText();}
    private static List<String> aliases(JsonNode mention){List<String> out=new ArrayList<>();mention.path("aliases").forEach(v->out.add(v.asText()));return out;}
}
