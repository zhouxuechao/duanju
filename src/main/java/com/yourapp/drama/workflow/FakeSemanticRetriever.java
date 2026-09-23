package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.NOVEL_CHUNK;
import static com.yourapp.drama.workflow.Documents.*;

@Component
public class FakeSemanticRetriever implements SemanticRetriever {
    private final DocumentStore store;
    public FakeSemanticRetriever(DocumentStore store){this.store=store;}
    @Override public List<ObjectNode> retrieve(String novelId,String query,int limit){ObjectNode source=store.get(com.yourapp.drama.persistence.ResourceKind.NOVEL_SOURCE,novelId);return store.list(NOVEL_CHUNK,project(source),null).stream().filter(c->novelId.equals(text(c,"novelId"))&&c.path("active").asBoolean(true)).sorted(Comparator.comparingInt(c->distance(text(c,"normalizedText"),query))).limit(Math.max(0,limit)).toList();}
    private static int distance(String text,String query){if(text.contains(query))return 0;Set<Integer> a=text.codePoints().collect(HashSet::new,Set::add,Set::addAll),b=query.codePoints().collect(HashSet::new,Set::add,Set::addAll);int common=0;for(Integer value:b)if(a.contains(value))common++;return b.size()-common;}
}
