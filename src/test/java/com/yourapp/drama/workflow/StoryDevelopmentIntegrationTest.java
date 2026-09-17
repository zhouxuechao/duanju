package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.GenerationWorker;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.persistence.DocumentStore;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryDevelopmentIntegrationTest {
    @Autowired DocumentStore store;
    @Autowired StoryDevelopmentService development;
    @Autowired GenerationWorker worker;
    @Autowired JobService jobs;
    @Autowired ObjectMapper mapper;
    @MockitoBean LlmGateway llm;

    private String projectId;

    @BeforeEach
    void setup() {
        ObjectNode project = store.create(PROJECT, obj().put("name", "分阶段创作").put("idea", "雨夜寻找失踪的信").put("episodeCount", 10).put("targetDuration", 20).put("ratio", "9:16"));
        projectId = id(project);
        when(llm.generate(any(), eq(JsonNode.class))).thenAnswer(inv -> resultFor(inv.getArgument(0)));
    }

    @Test
    void coreGenerationDoesNotCallBatchOrScriptAndConfirmationCreatesOnlyFirstBatch() {
        ObjectNode core = generateCore();
        assertThat(text(core, "reviewStatus")).isEqualTo("REVIEW");
        verify(llm, times(1)).generate(argThat(r -> r.userPrompt().contains("\"phase\":\"CORE\"")), eq(JsonNode.class));
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).noneMatch(d -> Set.of("OUTLINE_BATCH", "EPISODE_SCRIPT").contains(text(d, "documentType")));

        ObjectNode confirmed = development.confirm(id(core), obj().put("revision", revision(core)).put("batchSize", 5));
        assertThat(text(confirmed, "reviewStatus")).isEqualTo("CONFIRMED");
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).filteredOn(d -> "OUTLINE_BATCH".equals(text(d, "documentType"))).hasSize(1);
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).filteredOn(d -> "EPISODE_SCRIPT".equals(text(d, "documentType"))).isEmpty();
    }

    @Test
    void tenEpisodesUseTwoFiveEpisodeBatchesAndOnlyAllConfirmedStartScriptOne() {
        ObjectNode core = generateCore();
        ObjectNode batch1 = confirmAndGenerate(core, 5);
        assertThat(batch1.path("startEpisode").asInt()).isEqualTo(1);
        assertThat(batch1.path("endEpisode").asInt()).isEqualTo(5);
        ObjectNode batch1Done = confirm(batch1);
        ObjectNode batch2 = latest("OUTLINE_BATCH", 2);
        assertThat(batch2.path("startEpisode").asInt()).isEqualTo(6);
        assertThat(batch2.path("endEpisode").asInt()).isEqualTo(10);
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).filteredOn(d -> "EPISODE_SCRIPT".equals(text(d, "documentType"))).isEmpty();
        confirm(batch2);
        ObjectNode script1 = latest("EPISODE_SCRIPT", 1);
        assertThat(script1.path("episodeNo").asInt()).isEqualTo(1);
        assertThat(text(script1, "reviewStatus")).isEqualTo("REVIEW");
        assertThat(text(script1.path("sourceSnapshot"), "coreHash")).isNotBlank();
        assertThat(text(script1.path("sourceSnapshot"), "batchHash")).isNotBlank();
    }

    @Test
    void scriptConfirmationMaterializesEpisodeSceneAndSchedulesNextScript() {
        ObjectNode core = generateCore();
        ObjectNode b1 = confirmAndGenerate(core, 5); confirm(b1);
        ObjectNode b2 = latest("OUTLINE_BATCH", 2); confirm(b2);
        ObjectNode script1 = latest("EPISODE_SCRIPT", 1);
        worker.tick();
        script1 = store.get(STORY_DOCUMENT, id(script1));
        ObjectNode confirmed = development.confirm(id(script1), obj().put("revision", revision(script1)));
        assertThat(text(confirmed, "reviewStatus")).isEqualTo("CONFIRMED");
        assertThat(store.list(EPISODE, projectId, null)).hasSize(1);
        String episodeId = id(store.list(EPISODE, projectId, null).getFirst());
        assertThat(store.list(SCENE, projectId, episodeId)).hasSize(1);
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).filteredOn(d -> "EPISODE_SCRIPT".equals(text(d, "documentType"))).hasSize(2);
        assertThat(text(latest("EPISODE_SCRIPT", 2), "reviewStatus")).isEqualTo("GENERATING");
    }

    @Test
    void repeatedConfirmationIsIdempotentAndDoesNotEnqueueDuplicateSuccessor() {
        ObjectNode core = generateCore();
        ObjectNode confirmed = development.confirm(id(core), obj().put("revision", revision(core)).put("batchSize", 5));
        long jobsBefore = store.list(GENERATION_JOB, projectId, null).stream().filter(j -> "STORY".equals(text(j, "type"))).count();
        ObjectNode repeated = development.confirm(id(confirmed), obj());
        long jobsAfter = store.list(GENERATION_JOB, projectId, null).stream().filter(j -> "STORY".equals(text(j, "type"))).count();
        assertThat(id(repeated)).isEqualTo(id(confirmed));
        assertThat(jobsAfter).isEqualTo(jobsBefore);
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).filteredOn(d -> "OUTLINE_BATCH".equals(text(d, "documentType"))).hasSize(1);
    }

    @Test
    void continuitySnapshotIsIncludedInEveryDownstreamLlmRequest() {
        ObjectNode core = generateCore();
        ObjectNode batch = confirmAndGenerate(core, 5);
        ArgumentCaptor<LlmGateway.StructuredRequest> requests = ArgumentCaptor.forClass(LlmGateway.StructuredRequest.class);
        verify(llm, atLeast(2)).generate(requests.capture(), eq(JsonNode.class));
        assertThat(requests.getAllValues().getLast().userPrompt()).contains("continuitySnapshot").contains(text(core, "id"));
        assertThat(requests.getAllValues().getLast().userPrompt()).contains("coreId");
    }

    @Test
    void failedBatchCanRetryLocallyButUncertainSubmissionCannotBeRetried() {
        ObjectNode core = generateCore();
        development.confirm(id(core), obj().put("revision", revision(core)).put("batchSize", 5));
        ObjectNode batch = latest("OUTLINE_BATCH", 1);
        reset(llm);
        when(llm.generate(any(), eq(JsonNode.class))).thenThrow(new ProviderException("TEMPORARY", "暂时失败", "provider-batch-1", 503, true, false));
        worker.tick();
        ObjectNode failed = store.get(STORY_DOCUMENT, id(batch));
        assertThat(text(failed, "reviewStatus")).isEqualTo("FAILED");
        ObjectNode retried = development.retry(id(failed));
        assertThat(text(retried, "reviewStatus")).isEqualTo("GENERATING");
        ObjectNode uncertainJob = store.get(GENERATION_JOB, required(retried, "generationJobId"));
        uncertainJob = jobs.claim().orElseThrow();
        jobs.fail(id(uncertainJob), "UNKNOWN", "provider accepted", false, true);
        assertThatThrownBy(() -> development.retry(id(retried))).isInstanceOf(WorkflowException.class).hasMessageContaining("不确定");
    }

    @Test
    void confirmedBatchEditCreatesNewVersionThatCanBeConfirmedAndContinued() {
        ObjectNode core = generateCore();
        development.confirm(id(core), obj().put("revision", revision(core)).put("batchSize", 5));
        ObjectNode batch1 = latest("OUTLINE_BATCH", 1); worker.tick(); batch1 = store.get(STORY_DOCUMENT, id(batch1));
        ObjectNode confirmed = development.confirm(id(batch1), obj().put("revision", revision(batch1)));
        ObjectNode currentBatch = store.get(STORY_DOCUMENT, id(batch1));
        ObjectNode changed = currentBatch.path("content").deepCopy();
        ((ObjectNode) changed.withArray("episodes").get(0)).put("summary", "修订后的第一集线索");
        ObjectNode revised = development.edit(id(confirmed), obj().put("revision", revision(confirmed)).set("content", changed));
        assertThat(id(revised)).isNotEqualTo(id(confirmed));
        ObjectNode reconfirmed = development.confirm(id(revised), obj().put("revision", revision(revised)));
        assertThat(text(reconfirmed, "reviewStatus")).isEqualTo("CONFIRMED");
        assertThat(store.list(STORY_DOCUMENT, projectId, null)).filteredOn(d -> "OUTLINE_BATCH".equals(text(d, "documentType")) && d.path("batchNo").asInt() == 2 && !d.path("stale").asBoolean()).hasSize(1);
    }

    @Test
    void editingConfirmedCorePreservesOldContentAndMarksDownstreamStale() {
        ObjectNode core = generateCore();
        ObjectNode confirmedCore = development.confirm(id(core), obj().put("revision", revision(core)).put("batchSize", 5));
        ObjectNode batch = latest("OUTLINE_BATCH", 1); worker.tick(); batch = store.get(STORY_DOCUMENT, id(batch));
        ObjectNode edited = development.edit(id(confirmedCore), obj().put("revision", revision(confirmedCore)).set("content", coreContent("新标题")));
        assertThat(id(edited)).isNotEqualTo(id(confirmedCore));
        assertThat(store.get(STORY_DOCUMENT, id(confirmedCore)).path("stale").asBoolean()).isTrue();
        assertThat(store.get(STORY_DOCUMENT, id(batch)).path("stale").asBoolean()).isTrue();
        assertThat(text(edited.path("content"), "title")).isEqualTo("新标题");
    }

    @Test
    void structuralValidationRejectsMissingEpisodeCardAndUnknownAssetReference() {
        ObjectNode core = generateCore();
        ObjectNode batch = confirmAndGenerate(core, 5);
        ObjectNode invalid = batch.deepCopy();
        invalid.with("content").withArray("episodes").removeAll().add(card(1, "start", "end")).add(card(3, "end", "end3")).add(card(4, "end3", "end4")).add(card(5, "end4", "end5"));
        assertThatThrownBy(() -> development.edit(id(batch), obj().put("revision", revision(batch)).set("content", invalid.path("content")))).isInstanceOf(IllegalArgumentException.class);

        ObjectNode unknown = batch.path("content").deepCopy();
        ((ArrayNode)unknown.path("episodes").get(0).path("characterKeys")).add("ghost");
        assertThatThrownBy(() -> development.edit(id(batch), obj().put("revision", revision(batch)).set("content", unknown))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("资产");
    }

    @Test
    void stageDurationMustMatchProjectTargetWithoutMultiSecondDrift() {
        ObjectNode core = generateCore();
        ObjectNode batch = confirmAndGenerate(core, 5);
        ObjectNode changed = batch.path("content").deepCopy();
        ((ObjectNode)changed.path("episodes").get(0).path("scenePlan").get(0)).put("duration",19);
        assertThatThrownBy(() -> development.edit(id(batch), obj().put("revision", revision(batch)).set("content", changed)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("时长");
    }

    @Test
    void startingStoryWithoutEpisodeCountIsRejectedBeforeAnyPaidGeneration() {
        ObjectNode project = store.create(PROJECT, obj().put("name", "缺少集数").put("idea", "不完整请求").put("targetDuration", 20));
        assertThatThrownBy(() -> development.start(id(project), obj())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("episodeCount");
        assertThat(store.list(GENERATION_JOB, id(project), null)).isEmpty();
    }

    @Test
    void providerRequestAndModelIdentifiersSurvivePersistence() {
        ObjectNode core = generateCore();
        ObjectNode job = store.get(GENERATION_JOB, required(core, "generationJobId"));
        assertThat(text(job, "providerRequestId")).isEqualTo("req-CORE");
        assertThat(text(job, "model")).isEqualTo("fixture-model");
        assertThat(text(store.get(STORY_DOCUMENT, id(core)), "providerRequestId")).isEqualTo("req-CORE");
    }

    @Test
    void truncatedProviderOutputKeepsBoundedDiagnosticsAndNeverBecomesDocumentContent() {
        ObjectNode draft = development.start(projectId, obj());
        String partial = "{\"characters\":[" + "x".repeat(70_000) + "TAIL";
        reset(llm);
        when(llm.generate(any(), eq(JsonNode.class))).thenThrow(
                new ProviderException("OUTPUT_TRUNCATED", "模型输出达到长度上限", "req-truncated", 200, false, false)
                        .withRawOutput(partial)
                        .withProviderDiagnostics("length", 721, 16_384, 17_105));

        worker.tick();

        ObjectNode document = store.get(STORY_DOCUMENT, id(draft));
        ObjectNode job = store.get(GENERATION_JOB, required(document, "generationJobId"));
        assertThat(text(job, "status")).isEqualTo("FAILED");
        assertThat(text(job, "failureCode")).isEqualTo("OUTPUT_TRUNCATED");
        assertThat(text(job, "providerRequestId")).isEqualTo("req-truncated");
        assertThat(text(job, "finishReason")).isEqualTo("length");
        assertThat(job.path("providerUsage").path("promptTokens").asLong()).isEqualTo(721);
        assertThat(job.path("providerUsage").path("completionTokens").asLong()).isEqualTo(16_384);
        assertThat(text(job, "providerOutputRaw")).hasSizeLessThanOrEqualTo(65_536).endsWith("TAIL");
        assertThat(text(document, "reviewStatus")).isEqualTo("FAILED");
        assertThat(document.path("content").isEmpty()).isTrue();
    }

    @Test
    void staleCoreRejectsShotPlanBeforeProductionModelIsCalled() {
        ObjectNode core = generateCore();
        development.confirm(id(core), obj().put("revision", revision(core)).put("batchSize", 5));
        ObjectNode b1 = latest("OUTLINE_BATCH", 1); worker.tick(); b1 = store.get(STORY_DOCUMENT, id(b1)); development.confirm(id(b1), obj().put("revision", revision(b1)));
        ObjectNode b2 = latest("OUTLINE_BATCH", 2); worker.tick(); b2 = store.get(STORY_DOCUMENT, id(b2)); development.confirm(id(b2), obj().put("revision", revision(b2)));
        ObjectNode script = latest("EPISODE_SCRIPT", 1); worker.tick(); script = store.get(STORY_DOCUMENT, id(script)); development.confirm(id(script), obj().put("revision", revision(script)));
        ObjectNode episode = store.list(EPISODE, projectId, null).getFirst(); ObjectNode scene = store.list(SCENE, projectId, id(episode)).getFirst();
        ObjectNode confirmedCore = store.get(STORY_DOCUMENT, id(core));
        development.edit(id(confirmedCore), obj().put("revision", revision(confirmedCore)).set("content", coreContent("修订后的故事")));
        ObjectNode job = obj().put("projectId", projectId).put("type", "DIRECTOR_PLAN"); ObjectNode input = obj(); input.set("scene", scene); job.set("inputSnapshot", input);
        assertThatThrownBy(() -> development.checkProductionInput(job)).isInstanceOf(WorkflowException.class).hasMessageContaining("剧本");
    }

    private ObjectNode generateCore() { ObjectNode draft = development.start(projectId, obj()); worker.tick(); return store.get(STORY_DOCUMENT, id(draft)); }
    private ObjectNode confirmAndGenerate(ObjectNode core, int size) { ObjectNode c = core; if (!"CONFIRMED".equals(text(c, "reviewStatus"))) c = development.confirm(id(c), obj().put("revision", revision(c)).put("batchSize", size)); ObjectNode b = latest("OUTLINE_BATCH", 1); worker.tick(); return store.get(STORY_DOCUMENT, id(b)); }
    private ObjectNode confirm(ObjectNode doc) { ObjectNode c = development.confirm(id(doc), obj().put("revision", revision(doc))); worker.tick(); return c; }
    private ObjectNode latest(String type, int number) { return store.list(STORY_DOCUMENT, projectId, null).stream().filter(d -> type.equals(text(d, "documentType")) && (type.equals("OUTLINE_BATCH") ? d.path("batchNo").asInt() == number : d.path("episodeNo").asInt() == number)).findFirst().orElseThrow(); }

    private LlmGateway.StructuredResult<JsonNode> resultFor(LlmGateway.StructuredRequest request) throws Exception {
        JsonNode input = mapper.readTree(request.userPrompt());
        String phase = input.path("phase").asText();
        JsonNode value;
        if ("CORE".equals(phase)) value = coreContent("雨夜寻信");
        else if ("OUTLINE_BATCH".equals(phase)) { int start = input.path("startEpisode").asInt(); int end = input.path("endEpisode").asInt(); ObjectNode out = obj(); ArrayNode eps = out.putArray("episodes"); String previous = input.path("sourceSnapshot").path("requiredStartState").asText("start"); for (int i=start;i<=end;i++) { String next = "state-"+i; eps.add(card(i, previous, next)); previous=next; } value=out; }
        else { JsonNode outline = input.path("sourceSnapshot").path("episodeOutline"); ObjectNode out = obj().put("title", outline.path("title").asText()).put("summary", outline.path("summary").asText()).put("script", "这是一个足够长的测试剧本，用于验证分集确认、连续性快照、场景落库以及下一集排队流程。故事继续推进并留下明确悬念。主角在持续的雨声中穿过旧街，重新核对信封日期、人物动机和道具状态，最终决定把线索交给可信的人，并为下一集留下清晰而可追踪的冲突。 ").put("startState", outline.path("startState").asText()).put("endState", outline.path("endState").asText()); out.putArray("characterKeys").add("c1"); out.putArray("locationKeys").add("l1"); out.putArray("propKeys").add("p1"); out.putArray("scenes").add(obj().put("name","雨巷").put("description","主角在雨巷发现信封").put("duration",20)); value=out; }
        return new LlmGateway.StructuredResult<>(value, "fixture-model", "req-"+phase, value.toString(), false);
    }
    private ObjectNode coreContent(String title) { ObjectNode c=obj().put("title",title).put("logline","寻找一封改变命运的信").put("worldRules","现实都市，雨夜光线保持一致").put("seasonArc","主角从追寻线索到面对真相").put("characterArcs","主角学会承担选择").put("foreshadowingRules","信封上的日期反复出现").put("continuityRules","人物、地点、道具状态逐集继承"); ObjectNode character=obj().put("characterKey","c1").put("name","林舟").put("description","谨慎的快递员"); character.set("identityTraits",obj().put("age","30").put("face","清瘦").put("hair","黑短发").put("body","偏瘦").put("voiceDialect","普通话")); character.set("looks",arr(obj().put("lookKey","look1").put("name","雨衣").put("description","深蓝雨衣"))); c.putArray("characters").add(character); ObjectNode location=obj().put("locationKey","l1").put("name","旧街").put("description","狭窄的旧街"); location.set("locationBible",obj().put("layout","南北向街巷").put("spatialAnchors","红色邮筒").put("lighting","冷色路灯")); c.putArray("locations").add(location); ObjectNode prop=obj().put("propKey","p1").put("name","信封").put("description","未拆的信封").put("state","完好"); prop.set("propBible",obj().put("appearance","米白色").put("scale","手掌大小").put("ownership","林舟")); c.putArray("props").add(prop); return c; }
    private ObjectNode card(int no, String start, String end) { ObjectNode card=obj().put("episodeNo",no).put("title","第"+no+"集").put("summary","线索继续").put("startState",start).put("endState",end); card.putArray("characterKeys").add("c1"); card.putArray("locationKeys").add("l1"); card.putArray("propKeys").add("p1"); card.putArray("scenePlan").add(obj().put("name","雨巷").put("description","发现线索").put("duration",20)); return card; }
    private ArrayNode arr(JsonNode n) { return JsonNodeFactory.instance.arrayNode().add(n); }
}

