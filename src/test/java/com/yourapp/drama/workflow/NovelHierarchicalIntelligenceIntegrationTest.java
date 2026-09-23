package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class NovelHierarchicalIntelligenceIntegrationTest {
    @Autowired DocumentStore store;@Autowired NovelAnalysisService analysis;
    @MockitoBean NovelIntelligenceProvider provider;

    @Test void naturalNovelIsAnalyzedChunkToChapterToArcToGraphWithoutRawTextLeakingUpward(){
        List<NovelPromptCompiler.Compiled> requests=new CopyOnWriteArrayList<>();
        when(provider.cacheIdentity(any())).thenReturn("fixture-model-v1");
        when(provider.execute(any())).thenAnswer(call->{NovelPromptCompiler.Compiled prompt=call.getArgument(0);requests.add(prompt);ObjectNode value=switch(prompt.taskType()){case "CHUNK_ANALYSIS"->chunk();case "CHAPTER_SYNTHESIS"->chapter();case "ARC_SYNTHESIS"->arc();case "GLOBAL_GRAPH"->graph();default->throw new AssertionError(prompt.taskType());};return new NovelIntelligenceProvider.Result(value,"FAKE","fixture-model-v1","req-"+requests.size(),"stop",100,80,true);});
        ObjectNode project=store.create(PROJECT,obj().put("name","自然小说"));ObjectNode source=store.create(NOVEL_SOURCE,obj().put("projectId",id(project)).put("title","裂屏").put("format","TXT").put("storageKey","00000000-0000-0000-0000-000000000000.txt").put("fileName","fixture.txt").put("fileSize",200).put("fileHash","a".repeat(64)).put("status","READY").put("chapterCount",2).put("totalCharacters",100).put("parsingRevision",1));
        List<String> prose=List.of("林川推开客厅的门，苏宁藏起摔裂的黑色手机。RAW_SENTINEL_A","雨夜里苏宁把手机交给林川，坦白自己早已看见匿名短信。RAW_SENTINEL_B");
        for(int i=0;i<prose.size();i++){String body=prose.get(i);ObjectNode chapter=store.create(NOVEL_CHAPTER,obj().put("projectId",id(project)).put("novelId",id(source)).put("chapterNo",i+1).put("displayOrder",i+1).put("title","第"+(i+1)+"章").put("normalizedText",body).put("rawText",body).put("charCount",body.length()).put("sourceStart",i*100).put("sourceEnd",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)).put("active",true).put("parsingRevision",1));store.create(NOVEL_CHUNK,obj().put("projectId",id(project)).put("novelId",id(source)).put("chapterId",id(chapter)).put("chunkNo",1).put("normalizedText",body).put("charCount",body.length()).put("sourceStart",i*100).put("sourceEnd",i*100+body.length()).put("contentHash",NovelIngestionService.hashText(body)).put("active",true).put("parsingRevision",1));}
        assertThat(text(analysis.start(id(source),obj().put("confirmed",true).put("profile","DEEP")),"status")).isEqualTo("SUCCEEDED");
        assertThat(requests).extracting(NovelPromptCompiler.Compiled::taskType).containsExactly("CHUNK_ANALYSIS","CHUNK_ANALYSIS","CHAPTER_SYNTHESIS","CHAPTER_SYNTHESIS","ARC_SYNTHESIS","GLOBAL_GRAPH");
        assertThat(requests.stream().filter(r->!r.taskType().equals("CHUNK_ANALYSIS")).map(NovelPromptCompiler.Compiled::userPrompt)).allSatisfy(prompt->assertThat(prompt).doesNotContain("RAW_SENTINEL_A","RAW_SENTINEL_B"));
        assertThat(store.list(com.yourapp.drama.persistence.ResourceKind.CHARACTER,id(project),null)).isNotEmpty();assertThat(store.list(STORY_FACT,id(project),null)).isNotEmpty();
    }
    private static ObjectNode chunk(){ObjectNode v=obj().put("summary","两人围绕手机交换秘密").put("narrativeImportance",80);arrays(v,"events","characterMentions","locationMentions","propMentions","storyFacts","relationshipChanges","knowledgeChanges","characterStateChanges","locationStateChanges","propStateChanges","timelineHints","conflicts","foreshadowing","payoffs","importantDialogue","openQuestions","adaptationSignals");mention(v,"characterMentions","林川");mention(v,"characterMentions","苏宁");mention(v,"locationMentions","客厅");mention(v,"propMentions","黑色手机");v.withArray("storyFacts").addObject().put("statement","苏宁见过匿名短信").put("predicate","KNOWLEDGE").putArray("evidence").add("短信");v.withArray("knowledgeChanges").addObject().put("character","苏宁").put("fact","匿名短信").put("state","KNOWN");v.withArray("relationshipChanges").addObject().put("subject","林川").put("object","苏宁").put("type","TRUSTING");return v;}
    private static ObjectNode chapter(){ObjectNode v=obj().put("chapterSummary","秘密交换推动信任").put("openingState","彼此戒备").put("endingState","开始信任");arrays(v,"turningPoints","characters","locations","props","facts","relationshipChanges","knowledgeChanges","stateChanges","conflicts","foreshadowing","payoffs","adaptationSignals");return v;}
    private static ObjectNode arc(){ObjectNode v=obj().put("arcGoal","查明短信来源").put("mainConflict","是否坦白").put("climax","手机被交出").put("resolution","结成临时同盟");arrays(v,"turningPoints","characterArcs","factsIntroduced","factsResolved","foreshadowing","payoffs","adaptationSignals");return v;}
    private static ObjectNode graph(){ObjectNode v=obj().put("mainPlot","两人追查匿名短信");arrays(v,"subPlots","storyArcs","canonicalCharacters","canonicalLocations","canonicalProps","storyFacts","relationships","characterKnowledge","timelineEvents","worldRules","foreshadowing","payoffs","characterArcs");return v;}
    private static void mention(ObjectNode v,String field,String name){ObjectNode m=v.withArray(field).addObject().put("name",name).put("description",name).put("roleHint","fixture").put("confidence",.98);m.putArray("aliases");m.putArray("evidence").add(name);}
    private static void arrays(ObjectNode value,String... names){for(String name:names)value.putArray(name);}
}
