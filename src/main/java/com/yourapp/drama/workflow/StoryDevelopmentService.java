package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.job.JobService;
import com.yourapp.drama.model.LlmGateway;
import com.yourapp.drama.model.ProviderException;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.persistence.ResourceKind;
import com.yourapp.drama.persistence.RevisionConflictException;
import com.yourapp.drama.provider.StructuredJson;
import jakarta.validation.Validator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class StoryDevelopmentService {
   private final DocumentStore store;
   private final JobService jobs;
   private final LlmGateway llm;
   private final ObjectMapper mapper;
   private final StructuredJson structured;

   public StoryDevelopmentService(DocumentStore store, JobService jobs, LlmGateway llm, ObjectMapper mapper, Validator validator) {
      this.store = store;
      this.jobs = jobs;
      this.llm = llm;
      this.mapper = mapper;
      this.structured = new StructuredJson(mapper, validator);
   }

   public ObjectNode start(String projectId, ObjectNode request) {
      return (ObjectNode)this.store.transaction(() -> {
         ObjectNode project = this.store.getForUpdate(ResourceKind.PROJECT, projectId);
         if (project.hasNonNull("activeStoryDocumentId")) {
            return this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(project, "activeStoryDocumentId"));
         } else if (project.path("episodeCount").isIntegralNumber() && project.path("episodeCount").asInt() >= 1 && project.path("episodeCount").asInt() <= 100) {
            if (project.path("targetDuration").isNumber() && !(project.path("targetDuration").asDouble() <= (double)0.0F)) {
               for(ObjectNode j : this.store.list(ResourceKind.GENERATION_JOB, projectId, (String)null)) {
                  if (Set.of("STORY", "SCRIPT").contains(Documents.text(j, "type")) && this.activeJob(j)) {
                     throw new WorkflowException("GENERATION_ACTIVE", "本项目已有创作任务执行中，请先等待当前任务完成");
                  }
               }

               String coreId = UUID.randomUUID().toString();
               ObjectNode draft = Documents.obj().put("id", coreId).put("projectId", projectId).put("coreId", coreId).put("documentType", "CORE").put("version", 1).put("reviewStatus", "WAITING");
               draft.set("projectSnapshot", project.deepCopy());
               draft.set("content", Documents.obj());
               ObjectNode saved = this.store.create(ResourceKind.STORY_DOCUMENT, draft);
               this.store.update(ResourceKind.PROJECT, projectId, Documents.revision(project), project.deepCopy().put("activeStoryDocumentId", coreId));
               return this.schedule(saved);
            } else {
               throw new IllegalArgumentException("请先设置每集目标时长");
            }
         } else {
            throw new IllegalArgumentException("episodeCount 集数必须为 1 到 100 的整数，请先完善作品设定");
         }
      });
   }

   public ObjectNode edit(String documentId, ObjectNode request) {
      return (ObjectNode)this.store.transaction(() -> {
         ObjectNode before = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
         this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(before));
         ObjectNode doc = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
         this.requireCurrent(doc);
         this.expectRevision(doc, request);
         if ("GENERATING".equals(Documents.text(doc, "reviewStatus"))) {
            throw new WorkflowException("GENERATION_ACTIVE", "此文档正在生成，完成后再修改");
         } else {
            JsonNode content = request.path("content");
            if (!content.isObject()) {
               throw new IllegalArgumentException("修改内容必须为完整文档");
            } else {
               ObjectNode next = doc.deepCopy();
               next.set("content", content.deepCopy());
               next.set("validation", this.validate(doc, content));
               if ("CONFIRMED".equals(Documents.text(doc, "reviewStatus"))) {
                  String nextId = UUID.randomUUID().toString();
                  next.remove(List.of("id", "parentId", "revision", "createdAt", "updatedAt", "generationJobId", "confirmedAt", "continuitySnapshot", "continuityHash", "failureReason", "failureCode"));
                  next.put("id", nextId).put("supersedesId", documentId).put("version", doc.path("version").asInt() + 1).put("reviewStatus", "REVIEW").put("stale", false);
                  if ("CORE".equals(Documents.text(doc, "documentType"))) {
                     next.put("coreId", nextId);
                  } else {
                     next.put("continuityHash", Documents.text(doc, "continuityHash"));
                  }

                  this.invalidate(doc);
                  ObjectNode saved = this.store.create(ResourceKind.STORY_DOCUMENT, next);
                  if ("CORE".equals(Documents.text(doc, "documentType"))) {
                     ObjectNode p = this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(doc));
                     this.store.update(ResourceKind.PROJECT, Documents.id(p), Documents.revision(p), p.deepCopy().put("activeStoryDocumentId", nextId));
                  }

                  return saved;
               } else {
                  next.put("reviewStatus", "REVIEW");
                  next.remove(List.of("failureReason", "failureCode"));
                  return this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(doc), next);
               }
            }
         }
      });
   }

   public ObjectNode confirm(String documentId, ObjectNode request) {
      return (ObjectNode)this.store.transaction(() -> {
         ObjectNode before = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
         this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(before));
         ObjectNode doc = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
         this.requireCurrent(doc);
         if ("CONFIRMED".equals(Documents.text(doc, "reviewStatus"))) {
            return doc;
         } else {
            this.expectRevision(doc, request);
            if (!"REVIEW".equals(Documents.text(doc, "reviewStatus"))) {
               throw new WorkflowException("DRAFT_NOT_READY", "文档尚未生成或修改完成，请先处理本阶段问题");
            } else {
               ObjectNode next = doc.deepCopy();
               next.set("validation", this.validate(doc, doc.path("content")));
               next.put("reviewStatus", "CONFIRMED").put("confirmedAt", Instant.now().toString());
               if ("CORE".equals(Documents.text(doc, "documentType"))) {
                  int size = request.path("batchSize").asInt(doc.path("projectSnapshot").path("episodeCount").asInt() <= 20 ? 5 : 10);
                  if (size != 5 && size != 10) {
                     throw new IllegalArgumentException("每批集纲请选择 5 集或 10 集");
                  }

                  next.put("batchSize", size);
                  ObjectNode snapshot = Documents.obj().put("coreId", Documents.id(doc)).put("version", doc.path("version").asInt());
                  snapshot.set("core", doc.path("content").deepCopy());
                  snapshot.set("assetBindings", this.createAssets(doc));
                  next.set("continuitySnapshot", snapshot);
                  next.put("continuityHash", this.hash(snapshot));
                  ObjectNode p = this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(doc));
                  this.store.update(ResourceKind.PROJECT, Documents.id(p), Documents.revision(p), p.deepCopy().put("status", "STORY_BIBLE"));
               } else {
                  ObjectNode core = this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(doc, "coreId"));
                  next.put("continuityHash", Documents.text(core, "continuityHash"));
                  next.set("continuitySnapshot", doc.path("sourceSnapshot").deepCopy());
               }

               ObjectNode saved = this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(doc), next);
               if ("EPISODE_SCRIPT".equals(Documents.text(saved, "documentType"))) {
                  this.materializeEpisode(saved);
               }

               this.advance(saved);
               return saved;
            }
         }
      });
   }

   public ObjectNode retry(String documentId) {
      return (ObjectNode)this.store.transaction(() -> {
         ObjectNode before = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
         this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(before));
         ObjectNode doc = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
         this.requireCurrent(doc);
         ObjectNode old = this.store.get(ResourceKind.GENERATION_JOB, Documents.required(doc, "generationJobId"));
         if (this.activeJob(old)) {
            return doc;
         } else if (!"FAILED".equals(Documents.text(old, "status"))) {
            throw new WorkflowException("NOT_RETRYABLE", "只有失败的生成任务可以局部重试");
         } else if (old.path("submissionUncertain").asBoolean()) {
            throw new WorkflowException("SUBMISSION_UNCERTAIN", "这次提交结果不确定，请核对服务商记录；不能自动重发");
         } else if ("CONFIRMED".equals(Documents.text(doc, "reviewStatus"))) {
            throw new WorkflowException("ALREADY_CONFIRMED", "已确认的内容请通过修改创建新版本");
         } else {
            return this.schedule(doc);
         }
      });
   }

   public void process(ObjectNode job) {
      String documentId = Documents.required(job.path("inputSnapshot"), "documentId");
      ObjectNode doc = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
      if (this.current(doc) && Documents.id(job).equals(Documents.text(doc, "generationJobId")) && !job.path("cancelRequested").asBoolean()) {
         JsonNode input = job.path("inputSnapshot");
         ObjectNode schema = StoryDevelopmentSchemas.forDocument(doc);
         LlmGateway.StructuredRequest request = new LlmGateway.StructuredRequest(this.prompt(Documents.text(doc, "documentType")), input.toString(), this.mapper.convertValue(schema, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}), Map.of());
         LlmGateway.StructuredResult<JsonNode> result = this.llm.generate(request, JsonNode.class);
         this.jobs.mutate(Documents.id(job), (j) -> j.put("providerRequestId", result.requestId()).put("model", result.model()).put("simulated", result.simulated()));

         ObjectNode validation;
         try {
            validation = this.validate(doc, (JsonNode)result.value());
         } catch (RuntimeException error) {
            this.store.transaction(() -> {
               ObjectNode latest = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
               ObjectNode invalid = latest.deepCopy().put("reviewStatus", "FAILED");
               invalid.set("content", (JsonNode)result.value());
               invalid.set("validation", Documents.obj().put("passed", false).put("message", error.getMessage()));
               this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(latest), invalid);
               return null;
            });
            throw error;
         }

         this.store.transaction(() -> {
            ObjectNode latest = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
            ObjectNode next = latest.deepCopy().put("reviewStatus", this.current(latest) ? "REVIEW" : "STALE").put("providerRequestId", result.requestId());
            next.set("content", ((JsonNode)result.value()).deepCopy());
            next.set("validation", validation);
            this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(latest), next);
            this.jobs.succeed(Documents.id(job), Documents.obj().put("documentId", documentId).put("stage", Documents.text(doc, "documentType")).put("awaitingReview", this.current(latest)).put("simulated", result.simulated()));
            return null;
         });
      } else {
         this.jobs.mutate(Documents.id(job), (j) -> j.put("status", "CANCELLED"));
      }
   }

   public void syncFailure(ObjectNode job) {
      if (Set.of("STORY", "SCRIPT").contains(Documents.text(job, "type"))) {
         String documentId = Documents.text(job.path("inputSnapshot"), "documentId");
         if (!documentId.isBlank()) {
            this.store.transaction(() -> {
               ObjectNode doc = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
               if (Documents.id(job).equals(Documents.text(doc, "generationJobId")) && !"CONFIRMED".equals(Documents.text(doc, "reviewStatus"))) {
                  ObjectNode next = doc.deepCopy().put("reviewStatus", this.current(doc) ? "FAILED" : "STALE").put("failureReason", Documents.text(job, "failureReason")).put("failureCode", Documents.text(job, "failureCode"));
                  next.set("validation", Documents.obj().put("passed", false).put("message", Documents.text(job, "failureReason")));
                  this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(doc), next);
                  return null;
               } else {
                  return null;
               }
            });
         }
      }
   }

   private ObjectNode schedule(ObjectNode doc) {
      ObjectNode input = Documents.obj().put("pipelineVersion", 2).put("documentId", Documents.id(doc)).put("phase", Documents.text(doc, "documentType"));
      input.set("project", doc.path("projectSnapshot").deepCopy());
      if (!"CORE".equals(Documents.text(doc, "documentType"))) {
         ObjectNode core = this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(doc, "coreId"));
         this.requireConfirmed(core);
         input.put("coreId", Documents.id(core)).put("continuityHash", Documents.text(core, "continuityHash"));
         input.set("continuitySnapshot", core.path("continuitySnapshot").deepCopy());
         input.set("sourceSnapshot", doc.path("sourceSnapshot").deepCopy());

         for(String field : List.of("batchNo", "startEpisode", "endEpisode", "episodeNo")) {
            if (doc.has(field)) {
               input.set(field, doc.get(field));
            }
         }
      }

      JobService var10000 = this.jobs;
      String var10001 = Documents.project(doc);
      String var10003 = "EPISODE_SCRIPT".equals(Documents.text(doc, "documentType")) ? "SCRIPT" : "STORY";
      String var10005 = Documents.id(doc);
      ObjectNode submitted = var10000.enqueue(var10001, (String)null, var10003, input, "development:" + var10005 + ":" + String.valueOf(UUID.randomUUID()));
      this.jobs.mutate(Documents.id(submitted), (j) -> j.put("maxAttempts", 1));
      ObjectNode next = doc.deepCopy().put("generationJobId", Documents.id(submitted)).put("reviewStatus", "GENERATING");
      next.remove(List.of("failureReason", "failureCode"));
      return this.store.update(ResourceKind.STORY_DOCUMENT, Documents.id(doc), Documents.revision(doc), next);
   }

   private void advance(ObjectNode confirmed) {
      ObjectNode core = this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(confirmed, "coreId"));
      int count = core.path("projectSnapshot").path("episodeCount").asInt(1);
      int size = core.path("batchSize").asInt(5);
      switch (Documents.text(confirmed, "documentType")) {
         case "CORE":
            this.createBatch(core, 1, 1, Math.min(size, count), (ObjectNode)null);
            break;
         case "OUTLINE_BATCH":
            int last = confirmed.path("endEpisode").asInt();
            if (last < count) {
               this.createBatch(core, confirmed.path("batchNo").asInt() + 1, last + 1, Math.min(count, last + size), confirmed);
            } else {
               this.createScript(core, 1, (ObjectNode)null);
            }
            break;
         case "EPISODE_SCRIPT":
            int no = confirmed.path("episodeNo").asInt();
            if (no < count) {
               this.createScript(core, no + 1, confirmed);
            }
            break;
         default:
            throw new IllegalArgumentException("未知阶段");
      }

   }

   private void createBatch(ObjectNode core, int no, int start, int end, ObjectNode previous) {
      if (!this.findSlot(core, "OUTLINE_BATCH", "batchNo", no).isPresent()) {
         ObjectNode doc = this.draft(core, "OUTLINE_BATCH").put("batchNo", no).put("startEpisode", start).put("endEpisode", end).put("version", this.nextVersion(core, "OUTLINE_BATCH", "batchNo", no));
         ObjectNode source = Documents.obj().put("coreId", Documents.id(core)).put("coreHash", Documents.text(core, "continuityHash"));
         if (previous != null) {
            this.requireConfirmed(previous);
            source.put("previousBatchId", Documents.id(previous));
            source.put("previousBatchHash", this.hash(previous.path("content")));
            JsonNode cards = previous.path("content").path("episodes");
            source.set("previousApprovedCards", cards.deepCopy());
            source.put("requiredStartState", cards.get(cards.size() - 1).path("endState").asText());
         }

         doc.set("sourceSnapshot", source);
         this.schedule(this.store.create(ResourceKind.STORY_DOCUMENT, doc));
      }
   }

   private void createScript(ObjectNode core, int no, ObjectNode previous) {
      if (!this.findSlot(core, "EPISODE_SCRIPT", "episodeNo", no).isPresent()) {
         List<ObjectNode> batches = this.docs(Documents.project(core)).stream().filter((d) -> Documents.id(core).equals(Documents.text(d, "coreId")) && "OUTLINE_BATCH".equals(Documents.text(d, "documentType")) && !d.path("stale").asBoolean()).sorted(Comparator.comparingInt((d) -> d.path("batchNo").asInt())).toList();
         int expected = 1;
         ObjectNode owner = null;
         JsonNode outline = null;

         for(ObjectNode batch : batches) {
            this.requireConfirmed(batch);
            if (batch.path("startEpisode").asInt() != expected) {
               throw new WorkflowException("OUTLINES_INCOMPLETE", "分批集纲尚未全部确认");
            }

            expected = batch.path("endEpisode").asInt() + 1;

            for(JsonNode card : batch.path("content").path("episodes")) {
               if (card.path("episodeNo").asInt() == no) {
                  owner = batch;
                  outline = card;
               }
            }
         }

         if (expected == core.path("projectSnapshot").path("episodeCount").asInt() + 1 && owner != null) {
            ObjectNode doc = this.draft(core, "EPISODE_SCRIPT").put("episodeNo", no).put("sourceBatchId", Documents.id(owner)).put("version", this.nextVersion(core, "EPISODE_SCRIPT", "episodeNo", no));
            ObjectNode source = Documents.obj().put("coreId", Documents.id(core)).put("coreHash", Documents.text(core, "continuityHash")).put("batchId", Documents.id(owner)).put("batchHash", this.hash(owner.path("content")));
            source.set("episodeOutline", outline.deepCopy());
            if (previous != null) {
               this.requireConfirmed(previous);
               source.put("previousScriptId", Documents.id(previous));
               source.put("previousScriptHash", this.hash(previous.path("content")));
               source.set("previousEpisodeHandoff", Documents.obj().put("summary", Documents.text(previous.path("content"), "summary")).put("endState", Documents.text(previous.path("content"), "endState")));
            }

            doc.set("sourceSnapshot", source);
            this.schedule(this.store.create(ResourceKind.STORY_DOCUMENT, doc));
         } else {
            throw new WorkflowException("OUTLINES_INCOMPLETE", "请先完成并确认全部集纲");
         }
      }
   }

   private ObjectNode draft(ObjectNode core, String type) {
      ObjectNode d = Documents.obj().put("projectId", Documents.project(core)).put("coreId", Documents.id(core)).put("documentType", type).put("version", 1).put("reviewStatus", "WAITING").put("continuityHash", Documents.text(core, "continuityHash"));
      d.set("content", Documents.obj());
      d.set("projectSnapshot", core.path("projectSnapshot").deepCopy());
      return d;
   }

   private Optional<ObjectNode> findSlot(ObjectNode core, String type, String field, int no) {
      return this.docs(Documents.project(core)).stream().filter((d) -> Documents.id(core).equals(Documents.text(d, "coreId")) && type.equals(Documents.text(d, "documentType")) && d.path(field).asInt() == no && !d.path("stale").asBoolean()).findFirst();
   }

   private int nextVersion(ObjectNode core, String type, String field, int no) {
      return this.docs(Documents.project(core)).stream().filter((d) -> Documents.id(core).equals(Documents.text(d, "coreId")) && type.equals(Documents.text(d, "documentType")) && d.path(field).asInt() == no).mapToInt((d) -> d.path("version").asInt()).max().orElse(0) + 1;
   }

   private List<ObjectNode> docs(String projectId) {
      return this.store.list(ResourceKind.STORY_DOCUMENT, projectId, (String)null);
   }

   private ObjectNode validate(ObjectNode doc, JsonNode content) {
      try {
         this.structured.parse(content.toString(), StoryDevelopmentSchemas.forDocument(doc), JsonNode.class, (String)null);
      } catch (ProviderException error) {
         throw new IllegalArgumentException(error.getMessage());
      }

      if ("CORE".equals(Documents.text(doc, "documentType"))) {
         this.unique(content.path("characters"), "characterKey");
         this.unique(content.path("locations"), "locationKey");
         this.unique(content.path("props"), "propKey");

         for(JsonNode p : content.path("characters")) {
            this.unique(p.path("looks"), "lookKey");
         }
      } else {
         ObjectNode core = this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(doc, "coreId"));
         this.requireConfirmed(core);
         JsonNode canon = core.path("content");
         if (!Documents.text(core, "continuityHash").equals(Documents.text(doc, "continuityHash"))) {
            throw new WorkflowException("STALE_SNAPSHOT", "本阶段引用的故事版本已过期");
         }

         if ("OUTLINE_BATCH".equals(Documents.text(doc, "documentType"))) {
            int no = doc.path("startEpisode").asInt();
            String preceding = doc.path("sourceSnapshot").path("requiredStartState").asText("");

            for(JsonNode ep : content.path("episodes")) {
               if (ep.path("episodeNo").asInt() != no++) {
                  throw new IllegalArgumentException("集纲集号必须连续且与本批范围完全一致");
               }

               this.checkRefs(ep, canon);
               this.duration(ep.path("scenePlan"), doc);
               if (!preceding.isBlank() && !preceding.equals(Documents.text(ep, "startState"))) {
                  throw new IllegalArgumentException("第 " + ep.path("episodeNo").asInt() + " 集开场状态未承接上一集结尾，请修改开场或显式过渡描述");
               }

               preceding = Documents.text(ep, "endState");
            }
         } else {
            this.checkRefs(content, canon);
            this.duration(content.path("scenes"), doc);
            JsonNode outline = doc.path("sourceSnapshot").path("episodeOutline");

            for(String k : List.of("startState", "endState")) {
               if (!Documents.text(content, k).equals(Documents.text(outline, k))) {
                  throw new IllegalArgumentException("剧本的 " + k + " 必须保持已确认集纲的交接状态；改变剧情请先修订集纲");
               }
            }
         }
      }

      return Documents.obj().put("passed", true).put("checkedAt", Instant.now().toString()).put("message", "结构、集号、时长、资产引用与交接状态已校验；叙事与视觉细节仍需人工审查");
   }

   private void duration(JsonNode scenes, JsonNode doc) {
      double total = (double)0.0F;

      for(JsonNode s : scenes) {
         total += s.path("duration").asDouble();
      }

      double target = doc.path("projectSnapshot").path("targetDuration").asDouble((double)20.0F);
      if (Math.abs(total - target) > 0.01) {
         throw new IllegalArgumentException("场景时长合计 " + total + " 秒，必须与本集目标 " + target + " 秒一致");
      }
   }

   private void unique(JsonNode list, String key) {
      Set<String> seen = new HashSet();

      for(JsonNode n : list) {
         if (!seen.add(Documents.required(n, key))) {
            throw new IllegalArgumentException(key + " 重复，请为不同资产使用不同标识");
         }
      }

   }

   private void checkRefs(JsonNode node, JsonNode core) {
      for(String[] spec : List.of(new String[]{"characterKeys", "characters", "characterKey"}, new String[]{"locationKeys", "locations", "locationKey"}, new String[]{"propKeys", "props", "propKey"})) {
         Set<String> allowed = new HashSet();

         for(JsonNode x : core.path(spec[1])) {
            allowed.add(Documents.text(x, spec[2]));
         }

         for(JsonNode key : node.path(spec[0])) {
            if (!allowed.contains(key.asText())) {
               throw new IllegalArgumentException("未在故事圣经中定义的资产：" + spec[0] + " / " + key.asText());
            }
         }
      }

   }

   private void expectRevision(ObjectNode doc, ObjectNode request) {
      if (request.path("revision").asLong(-1L) != Documents.revision(doc)) {
         throw new RevisionConflictException(ResourceKind.STORY_DOCUMENT, Documents.id(doc));
      }
   }

   private boolean activeJob(JsonNode job) {
      return Set.of("QUEUED", "RUNNING", "RETRY_WAIT").contains(Documents.text(job, "status"));
   }

   private boolean current(JsonNode doc) {
      return !doc.path("stale").asBoolean() && Documents.text(this.store.get(ResourceKind.PROJECT, Documents.project(doc)), "activeStoryDocumentId").equals(Documents.text(doc, "coreId"));
   }

   private void requireCurrent(ObjectNode doc) {
      if (!this.current(doc)) {
         throw new WorkflowException("STALE_DOCUMENT", "这是旧版本内容，请打开当前版本继续审查");
      }
   }

   private void requireConfirmed(ObjectNode doc) {
      this.requireCurrent(doc);
      if (!"CONFIRMED".equals(Documents.text(doc, "reviewStatus"))) {
         throw new WorkflowException("UPSTREAM_REVIEW_REQUIRED", "请先确认上游内容");
      }
   }

   private String prompt(String type) {
      String var10000;
      switch (type) {
         case "CORE" -> var10000 = "01-story-planning";
         case "OUTLINE_BATCH" -> var10000 = "03-episode-planning";
         default -> var10000 = "04-script-writing";
      }

      String dir = var10000;

      try (InputStream stream = (new ClassPathResource("development-skills/" + dir + "/prompt.md")).getInputStream()) {
         return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (Exception e) {
         throw new IllegalStateException("创作技能加载失败：" + dir, e);
      }
   }

   private String hash(JsonNode value) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this.canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   private JsonNode canonical(JsonNode value) {
      if (!value.isObject()) {
         if (value.isArray()) {
            ArrayNode n = JsonNodeFactory.instance.arrayNode();
            value.forEach((v) -> n.add(this.canonical(v)));
            return n;
         } else {
            return value;
         }
      } else {
         ObjectNode n = Documents.obj();
          List<String> names = new ArrayList<>();
          value.fieldNames().forEachRemaining(names::add);
         Collections.sort(names);

         for(String name : names) {
            n.set(name, this.canonical(value.get(name)));
         }

         return n;
      }
   }

   private ObjectNode createAssets(ObjectNode core) {
      ObjectNode bindings = Documents.obj();
      ObjectNode chars = bindings.putObject("characters");
      ObjectNode places = bindings.putObject("locations");
      ObjectNode props = bindings.putObject("props");

      for(JsonNode p : core.path("content").path("characters")) {
         ObjectNode actor = (ObjectNode)p.deepCopy();
         actor.put("projectId", Documents.project(core)).put("storyBibleId", Documents.id(core)).put("provider", "SEEDREAM").put("sourceType", "IMAGE_REFERENCE").put("providerStatus", "UNBOUND");
         actor.putArray("requiredViews").add("FRONT").add("LEFT").add("RIGHT").add("BACK");
         ObjectNode saved = this.store.create(ResourceKind.CHARACTER, actor);
         chars.put(Documents.text(p, "characterKey"), Documents.id(saved));
         String firstLook = null;

         for(JsonNode l : p.path("looks")) {
            ObjectNode look = (ObjectNode)l.deepCopy();
            look.put("projectId", Documents.project(core)).put("characterId", Documents.id(saved)).put("storyBibleId", Documents.id(core));
            ObjectNode created = this.store.create(ResourceKind.CHARACTER_LOOK, look);
            if (firstLook == null) {
               firstLook = Documents.id(created);
            }
         }

         this.store.update(ResourceKind.CHARACTER, Documents.id(saved), Documents.revision(saved), saved.deepCopy().put("baseLookId", firstLook));
      }

      for(JsonNode p : core.path("content").path("locations")) {
         ObjectNode value = (ObjectNode)p.deepCopy();
         value.put("projectId", Documents.project(core)).put("storyBibleId", Documents.id(core));
         value.putArray("requiredViews").add("LAYOUT").add("FRONT").add("REVERSE").add("SIDE");
         ObjectNode saved = this.store.create(ResourceKind.LOCATION, value);
         places.put(Documents.text(p, "locationKey"), Documents.id(saved));
      }

      for(JsonNode p : core.path("content").path("props")) {
         ObjectNode value = (ObjectNode)p.deepCopy();
         value.put("projectId", Documents.project(core)).put("storyBibleId", Documents.id(core));
         value.putArray("requiredViews").add("FRONT").add("SIDE").add("BACK").add("SCALE");
         ObjectNode saved = this.store.create(ResourceKind.PROP, value);
         props.put(Documents.text(p, "propKey"), Documents.id(saved));
      }

      return bindings;
   }

   private void materializeEpisode(ObjectNode doc) {
      ObjectNode ep = Documents.obj().put("projectId", Documents.project(doc)).put("storyBibleId", Documents.text(doc, "coreId")).put("storyDocumentId", Documents.id(doc)).put("episodeNo", doc.path("episodeNo").asInt()).put("name", Documents.text(doc.path("content"), "title")).put("summary", Documents.text(doc.path("content"), "summary")).put("script", Documents.text(doc.path("content"), "script")).put("scriptReviewStatus", "CONFIRMED").put("locked", true).put("continuityHash", Documents.text(doc, "continuityHash"));
      ObjectNode saved = this.store.create(ResourceKind.EPISODE, ep);
      int no = 0;

      for(JsonNode s : doc.path("content").path("scenes")) {
         ObjectNode scene = (ObjectNode)s.deepCopy();
         ObjectNode var10000 = scene.put("projectId", Documents.project(doc)).put("episodeId", Documents.id(saved));
         ++no;
         var10000.put("sceneNo", no).put("storyDocumentId", Documents.id(doc)).put("locked", true);
         scene.set("state", Documents.obj());
         this.store.create(ResourceKind.SCENE, scene);
      }

   }

   private void invalidate(ObjectNode source) {
      Set<String> invalidDocs = new HashSet();
      String type = Documents.text(source, "documentType");

      for(ObjectNode d : this.docs(Documents.project(source))) {
         if (Documents.text(d, "coreId").equals(Documents.text(source, "coreId")) && !d.path("stale").asBoolean()) {
            boolean affected = "CORE".equals(type) || Documents.id(d).equals(Documents.id(source)) || "OUTLINE_BATCH".equals(type) && ("EPISODE_SCRIPT".equals(Documents.text(d, "documentType")) || "OUTLINE_BATCH".equals(Documents.text(d, "documentType")) && d.path("batchNo").asInt() >= source.path("batchNo").asInt()) || "EPISODE_SCRIPT".equals(type) && "EPISODE_SCRIPT".equals(Documents.text(d, "documentType")) && d.path("episodeNo").asInt() >= source.path("episodeNo").asInt();
            if (affected) {
               invalidDocs.add(Documents.id(d));
               this.store.update(ResourceKind.STORY_DOCUMENT, Documents.id(d), Documents.revision(d), d.deepCopy().put("stale", true).put("staleReason", "上游版本已修改，需要重新生成或审查"));
               if (d.hasNonNull("generationJobId")) {
                  ObjectNode job = this.store.get(ResourceKind.GENERATION_JOB, Documents.text(d, "generationJobId"));
                  if (this.activeJob(job)) {
                     this.jobs.cancel(Documents.id(job));
                  }
               }
            }
         }
      }

      Set<String> episodeIds = new HashSet();
      Set<String> sceneIds = new HashSet();

      for(ObjectNode ep : this.store.list(ResourceKind.EPISODE, Documents.project(source), (String)null)) {
         if (invalidDocs.contains(Documents.text(ep, "storyDocumentId"))) {
            episodeIds.add(Documents.id(ep));
            this.store.update(ResourceKind.EPISODE, Documents.id(ep), Documents.revision(ep), ep.deepCopy().put("stale", true));
         }
      }

      for(ObjectNode s : this.store.list(ResourceKind.SCENE, Documents.project(source), (String)null)) {
         if (episodeIds.contains(Documents.text(s, "episodeId"))) {
            sceneIds.add(Documents.id(s));
            this.store.update(ResourceKind.SCENE, Documents.id(s), Documents.revision(s), s.deepCopy().put("stale", true));
         }
      }

      for(ObjectNode shot : this.store.list(ResourceKind.SHOT, Documents.project(source), (String)null)) {
         if (sceneIds.contains(Documents.text(shot, "sceneId"))) {
            this.store.update(ResourceKind.SHOT, Documents.id(shot), Documents.revision(shot), shot.deepCopy().put("stale", true));
         }
      }

      for(ObjectNode t : this.store.list(ResourceKind.TIMELINE, Documents.project(source), (String)null)) {
         if (episodeIds.contains(Documents.text(t, "episodeId"))) {
            this.store.update(ResourceKind.TIMELINE, Documents.id(t), Documents.revision(t), t.deepCopy().put("stale", true));
         }
      }

   }

   public JsonNode approvedSnapshot(ObjectNode episode) {
      if (episode.path("stale").asBoolean()) {
         throw new WorkflowException("STALE_SCRIPT", "剧本已被新版本替代，请重新确认当前版本");
      } else if (!episode.hasNonNull("storyDocumentId")) {
         throw new WorkflowException("SCRIPT_REVIEW_REQUIRED", "请在审查台确认当前版本的单集剧本后再生产");
      } else {
         ObjectNode doc = this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(episode, "storyDocumentId"));
         this.requireConfirmed(doc);
         return this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(doc, "coreId")).path("continuitySnapshot").deepCopy();
      }
   }

   public void checkProductionInput(ObjectNode job) {
      if (Set.of("DIRECTOR_PLAN", "SHOT_DETAIL", "KEYFRAME", "STORYBOARD", "VIDEO", "KEYFRAME_QC", "VIDEO_QC", "TTS", "LIPSYNC", "TIMELINE", "RENDER").contains(Documents.text(job, "type"))) {
         JsonNode input = job.path("inputSnapshot");
         String episodeId = "";
         if (job.hasNonNull("shotId")) {
            ObjectNode shot = this.store.get(ResourceKind.SHOT, Documents.text(job, "shotId"));
            if (shot.path("stale").asBoolean()) {
               throw new WorkflowException("STALE_SHOT", "本镜头引用的剧本已更新，请确认新版本后再生产");
            }

            episodeId = Documents.required(this.store.get(ResourceKind.SCENE, Documents.required(shot, "sceneId")), "episodeId");
         } else if (Set.of("DIRECTOR_PLAN", "SHOT_DETAIL").contains(Documents.text(job, "type"))) {
            JsonNode sceneInput="SHOT_DETAIL".equals(Documents.text(job,"type"))?input.path("scene"):input.path("scene");
            episodeId = Documents.required(this.store.get(ResourceKind.SCENE, Documents.required(sceneInput, "id")), "episodeId");
         } else if ("TIMELINE".equals(Documents.text(job, "type"))) {
            episodeId = Documents.required(input, "episodeId");
         } else if ("RENDER".equals(Documents.text(job, "type"))) {
            episodeId = Documents.required(this.store.get(ResourceKind.TIMELINE, Documents.required(input, "timelineId")), "episodeId");
         }

         if (!episodeId.isBlank()) {
            this.approvedSnapshot(this.store.get(ResourceKind.EPISODE, episodeId));
         }

      }
   }
}
