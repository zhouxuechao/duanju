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
    void premiseGatePersistsCapacityRiskBeforeCoreGeneration() {
        ObjectNode project = store.create(PROJECT, obj().put("name", "长篇前提门禁").put("idea", "主角找到一封信").put("episodeCount", 80).put("targetDuration", 100).put("ratio", "9:16"));
        ObjectNode core = development.start(id(project), obj());
        worker.tick();
        core = store.get(STORY_DOCUMENT, id(core));
        assertThat(core.path("premiseAnalysis").path("viable").asBoolean()).isFalse();
        assertThat(core.path("premiseAnalysis").path("risks")).isNotEmpty();
        assertThat(text(core, "reviewStatus")).isEqualTo("PREMISE_REVIEW_REQUIRED");
        assertThat(store.list(GENERATION_JOB, id(project), null)).hasSize(1);
        assertThat(core.path("premiseValidation").path("passed").asBoolean()).isFalse();
    }

    @Test
    void forceContinueRequiresReasonAndRecordsAuditableOverrideBeforeCore() {
        ObjectNode project = store.create(PROJECT, obj().put("name", "长篇前提门禁").put("idea", "主角找到一封信").put("episodeCount", 80).put("targetDuration", 100).put("ratio", "9:16"));
        ObjectNode core = development.start(id(project), obj()); worker.tick(); core = store.get(STORY_DOCUMENT, id(core));

        ObjectNode blocked = core;
        assertThatThrownBy(() -> development.reviewPremise(id(blocked), obj().put("action", "FORCE_CONTINUE").put("overrideBy", "tester")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("原因");

        ObjectNode continued = development.reviewPremise(id(core), obj().put("action", "FORCE_CONTINUE")
                .put("overrideBy", "tester").put("overrideReason", "已补充多个阶段目标，接受容量风险"));
        assertThat(text(continued, "reviewStatus")).isEqualTo("GENERATING");
        assertThat(text(continued.path("premiseOverride"), "overrideBy")).isEqualTo("tester");
        assertThat(text(continued.path("premiseOverride"), "overrideReason")).contains("容量风险");
        assertThat(text(continued.path("premiseOverride"), "overrideAt")).isNotBlank();
        ObjectNode coreJob = store.get(GENERATION_JOB, required(continued, "generationJobId"));
        assertThat(text(coreJob.path("inputSnapshot"), "phase")).isEqualTo("CORE");
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
        worker.tick();
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
        script1 = store.get(STORY_DOCUMENT, id(script1));
        assertThat(text(script1, "reviewStatus")).as(script1.toString()).isEqualTo("QA_PENDING");
        assertThat(text(script1, "qaJobId")).isNotBlank();
        worker.tick();
        script1 = store.get(STORY_DOCUMENT, id(script1));
        assertThat(text(script1, "reviewStatus")).isEqualTo("REVIEW");
        assertThat(script1.path("storyQa").path("passed").asBoolean()).isTrue();
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
    void everyStoryRequestCarriesResolvedFormatAndTraceableRulePack() {
        generateCore();
        ArgumentCaptor<LlmGateway.StructuredRequest> requests = ArgumentCaptor.forClass(LlmGateway.StructuredRequest.class);
        verify(llm, times(2)).generate(requests.capture(), eq(JsonNode.class));
        String input = requests.getAllValues().stream().map(LlmGateway.StructuredRequest::userPrompt).filter(value -> value.contains("\"phase\":\"CORE\"")).findFirst().orElseThrow();
        assertThat(input).contains("episodeFormat", "GENERAL_MICRO", "rulePack", "fingerprint", "BASE_CORE");
        assertThat(input).contains("premiseAnalysis");
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
    void humanStoryEditPreservesBeforeAfterReasonAndGenerationVersionContext() {
        ObjectNode core = generateCore();
        ObjectNode changed = (ObjectNode) core.path("content").deepCopy();
        changed.put("logline", "人工压缩后的明确故事承诺");
        ObjectNode request = obj().put("revision", revision(core)).put("feedbackNote", "去掉模型化解释");
        request.putArray("reasonCodes").add("TOO_VERBOSE");
        request.set("content", changed);

        ObjectNode saved = development.edit(id(core), request);

        List<ObjectNode> feedback = store.list(HUMAN_EDIT_FEEDBACK, projectId, null);
        assertThat(feedback).hasSize(1);
        ObjectNode record = feedback.getFirst();
        assertThat(text(record, "targetKind")).isEqualTo("CORE");
        assertThat(text(record, "targetId")).isEqualTo(id(saved));
        assertThat(text(record.path("beforeTextOrJson"), "logline")).isNotEqualTo(text(record.path("afterTextOrJson"), "logline"));
        assertThat(record.path("reasonCodes").get(0).asText()).isEqualTo("TOO_VERBOSE");
        assertThat(text(record.path("versionContext"), "model")).isEqualTo("fixture-model");
        assertThat(text(record, "freeformNote")).isEqualTo("去掉模型化解释");
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
        ObjectNode script = latest("EPISODE_SCRIPT", 1); worker.tick(); worker.tick(); script = store.get(STORY_DOCUMENT, id(script)); development.confirm(id(script), obj().put("revision", revision(script)));
        ObjectNode episode = store.list(EPISODE, projectId, null).getFirst(); ObjectNode scene = store.list(SCENE, projectId, id(episode)).getFirst();
        ObjectNode confirmedCore = store.get(STORY_DOCUMENT, id(core));
        development.edit(id(confirmedCore), obj().put("revision", revision(confirmedCore)).set("content", coreContent("修订后的故事")));
        ObjectNode job = obj().put("projectId", projectId).put("type", "DIRECTOR_PLAN"); ObjectNode input = obj(); input.set("scene", scene); job.set("inputSnapshot", input);
        assertThatThrownBy(() -> development.checkProductionInput(job)).isInstanceOf(WorkflowException.class).hasMessageContaining("剧本");
    }

    @Test
    void editingEpisodeScriptInvalidatesOldQaAndRunsQaForTheNewContent() {
        ObjectNode core = generateCore();
        ObjectNode b1 = confirmAndGenerate(core, 5); confirm(b1);
        ObjectNode b2 = latest("OUTLINE_BATCH", 2); confirm(b2);
        ObjectNode script = latest("EPISODE_SCRIPT", 1); worker.tick();
        script = store.get(STORY_DOCUMENT, id(script));
        String firstQaJob = text(script, "qaJobId");
        String firstHash = text(script, "qaContentHash");
        ObjectNode editedContent = (ObjectNode) script.path("content").deepCopy();
        editedContent.put("summary", "修改后的摘要推动了新的角色选择");
        ObjectNode edited = development.edit(id(script), obj().put("revision", revision(script)).set("content", editedContent));
        assertThat(text(edited, "reviewStatus")).isEqualTo("QA_PENDING");
        assertThat(text(edited, "qaJobId")).isNotEqualTo(firstQaJob);
        assertThat(edited.has("storyQa")).isFalse();
        worker.tick();
        ObjectNode checked = store.get(STORY_DOCUMENT, id(edited));
        assertThat(text(checked, "reviewStatus")).isEqualTo("REVIEW");
        assertThat(text(checked, "qaContentHash")).isNotBlank().isNotEqualTo(firstHash);
    }

    @Test
    void qaRewriteCreatesOnlyOneNewEpisodeVersionAndPreservesApprovedBoundaries() {
        ObjectNode core = generateCore();
        ObjectNode b1 = confirmAndGenerate(core, 5); confirm(b1);
        ObjectNode b2 = latest("OUTLINE_BATCH", 2); confirm(b2);
        ObjectNode script = latest("EPISODE_SCRIPT", 1); worker.tick();
        script = store.get(STORY_DOCUMENT, id(script));
        ObjectNode qa = (ObjectNode) script.path("storyQa").deepCopy();
        qa.put("passed", false).put("rewriteRequired", true);
        qa.withArray("blockingIssues").add("本集没有真实剧情增量");
        qa.withArray("rewriteInstructions").add("加入会被下一集继承的角色决定");
        script = store.update(STORY_DOCUMENT, id(script), revision(script), script.deepCopy().set("storyQa", qa));

        ObjectNode rewritten = development.rewrite(id(script), obj().put("revision", revision(script)));
        assertThat(text(rewritten, "reviewStatus")).isEqualTo("GENERATING");
        assertThat(rewritten.path("rewriteAttempt").asInt()).isEqualTo(1);
        assertThat(text(rewritten.path("sourceSnapshot").path("rewriteRequest"), "preserveStartState")).isEqualTo(text(script.path("content"), "startState"));
        assertThat(text(rewritten.path("sourceSnapshot").path("rewriteRequest"), "preserveEndState")).isEqualTo(text(script.path("content"), "endState"));
        assertThat(store.get(STORY_DOCUMENT, id(script)).path("stale").asBoolean()).isTrue();
    }

    private ObjectNode generateCore() { ObjectNode draft = development.start(projectId, obj()); worker.tick(); worker.tick(); return store.get(STORY_DOCUMENT, id(draft)); }
    private ObjectNode confirmAndGenerate(ObjectNode core, int size) { ObjectNode c = core; if (!"CONFIRMED".equals(text(c, "reviewStatus"))) c = development.confirm(id(c), obj().put("revision", revision(c)).put("batchSize", size)); ObjectNode b = latest("OUTLINE_BATCH", 1); worker.tick(); return store.get(STORY_DOCUMENT, id(b)); }
    private ObjectNode confirm(ObjectNode doc) { ObjectNode c = development.confirm(id(doc), obj().put("revision", revision(doc))); worker.tick(); return c; }
    private ObjectNode latest(String type, int number) { return store.list(STORY_DOCUMENT, projectId, null).stream().filter(d -> type.equals(text(d, "documentType")) && (type.equals("OUTLINE_BATCH") ? d.path("batchNo").asInt() == number : d.path("episodeNo").asInt() == number)).findFirst().orElseThrow(); }

    private LlmGateway.StructuredResult<JsonNode> resultFor(LlmGateway.StructuredRequest request) throws Exception {
        JsonNode input = mapper.readTree(request.userPrompt());
        String phase = input.path("phase").asText();
        JsonNode value;
        if ("PREMISE".equals(phase)) value = premise(input.path("project").path("episodeCount").asInt());
        else if ("CORE".equals(phase)) value = coreContent("雨夜寻信");
        else if ("OUTLINE_BATCH".equals(phase)) { int start = input.path("startEpisode").asInt(); int end = input.path("endEpisode").asInt(); ObjectNode out = obj(); ArrayNode eps = out.putArray("episodes"); String previous = input.path("sourceSnapshot").path("requiredStartState").asText("start"); for (int i=start;i<=end;i++) { String next = "state-"+i; eps.add(card(i, previous, next)); previous=next; } value=out; }
        else if ("STORY_QA".equals(phase)) value = qa();
        else { JsonNode outline = input.path("sourceSnapshot").path("episodeOutline"); ObjectNode out = obj().put("title", outline.path("title").asText()).put("summary", outline.path("summary").asText()).put("script", "这是一个足够长的测试剧本，用于验证分集确认、连续性快照、场景落库以及下一集排队流程。故事继续推进并留下明确悬念。主角在持续的雨声中穿过旧街，重新核对信封日期、人物动机和道具状态，最终决定把线索交给可信的人，并为下一集留下清晰而可追踪的冲突。 ").put("startState", outline.path("startState").asText()).put("endState", outline.path("endState").asText()).put("episodeFormatId","GENERAL_MICRO").put("beatMode","SINGLE_ROUND").put("targetDurationSec",20); out.putArray("characterKeys").add("c1"); out.putArray("locationKeys").add("l1"); out.putArray("propKeys").add("p1"); out.putArray("beatBoundaries").add(beat("HOOK",0,3)).add(beat("PAYOFF",3,20)); out.putArray("scenes").add(scene("雨巷","主角在雨巷发现信封",20)); value=out; }
        return new LlmGateway.StructuredResult<>(value, "fixture-model", "req-"+phase, value.toString(), false);
    }
    private ObjectNode coreContent(String title) {
        ObjectNode c=obj().put("title",title).put("logline","寻找一封改变命运的信");
        c.set("storyProfile",obj().put("settingGenre","OTHER").put("storyType","GROWTH").put("audience","GENERAL").put("intensity","MEDIUM").put("sourceMode","ORIGINAL_IDEA"));c.withObject("storyProfile").putArray("tropes");c.withObject("storyProfile").putArray("tones");
        c.set("emotionContract",obj().put("corePromise","每次选择都使真相更近也让代价变大").put("primaryEmotion","期待").put("secondaryEmotion","紧张").put("audienceExpectation","看到线索改变人物选择").put("payoffPattern","线索、选择、后果逐级兑现"));c.withObject("emotionContract").putArray("forbiddenPatterns").add("重复发现同一种线索");
        c.set("storyEngine",obj().put("coreConflict","林舟追查信件而有人阻止真相公开").put("protagonistGoal","找到收信人并查清失踪原因").put("oppositionGoal","销毁信件并掩盖真相").put("stakes","朋友安全与林舟信誉").put("mainPayoff","林舟用选择揭开真相").put("reversalStrategy","每次证据改变对嫌疑人的判断"));c.withObject("storyEngine").putArray("escalationAxes").add("知识").add("风险");
        c.putArray("worldRules").add("现实都市，事件遵守同一时间线");c.set("seasonArc",obj().put("opening","雨夜收到错投信").put("development","沿线索追查").put("majorTurn","可信的人被证据指向").put("climax","必须公开信件承担代价").put("ending","真相公开，人物完成选择"));
        ObjectNode unit=obj().put("unitId","UNIT_01").put("startEpisode",1).put("endEpisode",10).put("title","雨夜寻信").put("goal","找到收信人").put("mainConflict","追查与阻挠").put("antagonistPressure","线索持续被销毁").put("emotionGoal","从期待推进到紧张兑现").put("reveal","信件来自失踪者").put("payoff","找到关键收件记录").put("climax","林舟决定公开信件").put("endHook","真正收信人出现");unit.set("unitTransformation",obj().put("protagonist","从回避风险到主动承担").put("relationships","从独查到信任伙伴").put("mainConflict","从找人升级为保护真相").put("audienceKnowledge","确认信件关联失踪案").put("nextStageReason","真相仍有幕后者"));c.putArray("unitArcs").add(unit);
        ObjectNode character=obj().put("characterKey","c1").put("name","林舟").put("description","谨慎的快递员");ObjectNode nb=obj().put("storyRole","主角").put("want","送达失踪者的信").put("need","学会承担选择").put("fear","连累朋友").put("weakness","过度谨慎").put("secret","曾见过寄信人").put("motivation","弥补一次错投").put("decisionPattern","先核对证据再行动").put("speechStyle","短句，回避夸张判断");nb.set("arc",obj().put("start","独自追查").put("end","主动信任伙伴"));nb.withObject("arc").putArray("turningPoints").add("伙伴因他受伤");nb.putArray("behaviorRules").add("不凭猜测指控");nb.putArray("relationships").add("与伙伴从防备到互信");character.set("narrativeBible",nb);character.set("identityTraits",obj().put("age","30").put("face","清瘦").put("hair","黑短发").put("body","偏瘦").put("voiceDialect","普通话"));character.set("looks",arr(obj().put("lookKey","look1").put("name","雨衣").put("description","深蓝雨衣")));c.putArray("characters").add(character);
        ObjectNode location=obj().put("locationKey","l1").put("name","旧街").put("description","狭窄的旧街");location.set("locationBible",obj().put("layout","南北向街巷").put("spatialAnchors","红色邮筒").put("lighting","冷色路灯"));c.putArray("locations").add(location);ObjectNode prop=obj().put("propKey","p1").put("name","信封").put("description","未拆的信封").put("state","完好");prop.set("propBible",obj().put("appearance","米白色").put("scale","手掌大小").put("ownership","林舟"));c.putArray("props").add(prop);c.putArray("foreshadowingRules").add("信封日期先出现后解释");c.putArray("continuityRules").add("人物、地点、道具状态逐集继承");return c;
    }
    private ObjectNode card(int no, String start, String end) { ObjectNode card=obj().put("episodeNo",no).put("title","第"+no+"集").put("episodeFunction","推进信件来源").put("episodeGoal","确认一条可行动线索").put("hook","信封日期与失踪日冲突").put("mainConflict","林舟要查记录但记录正在被删除").put("newInformation","收件记录指向旧街").put("characterDecision","林舟决定保存副本").put("escalation","阻挠者开始跟踪林舟").put("payoff","林舟拿到一页记录").put("cliffhanger","记录上出现熟人名字").put("summary","线索继续并改变嫌疑方向").put("startState",start).put("endState",end).put("episodeFormatId","GENERAL_MICRO").put("beatMode","SINGLE_ROUND").put("estimatedDurationSec",20);card.putArray("characterKeys").add("c1");card.putArray("locationKeys").add("l1");card.putArray("propKeys").add("p1");card.set("foreshadowing",obj());card.withObject("foreshadowing").putArray("plant");card.withObject("foreshadowing").putArray("advance").add("信封日期");card.withObject("foreshadowing").putArray("resolve");card.putArray("beats").add(beat("HOOK",0,3)).add(beat("DECISION",3,20));card.putArray("progressionEvents").add(obj().put("atSec",10).put("type","NEW_INFORMATION").put("description","得到收件记录"));card.putArray("scenePlan").add(scene("雨巷","发现线索",20));return card; }
    private ObjectNode beat(String id,double start,double end){return obj().put("beatId",id).put("purpose",id.equals("HOOK")?"建立观看问题":"推动选择并兑现").put("startSec",start).put("endSec",end);}
    private ObjectNode scene(String name,String description,double duration){return obj().put("name",name).put("description",description).put("startSec",0).put("endSec",duration).put("duration",duration);}
    private ObjectNode premise(int episodeCount){ObjectNode p=obj().put("viable",episodeCount<=30).put("coreConflict","林舟追查信件而阻挠者试图销毁证据").put("protagonistGoal","找到收信人并查清失踪原因").put("opposition","掌握记录且有自身目标的阻挠者").put("audiencePromise","每条线索都改变人物选择和嫌疑方向").put("whyNotResolveImmediately","证据分散且对手会根据调查行动调整策略");p.set("expansionPotential",obj().put("conflictDepth","阻挠逐级升级").put("characterDepth","主角需要克服过度谨慎").put("relationshipDepth","伙伴关系因风险变化").put("reversalPotential","旧线索会获得新解释").put("informationDepth","角色拥有不同知识边界"));p.putArray("risks");if(episodeCount>30)p.withArray("risks").add("目标集数超过原始冲突容量，需要多个不同阶段目标");p.putArray("questionsForCore").add("每个 Unit 改变什么状态？");return p;}
    private ObjectNode qa(){ObjectNode qa=obj().put("passed",true).put("watchReason","线索迫使主角作出有代价的选择").put("nextEpisodeReason","熟人名字改变嫌疑方向").put("rewriteRequired",false);ObjectNode dimensions=qa.putObject("dimensions");for(String key:List.of("hook","progression","conflict","characterConsistency","genreFit","continuity","payoff","cliffhanger","narrativeNecessity","dialogueNaturalness"))dimensions.put(key,85);ObjectNode delta=qa.putObject("episodeDelta");for(String key:List.of("factsChanged","relationshipsChanged","goalsChanged","knowledgeChanged","riskChanged","resourcesChanged"))delta.putArray(key);delta.withArray("knowledgeChanged").add("林舟确认收件记录指向旧街");qa.putArray("blockingIssues");qa.putArray("issues");qa.putArray("rewriteInstructions");return qa;}
    private ArrayNode arr(JsonNode n) { return JsonNodeFactory.instance.arrayNode().add(n); }
}

