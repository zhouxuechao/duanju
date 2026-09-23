package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

class StoryFactResolverScaleTest {
    @Test
    void resolveBulkLoadsFactsMutationsAndKnowledgeOnce() {
        CountingStore store = new CountingStore();
        List<String> actors = new ArrayList<>();
        for (int i = 0; i < 10; i++) actors.add("character-" + i);
        for (int i = 0; i < 300; i++) {
            String factId = "fact-" + i;
            store.add(ResourceKind.STORY_FACT, obj().put("id", factId).put("projectId", "project-1").put("factKey", factId)
                    .put("statement", "事实 " + i).put("status", "ACTIVE").put("validFromStoryTime", 0).put("revealedAtStoryTime", 200));
            store.add(ResourceKind.STORY_FACT_MUTATION, mutation("mutation-a-" + i, factId, 50, "第一次变化 " + i));
            store.add(ResourceKind.STORY_FACT_MUTATION, mutation("mutation-b-" + i, factId, 100, "第二次变化 " + i));
        }
        for (int i = 0; i < 1000; i++) store.add(ResourceKind.CHARACTER_KNOWLEDGE,
                obj().put("id", "knowledge-" + i).put("projectId", "project-1").put("factId", "fact-" + (i % 300))
                        .put("characterId", actors.get(i % actors.size())).put("knowledgeState", "KNOWN").put("knownFromStoryTime", 0));

        ObjectNode result = new StoryFactResolver(store).resolve("project-1", 75, actors);

        assertThat(result.path("facts")).isNotEmpty();
        assertThat(result.path("facts").get(0).path("factVersion").asInt()).isEqualTo(2);
        assertThat(store.listCalls).isEqualTo(3);
        assertThat(store.getCalls).isZero();
    }

    private static ObjectNode mutation(String id, String factId, double time, String statement) {
        ObjectNode mutation = obj().put("id", id).put("projectId", "project-1").put("factId", factId)
                .put("effectiveFromStoryTime", time).put("operation", "REVISE");
        mutation.set("after", obj().put("statement", statement));
        return mutation;
    }

    private static final class CountingStore implements DocumentStore {
        private final Map<ResourceKind, List<ObjectNode>> values = new EnumMap<>(ResourceKind.class);
        int listCalls;
        int getCalls;

        void add(ResourceKind kind, ObjectNode value) { values.computeIfAbsent(kind, ignored -> new ArrayList<>()).add(value); }
        @Override public ObjectNode get(ResourceKind kind, String id) { getCalls++; return values.getOrDefault(kind, List.of()).stream().filter(v -> id.equals(v.path("id").asText())).findFirst().orElseThrow().deepCopy(); }
        @Override public Optional<ObjectNode> find(ResourceKind kind, String id) { return Optional.ofNullable(get(kind, id)); }
        @Override public List<ObjectNode> list(ResourceKind kind, String projectId, String parentId) { listCalls++; return values.getOrDefault(kind, List.of()).stream().filter(v -> projectId == null || projectId.equals(v.path("projectId").asText())).filter(v -> parentId == null || parentId.equals(v.path("factId").asText())).map(ObjectNode::deepCopy).toList(); }
        @Override public ObjectNode create(ResourceKind kind, ObjectNode document) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode update(ResourceKind kind, String id, long expectedRevision, ObjectNode document) { throw new UnsupportedOperationException(); }
        @Override public ObjectNode getForUpdate(ResourceKind kind, String id) { throw new UnsupportedOperationException(); }
        @Override public <T> T transaction(Supplier<T> work) { return work.get(); }
    }
}
