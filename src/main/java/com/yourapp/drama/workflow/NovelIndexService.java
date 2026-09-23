package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class NovelIndexService {
    private final DocumentStore store;private final SemanticRetriever semantic;
    public NovelIndexService(DocumentStore store,SemanticRetriever semantic){this.store=store;this.semantic=semantic;}
    public List<ObjectNode> keyword(String novelId,String query,int limit){ObjectNode source=store.get(NOVEL_SOURCE,novelId);String needle=query.toLowerCase(Locale.ROOT);return store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->novelId.equals(text(c,"novelId"))&&c.path("active").asBoolean(true)&&text(c,"normalizedText").toLowerCase(Locale.ROOT).contains(needle)).limit(limit).toList();}
    public List<ObjectNode> byChapterRange(String novelId,int from,int to){ObjectNode source=store.get(NOVEL_SOURCE,novelId);Set<String> chapterIds=new HashSet<>();store.list(NOVEL_CHAPTER,project(source),novelId).stream().filter(c->c.path("chapterNo").asInt()>=from&&c.path("chapterNo").asInt()<=to&&c.path("active").asBoolean(true)).forEach(c->chapterIds.add(id(c)));return store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->chapterIds.contains(text(c,"chapterId"))&&c.path("active").asBoolean(true)).toList();}
    public List<ObjectNode> byEntity(String novelId,String entity){ObjectNode source=store.get(NOVEL_SOURCE,novelId);return store.list(NOVEL_CHUNK_ANALYSIS,project(source),null).stream().filter(a->contains(a.path("characterMentions"),entity)||contains(a.path("locationMentions"),entity)||contains(a.path("propMentions"),entity)).toList();}
    public List<ObjectNode> byFact(String novelId,String fact){ObjectNode source=store.get(NOVEL_SOURCE,novelId);return store.list(STORY_FACT,project(source),null).stream().filter(item->novelId.equals(text(item,"novelId"))&&text(item,"statement").contains(fact)).toList();}
    public List<ObjectNode> byStoryArc(String novelId,int startChapter){ObjectNode source=store.get(NOVEL_SOURCE,novelId);return store.list(NOVEL_STORY_ARC,project(source),novelId).stream().filter(arc->arc.path("startChapter").asInt()==startChapter).toList();}
    public List<ObjectNode> semantic(String novelId,String query,int limit){return semantic.retrieve(novelId,query,limit);}
    private static boolean contains(com.fasterxml.jackson.databind.JsonNode values,String needle){for(var value:values)if(value.asText().equals(needle))return true;return false;}
}
