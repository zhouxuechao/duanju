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
import com.yourapp.drama.production.RewriteBoundary;
import com.yourapp.drama.production.PromptCompiler;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StoryDevelopmentService {
   private static final Logger log = LoggerFactory.getLogger(StoryDevelopmentService.class);
   private final DocumentStore store;
   private final JobService jobs;
   private final LlmGateway llm;
   private final ObjectMapper mapper;
   private final StructuredJson structured;
   private final StoryProfilePolicy storyProfiles;
   private final EpisodeFormatResolver episodeFormats;
   private final StoryFormatResolver storyFormats;
   private final ScreenwritingRuleResolver ruleResolver;
   private final StoryContractValidator storyContracts;
   private final StoryQualityService storyQuality;
   private final PromptCompiler prompts;

   public StoryDevelopmentService(DocumentStore store, JobService jobs, LlmGateway llm, ObjectMapper mapper, Validator validator,
                                  StoryProfilePolicy storyProfiles, EpisodeFormatResolver episodeFormats,
                                  StoryFormatResolver storyFormats, ScreenwritingRuleResolver ruleResolver, StoryContractValidator storyContracts,
                                  StoryQualityService storyQuality,PromptCompiler prompts) {
      this.store = store;
      this.jobs = jobs;
      this.llm = llm;
      this.mapper = mapper;
      this.structured = new StructuredJson(mapper, validator);
      this.storyProfiles = storyProfiles;
      this.episodeFormats = episodeFormats;
      this.storyFormats = storyFormats;
      this.ruleResolver = ruleResolver;
      this.storyContracts = storyContracts;
      this.storyQuality = storyQuality;
      this.prompts=prompts;
   }

   public ObjectNode start(String projectId, ObjectNode request) {
      return (ObjectNode)this.store.transaction(() -> {
         ObjectNode project = this.store.getForUpdate(ResourceKind.PROJECT, projectId);
         ObjectNode normalizedProject = this.storyProfiles.enrich(project);
         normalizedProject.set("episodeFormat", this.episodeFormats.resolve(normalizedProject));
         normalizedProject.set("storyFormat",this.storyFormats.resolve(normalizedProject));
         if (!normalizedProject.equals(project)) {
            project = this.store.update(ResourceKind.PROJECT, projectId, Documents.revision(project), normalizedProject);
         }
          if (project.hasNonNull("activeStoryDocumentId")) {
             ObjectNode active=this.store.get(ResourceKind.STORY_DOCUMENT, Documents.text(project, "activeStoryDocumentId"));
             String briefId=Documents.text(active,"storyBriefId");
             if(!briefId.isBlank()){
                ObjectNode brief=this.store.get(ResourceKind.STORY_DOCUMENT,briefId);
                if(!"CONFIRMED".equals(Documents.text(brief,"reviewStatus")))return brief;
             }
             return active;
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
                ObjectNode brief=Documents.obj().put("projectId",projectId).put("coreId",coreId).put("documentType","STORY_BRIEF")
                        .put("version",1).put("reviewStatus","WAITING");
                brief.set("projectSnapshot",project.deepCopy());brief.set("content",Documents.obj());
                ObjectNode storedBrief=this.store.create(ResourceKind.STORY_DOCUMENT,brief);
                this.store.update(ResourceKind.STORY_DOCUMENT,coreId,Documents.revision(saved),saved.deepCopy().put("storyBriefId",Documents.id(storedBrief)));
                return this.schedule(storedBrief);
            } else {
               throw new IllegalArgumentException("请先设置每集目标时长");
            }
         } else {
            throw new IllegalArgumentException("episodeCount 集数必须为 1 到 100 的整数，请先完善作品设定");
         }
      });
   }

   public ObjectNode edit(String documentId, ObjectNode request) {
      ObjectNode saved = (ObjectNode)this.store.transaction(() -> {
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
                  next.put("id", nextId).put("supersedesId", documentId).put("version", doc.path("version").asInt() + 1).put("reviewStatus", this.needsStoryQa(doc) ? "QA_PENDING" : "REVIEW").put("stale", false);
                  this.clearStoryQa(next);
                  if ("CORE".equals(Documents.text(doc, "documentType"))) {
                     next.put("coreId", nextId);
                  } else {
                     next.put("continuityHash", Documents.text(doc, "continuityHash"));
                  }

                  this.invalidate(doc);
                  ObjectNode created = this.store.create(ResourceKind.STORY_DOCUMENT, next);
                  if ("CORE".equals(Documents.text(doc, "documentType"))) {
                     ObjectNode p = this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(doc));
                     this.store.update(ResourceKind.PROJECT, Documents.id(p), Documents.revision(p), p.deepCopy().put("activeStoryDocumentId", nextId));
                  }
                  this.recordHumanEdit(doc, created, request);
                  return created;
               } else {
                  next.put("reviewStatus", this.needsStoryQa(doc) ? "QA_PENDING" : "REVIEW");
                  this.clearStoryQa(next);
                  next.remove(List.of("failureReason", "failureCode"));
                  ObjectNode updated = this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(doc), next);
                  this.recordHumanEdit(doc, updated, request);
                  return updated;
               }
            }
         }
      });
      return this.needsStoryQa(saved) ? this.scheduleStoryQa(saved) : saved;
   }

   public ObjectNode reviewPremise(String documentId, ObjectNode request) {
      String action = request.path("action").asText("").trim();
      if (!Set.of("EDIT_IDEA", "ACCEPT_RECOMMENDATIONS", "FORCE_CONTINUE").contains(action)) {
         throw new IllegalArgumentException("前提门禁操作无效");
      }
      ObjectNode saved = this.store.transaction(() -> {
         ObjectNode doc = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
         this.requireCurrent(doc);
         if (!"PREMISE_REVIEW_REQUIRED".equals(Documents.text(doc, "reviewStatus"))) {
            throw new WorkflowException("PREMISE_REVIEW_NOT_REQUIRED", "当前创意不在前提人工处理状态");
         }
         ObjectNode next = doc.deepCopy();
         String actor = request.path("overrideBy").asText(request.path("reviewer").asText("USER")).trim();
         if ("EDIT_IDEA".equals(action)) {
            String idea = request.path("idea").asText("").trim();
            if (idea.isBlank()) throw new IllegalArgumentException("修改创意时必须提供新的 idea");
            ObjectNode project = this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(doc));
            ObjectNode updatedProject = this.store.update(ResourceKind.PROJECT, Documents.id(project), Documents.revision(project), project.deepCopy().put("idea", idea));
            ArrayNode history = next.withArray("premiseHistory");
            history.add(Documents.obj().put("recordedAt", Instant.now().toString()).set("analysis", next.path("premiseAnalysis").deepCopy()));
            ObjectNode snapshot = updatedProject.deepCopy();
            snapshot.set("episodeFormat", this.episodeFormats.resolve(snapshot));
            next.set("projectSnapshot", snapshot);
            next.remove(List.of("premiseAnalysis", "premiseValidation", "premiseOverride", "premiseAcceptance"));
            next.put("reviewStatus", "WAITING");
            return this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(doc), next);
         }
         if ("FORCE_CONTINUE".equals(action)) {
            String reason = request.path("overrideReason").asText("").trim();
            PremiseGate.Result override=new PremiseGate().override(reason,actor.isBlank()?"USER":actor);
            next.set("premiseOverride", Documents.obj().put("overrideAt", Instant.now().toString())
                    .put("overrideBy", override.reviewer()).put("overrideReason", override.reason()));
         } else {
            ObjectNode acceptance = Documents.obj().put("acceptedAt", Instant.now().toString()).put("acceptedBy", actor.isBlank() ? "USER" : actor);
            acceptance.set("recommendedAdjustments", next.path("premiseAnalysis").path("recommendedAdjustments").deepCopy());
            next.set("premiseAcceptance", acceptance);
         }
         next.put("reviewStatus", "PREMISE_PASSED");
         return this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(doc), next);
      });
      return "EDIT_IDEA".equals(action) ? this.schedulePremise(saved) : this.schedule(saved);
   }

   private void recordHumanEdit(ObjectNode before, ObjectNode after, ObjectNode request) {
      if (before.path("content").equals(after.path("content"))) return;
      ObjectNode context = Documents.obj()
         .put("documentVersion", after.path("version").asInt())
         .put("storyDocumentId", Documents.id(after));
      String jobId = Documents.text(before, "generationJobId");
      if (!jobId.isBlank()) {
         this.store.find(ResourceKind.GENERATION_JOB, jobId).ifPresent(job -> {
            context.put("model", Documents.text(job, "model"));
            context.put("promptVersionId", Documents.text(job.path("inputSnapshot"), "promptVersionId"));
            context.put("rulePackFingerprint", Documents.text(job.path("inputSnapshot"), "rulePackFingerprint"));
         });
      }
      ObjectNode feedback = Documents.obj()
         .put("projectId", Documents.project(after))
         .put("targetKind", Documents.text(after, "documentType"))
         .put("targetId", Documents.id(after))
         .put("sourceTargetId", Documents.id(before))
         .put("fieldPath", "content")
         .put("episodeNo", after.path("episodeNo").asInt(0))
         .put("storyType", Documents.text(after.path("projectSnapshot").path("storyProfile"), "storyType"))
         .put("freeformNote", Documents.text(request, "feedbackNote"));
      feedback.set("beforeTextOrJson", before.path("content").deepCopy());
      feedback.set("afterTextOrJson", after.path("content").deepCopy());
      ArrayNode reasons = feedback.putArray("reasonCodes");
      if (request.path("reasonCodes").isArray()) request.path("reasonCodes").forEach(value -> {
         String code = value.asText("").trim();
         if (!code.isBlank()) reasons.add(code);
      });
      feedback.set("versionContext", context);
      this.store.create(ResourceKind.HUMAN_EDIT_FEEDBACK, feedback);
   }

   public ObjectNode confirm(String documentId, ObjectNode request) {
      ObjectNode candidate=this.store.get(ResourceKind.STORY_DOCUMENT,documentId);
      if("STORY_BRIEF".equals(Documents.text(candidate,"documentType"))){
         if("CONFIRMED".equals(Documents.text(candidate,"reviewStatus")))return candidate;
         return this.confirmStoryBrief(documentId,request);
      }
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
               if (this.needsStoryQa(doc) && (!doc.path("storyQa").path("passed").asBoolean() || !this.storyQuality.contentHash(doc.path("content")).equals(Documents.text(doc, "qaContentHash")))) {
                  throw new WorkflowException("STORY_QA_REQUIRED", "当前剧本内容尚未通过对应版本的故事质检，请完成质检后再确认");
               }
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
      ObjectNode snapshot = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
      if ("QA_FAILED".equals(Documents.text(snapshot, "reviewStatus"))) {
         ObjectNode oldQa = this.store.get(ResourceKind.GENERATION_JOB, Documents.required(snapshot, "qaJobId"));
         if (oldQa.path("submissionUncertain").asBoolean()) throw new WorkflowException("SUBMISSION_UNCERTAIN", "故事质检提交状态不确定，请先核对服务商记录；不能自动重发");
         return this.scheduleStoryQa(snapshot);
      }
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
            return "CORE".equals(Documents.text(doc, "documentType")) && !doc.path("premiseAnalysis").isObject()
                    ? this.schedulePremise(doc) : this.schedule(doc);
         }
      });
   }

   private ObjectNode confirmStoryBrief(String documentId,ObjectNode request){
      ObjectNode confirmed=this.store.transaction(()->{
         ObjectNode doc=this.store.getForUpdate(ResourceKind.STORY_DOCUMENT,documentId);this.requireCurrent(doc);
         if("CONFIRMED".equals(Documents.text(doc,"reviewStatus")))return doc;
         this.expectRevision(doc,request);
         if(!"REVIEW".equals(Documents.text(doc,"reviewStatus")))throw new WorkflowException("DRAFT_NOT_READY","故事需求尚未生成或修改完成");
         ObjectNode next=doc.deepCopy().put("reviewStatus","CONFIRMED").put("confirmedAt",Instant.now().toString());
         next.set("validation",this.validate(doc,doc.path("content")));
         return this.store.update(ResourceKind.STORY_DOCUMENT,documentId,Documents.revision(doc),next);
      });
      ObjectNode core=this.store.transaction(()->{
         ObjectNode current=this.store.getForUpdate(ResourceKind.STORY_DOCUMENT,Documents.text(confirmed,"coreId"));
         ObjectNode next=current.deepCopy().put("storyBriefId",Documents.id(confirmed));
         next.set("storyBriefSnapshot",confirmed.path("content").deepCopy());
         return this.store.update(ResourceKind.STORY_DOCUMENT,Documents.id(current),Documents.revision(current),next);
      });
      this.schedulePremise(core);
      return confirmed;
   }

   public ObjectNode rewrite(String documentId, ObjectNode request) {
      ObjectNode created = this.store.transaction(() -> {
         ObjectNode before = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
         this.store.getForUpdate(ResourceKind.PROJECT, Documents.project(before));
         ObjectNode doc = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
         this.requireCurrent(doc);
         this.expectRevision(doc, request);
         if (!this.needsStoryQa(doc)) throw new WorkflowException("REWRITE_STAGE_INVALID", "只有单集剧本支持按 Story QA 定向改写");
         if (!"REVIEW".equals(Documents.text(doc, "reviewStatus")) || !doc.path("storyQa").path("rewriteRequired").asBoolean())
            throw new WorkflowException("REWRITE_NOT_REQUIRED", "当前剧本没有需要自动改写的 Story QA 阻断问题");
         int attempt = doc.path("rewriteAttempt").asInt() + 1;
         if (attempt > 2) throw new WorkflowException("REWRITE_LIMIT_REACHED", "自动改写最多执行两次，请人工修改剧本或上游集纲");
         String nextId = UUID.randomUUID().toString();
         ObjectNode next = doc.deepCopy();
         next.remove(List.of("id", "parentId", "revision", "createdAt", "updatedAt", "generationJobId", "confirmedAt", "qaJobId",
                 "qaProviderRequestId", "qaModel", "qaContentHash", "storyQa", "qaFailureCode", "qaFailureReason", "failureReason", "failureCode"));
         next.put("id", nextId).put("supersedesId", documentId).put("version", doc.path("version").asInt() + 1)
                 .put("rewriteAttempt", attempt).put("reviewStatus", "WAITING").put("stale", false);
         ObjectNode source = (ObjectNode)doc.path("sourceSnapshot").deepCopy();
         ObjectNode rewrite = Documents.obj().put("attempt", attempt).put("preserveStartState", Documents.text(doc.path("content"), "startState"))
                 .put("preserveEndState", Documents.text(doc.path("content"), "endState"));
         rewrite.set("blockingIssues", doc.path("storyQa").path("blockingIssues").deepCopy());
         rewrite.set("rewriteInstructions", doc.path("storyQa").path("rewriteInstructions").deepCopy());
         rewrite.set("previousScript", doc.path("content").deepCopy());
         int episodeNo=doc.path("episodeNo").asInt();JsonNode previous=Documents.obj(),following=Documents.obj();
         for(ObjectNode other:this.docs(Documents.project(doc)))if("EPISODE_SCRIPT".equals(Documents.text(other,"documentType"))&&Documents.text(doc,"coreId").equals(Documents.text(other,"coreId"))&&!other.path("stale").asBoolean()&&"CONFIRMED".equals(Documents.text(other,"reviewStatus"))){if(other.path("episodeNo").asInt()==episodeNo-1)previous=other.path("content");if(other.path("episodeNo").asInt()==episodeNo+1)following=other.path("content");}
         source.set("rewriteBoundary",new RewriteBoundary(this.mapper).build(episodeNo,previous,doc.path("content"),following));
         source.set("rewriteRequest", rewrite);
         next.set("sourceSnapshot", source);
         this.invalidate(doc);
         return this.store.create(ResourceKind.STORY_DOCUMENT, next);
      });
      return this.schedule(created);
   }

   public void process(ObjectNode job) {
      String documentId = Documents.required(job.path("inputSnapshot"), "documentId");
      ObjectNode doc = this.store.get(ResourceKind.STORY_DOCUMENT, documentId);
      if (this.current(doc) && Documents.id(job).equals(Documents.text(doc, "generationJobId")) && !job.path("cancelRequested").asBoolean()) {
         JsonNode input = job.path("inputSnapshot");
         if ("PREMISE".equals(Documents.text(input, "phase"))) {
            this.processPremise(job, doc, input);
            return;
         }
         ObjectNode schema = StoryDevelopmentSchemas.forDocument(doc);
         var compiled=this.prompts.compileStory(Documents.text(doc,"documentType"),input,schema);this.jobs.mutate(Documents.id(job),j->{j.put("promptCompilerVersion",compiled.compilerVersion());j.set("promptIR",compiled.promptIRJson().deepCopy());});
         LlmGateway.StructuredRequest request = new LlmGateway.StructuredRequest(compiled.systemPrompt(), compiled.userPrompt(), this.mapper.convertValue(compiled.schema(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}), Map.of());
         LlmGateway.StructuredResult<JsonNode> result = this.llm.generate(request, JsonNode.class);
         this.jobs.mutate(Documents.id(job), (j) -> j.put("providerRequestId", result.requestId()).put("model", result.model()).put("simulated", result.simulated()));
         JsonNode generatedContent = this.canonicalizeSystemOwnedFields(doc, result.value());

         ObjectNode validation;
         try {
            validation = this.validate(doc, generatedContent);
         } catch (RuntimeException error) {
            String rawMessage = error.getMessage();
            String failureMessage = rawMessage == null || rawMessage.isBlank() ? error.getClass().getSimpleName() : rawMessage;
            log.warn("Story document validation failed: documentId={}, type={}", documentId, Documents.text(doc, "documentType"), error);
            this.store.transaction(() -> {
               ObjectNode latest = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
               ObjectNode invalid = latest.deepCopy().put("reviewStatus", "FAILED");
               invalid.set("content", generatedContent.deepCopy());
               invalid.set("validation", Documents.obj().put("passed", false).put("message", failureMessage));
               this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(latest), invalid);
               return null;
            });
            throw error;
         }

         ObjectNode generated = this.store.transaction(() -> {
            ObjectNode latest = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, documentId);
            boolean needsQa = "EPISODE_SCRIPT".equals(Documents.text(latest, "documentType")) && this.current(latest);
            ObjectNode next = latest.deepCopy().put("reviewStatus", this.current(latest) ? (needsQa ? "QA_PENDING" : "REVIEW") : "STALE").put("providerRequestId", result.requestId());
            next.set("content", generatedContent.deepCopy());
            next.set("validation", validation);
            ObjectNode saved = this.store.update(ResourceKind.STORY_DOCUMENT, documentId, Documents.revision(latest), next);
            this.jobs.succeed(Documents.id(job), Documents.obj().put("documentId", documentId).put("stage", Documents.text(doc, "documentType")).put("awaitingReview", this.current(latest)).put("simulated", result.simulated()));
            return saved;
         });
         if ("QA_PENDING".equals(Documents.text(generated, "reviewStatus"))) this.scheduleStoryQa(generated);
      } else {
         this.jobs.mutate(Documents.id(job), (j) -> j.put("status", "CANCELLED"));
      }
   }

   private JsonNode canonicalizeSystemOwnedFields(ObjectNode document, JsonNode generated) {
      if (!generated.isObject()) return generated;
      ObjectNode content = (ObjectNode)generated.deepCopy();
      String documentType = Documents.text(document, "documentType");
      if ("CORE".equals(documentType)) {
         ObjectNode project = this.storyProfiles.enrich((ObjectNode)document.path("projectSnapshot").deepCopy());
         content.set("storyProfile", project.path("storyProfile").deepCopy());
         content.path("locations").forEach(this::canonicalizeTopologyReferences);
      } else if ("EPISODE_SCRIPT".equals(documentType)) {
         JsonNode outline = document.path("sourceSnapshot").path("episodeOutline");
         if (outline.isObject()) {
            content.set("startState", outline.path("startState").deepCopy());
            content.set("endState", outline.path("endState").deepCopy());
         }
         JsonNode protectedState = document.path("sourceSnapshot").path("rewriteBoundary").path("protected");
         if (protectedState.isObject()) {
            for (String field : List.of("establishedFacts", "unrevealedSecrets", "characterKnowledge", "evidenceIds", "relationships")) {
               JsonNode expected = protectedState.path(field);
               if (!expected.isMissingNode() && !expected.isNull()) content.set(field, expected.deepCopy());
            }
         }
      }
      return content;
   }

   private void canonicalizeTopologyReferences(JsonNode location) {
      JsonNode bible = location.path("locationBible");
      Set<String> surfaces = new LinkedHashSet<>(), nodes = new LinkedHashSet<>();
      bible.path("surfaces").forEach(value -> {
         String id = Documents.text(value, "surfaceId");
         if (!id.isBlank()) { surfaces.add(id); nodes.add(id); }
      });
      bible.path("fixedFeatures").forEach(value -> {
         if (value.isObject()) {
            ObjectNode feature = (ObjectNode)value;
            String id = Documents.text(feature, "featureId");
            if (!id.isBlank()) nodes.add(id);
            String support = Documents.text(feature, "supportSurfaceId");
            String canonical = uniqueNearReference(support, surfaces);
            if (!canonical.equals(support)) feature.put("supportSurfaceId", canonical);
         }
      });
      bible.path("lightSources").forEach(value -> {
         String id = Documents.text(value, "lightId");
         if (!id.isBlank()) nodes.add(id);
      });
      bible.path("spatialRelations").forEach(value -> {
         if (!value.isObject()) return;
         ObjectNode relation = (ObjectNode)value;
         for (String field : List.of("subjectId", "objectId")) {
            String reference = Documents.text(relation, field);
            String canonical = uniqueNearReference(reference, nodes);
            if (!canonical.equals(reference)) relation.put(field, canonical);
         }
      });
   }

   private String uniqueNearReference(String reference, Set<String> allowed) {
      if (reference.isBlank() || allowed.contains(reference)) return reference;
      List<String> sameCaseFold = allowed.stream().filter(candidate -> candidate.equalsIgnoreCase(reference)).toList();
      if (sameCaseFold.size() == 1) return sameCaseFold.getFirst();
      List<String> near = allowed.stream().filter(candidate -> Math.max(candidate.length(), reference.length()) >= 4)
              .filter(candidate -> editDistance(candidate.toUpperCase(Locale.ROOT), reference.toUpperCase(Locale.ROOT)) <= 1).toList();
      return near.size() == 1 ? near.getFirst() : reference;
   }

   private int editDistance(String left, String right) {
      int[] previous = new int[right.length() + 1];
      for (int j = 0; j <= right.length(); j++) previous[j] = j;
      for (int i = 1; i <= left.length(); i++) {
         int[] current = new int[right.length() + 1]; current[0] = i;
         for (int j = 1; j <= right.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
         previous = current;
      }
      return previous[right.length()];
   }

   public void syncFailure(ObjectNode job) {
      if (Set.of("STORY", "SCRIPT").contains(Documents.text(job, "type")) && Set.of("FAILED", "RETRY_WAIT").contains(Documents.text(job, "status"))) {
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

   private boolean needsStoryQa(JsonNode document) {
      return "EPISODE_SCRIPT".equals(Documents.text(document, "documentType"));
   }

   private void clearStoryQa(ObjectNode document) {
      document.remove(List.of("qaJobId", "qaProviderRequestId", "qaModel", "qaContentHash", "storyQa", "qaFailureCode", "qaFailureReason"));
   }

   private ObjectNode scheduleStoryQa(ObjectNode document) {
      try {
         return this.storyQuality.schedule(document);
      } catch (RuntimeException error) {
         log.warn("Unable to schedule story QA: documentId={}", Documents.id(document), error);
         return this.storyQuality.markSchedulingFailure(document, error);
      }
   }

   private ObjectNode schedule(ObjectNode doc) {
      ObjectNode input = Documents.obj().put("pipelineVersion", 2).put("documentId", Documents.id(doc)).put("phase", Documents.text(doc, "documentType"));
      ObjectNode project = this.storyProfiles.enrich((ObjectNode)doc.path("projectSnapshot").deepCopy());
      ObjectNode episodeFormat = this.episodeFormats.resolve(project);
      ObjectNode storyFormat=this.storyFormats.resolve(project);
      project.set("episodeFormat", episodeFormat.deepCopy());
      project.set("storyFormat",storyFormat.deepCopy());
      input.set("project", project);
      input.set("storyProfile", project.path("storyProfile").deepCopy());
      input.set("episodeFormat", episodeFormat);
      input.set("storyFormat",storyFormat);
       String documentType=Documents.text(doc,"documentType"),basePrompt=this.prompts.storySkillText(documentType);
       ObjectNode rulePack=this.ruleResolver.resolve(documentType, project.path("storyProfile"), episodeFormat,storyFormat,
               project.path("distributionProfile").asText("GENERAL"),basePrompt,
               project.path("writerModelProfile").asText("DEEPSEEK_WRITER"));
       input.set("rulePack",rulePack);input.put("rulePackFingerprint",Documents.text(rulePack,"fingerprint"))
               .put("promptCompilerVersion",ScreenwritingRuleResolver.STORY_COMPILER_VERSION);
      if ("CORE".equals(Documents.text(doc, "documentType")) && doc.path("premiseAnalysis").isObject()) {
         input.set("premiseAnalysis", doc.path("premiseAnalysis").deepCopy());
      }
      if (!Set.of("CORE","STORY_BRIEF").contains(Documents.text(doc, "documentType"))) {
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

   private ObjectNode schedulePremise(ObjectNode doc) {
      ObjectNode project = this.storyProfiles.enrich((ObjectNode)doc.path("projectSnapshot").deepCopy());
      ObjectNode format = this.episodeFormats.resolve(project);
      ObjectNode storyFormat=this.storyFormats.resolve(project);
      project.set("episodeFormat", format.deepCopy());
      project.set("storyFormat",storyFormat.deepCopy());
      ObjectNode input = Documents.obj().put("pipelineVersion", 2).put("documentId", Documents.id(doc)).put("phase", "PREMISE");
      input.set("project", project);
      input.set("storyProfile", project.path("storyProfile").deepCopy());
      input.set("episodeFormat", format);
      input.set("storyFormat",storyFormat);
      if(doc.path("storyBriefSnapshot").isObject())input.set("storyBrief",doc.path("storyBriefSnapshot").deepCopy());
      String basePrompt=this.prompts.storySkillText("PREMISE");
      ObjectNode rulePack=this.ruleResolver.resolve("PREMISE", project.path("storyProfile"), format,storyFormat,
              project.path("distributionProfile").asText("GENERAL"),basePrompt,
              project.path("writerModelProfile").asText("DEEPSEEK_WRITER"));
      input.set("rulePack",rulePack);input.put("rulePackFingerprint",Documents.text(rulePack,"fingerprint"))
              .put("promptCompilerVersion",ScreenwritingRuleResolver.STORY_COMPILER_VERSION);
      ObjectNode submitted = this.jobs.enqueue(Documents.project(doc), null, "STORY", input, "premise:" + Documents.id(doc) + ":" + UUID.randomUUID());
      this.jobs.mutate(Documents.id(submitted), j -> j.put("maxAttempts", 1));
      ObjectNode next = doc.deepCopy().put("generationJobId", Documents.id(submitted)).put("reviewStatus", "PREMISE_ANALYSIS");
      next.remove(List.of("failureReason", "failureCode"));
      return this.store.update(ResourceKind.STORY_DOCUMENT, Documents.id(doc), Documents.revision(doc), next);
   }

   private void processPremise(ObjectNode job, ObjectNode doc, JsonNode input) {
      var compiled=this.prompts.compileStory("PREMISE",input,StoryDevelopmentSchemas.premise());this.jobs.mutate(Documents.id(job),j->{j.put("promptCompilerVersion",compiled.compilerVersion());j.set("promptIR",compiled.promptIRJson().deepCopy());});
      LlmGateway.StructuredRequest request = new LlmGateway.StructuredRequest(compiled.systemPrompt(), compiled.userPrompt(),
              this.mapper.convertValue(compiled.schema(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}), Map.of());
      LlmGateway.StructuredResult<JsonNode> result = this.llm.generate(request, JsonNode.class);
      JsonNode premise = this.structured.parse(result.value().toString(), StoryDevelopmentSchemas.premise(), JsonNode.class, result.requestId());
      this.jobs.mutate(Documents.id(job), j -> j.put("providerRequestId", result.requestId()).put("model", result.model()).put("simulated", result.simulated()));
      boolean viable = new PremiseGate().evaluate(premise).decision()==PremiseGate.Decision.PASS;
      ObjectNode normalized = premise.deepCopy();
      if (!normalized.hasNonNull("capacityRisk")) normalized.put("capacityRisk", premise.path("risks").isEmpty() ? "未发现超出目标篇幅的容量风险" : premise.path("risks").get(0).asText());
      if (!normalized.path("weaknesses").isArray()) normalized.set("weaknesses", premise.path("risks").deepCopy());
      if (!normalized.path("recommendedAdjustments").isArray()) normalized.set("recommendedAdjustments", premise.path("questionsForCore").deepCopy());
      ObjectNode saved = this.store.transaction(() -> {
         ObjectNode latest = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, Documents.id(doc));
         ObjectNode next = latest.deepCopy().put("premiseProviderRequestId", result.requestId()).put("premiseModel", result.model()).put("reviewStatus", viable ? "PREMISE_PASSED" : "PREMISE_REVIEW_REQUIRED");
         next.set("premiseAnalysis", normalized.deepCopy());
         next.set("premiseValidation", Documents.obj().put("passed", viable).put("checkedAt", Instant.now().toString()));
         ObjectNode updated = this.store.update(ResourceKind.STORY_DOCUMENT, Documents.id(latest), Documents.revision(latest), next);
         this.jobs.succeed(Documents.id(job), Documents.obj().put("documentId", Documents.id(doc)).put("stage", "PREMISE").put("viable", viable).put("simulated", result.simulated()));
         return updated;
      });
      if (!viable) return;
      try {
         this.schedule(saved);
      } catch (RuntimeException error) {
         log.warn("Premise analysis was saved but CORE scheduling failed: documentId={}", Documents.id(doc), error);
         this.store.transaction(() -> {
            ObjectNode latest = this.store.getForUpdate(ResourceKind.STORY_DOCUMENT, Documents.id(doc));
            String message = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
            return this.store.update(ResourceKind.STORY_DOCUMENT, Documents.id(latest), Documents.revision(latest), latest.deepCopy().put("reviewStatus", "FAILED").put("failureCode", "CORE_SCHEDULE_FAILED").put("failureReason", message));
         });
      }
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
         source.set("episodeFormat", this.episodeFormats.resolve(core.path("projectSnapshot")));
         ArrayNode activeUnits = source.putArray("activeUnitArcs");
         for (JsonNode unit : core.path("content").path("unitArcs")) {
            if (unit.path("endEpisode").asInt() >= start && unit.path("startEpisode").asInt() <= end) activeUnits.add(unit.deepCopy());
         }
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
            source.set("episodeFormat", this.episodeFormats.resolve(core.path("projectSnapshot")));
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

      if ("STORY_BRIEF".equals(Documents.text(doc,"documentType"))) {
         return Documents.obj().put("passed",true).put("checkedAt",Instant.now().toString())
                 .put("message","创意约束、主角、对手、冲突、代价与不可改项已结构化");
      } else if ("CORE".equals(Documents.text(doc, "documentType"))) {
         this.unique(content.path("characters"), "characterKey");
         this.unique(content.path("locations"), "locationKey");
         this.unique(content.path("props"), "propKey");

         for(JsonNode p : content.path("characters")) {
            this.unique(p.path("looks"), "lookKey");
         }
         for(JsonNode location : content.path("locations")) {
            this.validateLocationTopology(location);
         }
         ObjectNode expectedProfile = this.storyProfiles.enrich((ObjectNode)doc.path("projectSnapshot").deepCopy()).withObject("storyProfile");
         if (!expectedProfile.equals(content.path("storyProfile"))) {
            throw new IllegalArgumentException("CORE.storyProfile 必须与项目已确认故事画像一致");
         }
         this.storyContracts.validateCore(content, doc.path("projectSnapshot").path("episodeCount").asInt());
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
               this.storyContracts.validateOutlineEpisode(ep, this.episodeFormats.resolve(doc.path("projectSnapshot")));
               if (!preceding.isBlank() && !preceding.equals(Documents.text(ep, "startState"))) {
                  throw new IllegalArgumentException("第 " + ep.path("episodeNo").asInt() + " 集开场状态未承接上一集结尾，请修改开场或显式过渡描述");
               }

               preceding = Documents.text(ep, "endState");
            }
         } else {
            this.checkRefs(content, canon);
            this.duration(content.path("scenes"), doc);
            this.storyContracts.validateScript(content, this.episodeFormats.resolve(doc.path("projectSnapshot")));
            JsonNode outline = doc.path("sourceSnapshot").path("episodeOutline");

            for(String k : List.of("startState", "endState")) {
               if (!Documents.text(content, k).equals(Documents.text(outline, k))) {
                  throw new IllegalArgumentException("剧本的 " + k + " 必须保持已确认集纲的交接状态；改变剧情请先修订集纲");
               }
            }
            if(doc.path("sourceSnapshot").path("rewriteBoundary").isObject()){
               List<com.yourapp.drama.production.ProductionModels.Risk> boundaryRisks=new RewriteBoundary(this.mapper).validate(content,doc.path("sourceSnapshot").path("rewriteBoundary"));
               if(!boundaryRisks.isEmpty())throw new IllegalArgumentException(boundaryRisks.getFirst().code()+"："+boundaryRisks.getFirst().message());
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

      double target = this.episodeFormats.resolve(doc.path("projectSnapshot")).path("targetDurationSec").asDouble(20);
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

   private void validateLocationTopology(JsonNode location) {
      JsonNode bible = location.path("locationBible");
      this.unique(bible.path("surfaces"), "surfaceId");
      this.unique(bible.path("fixedFeatures"), "featureId");
      this.unique(bible.path("lightSources"), "lightId");
      Set<String> surfaces = new HashSet<>(), nodes = new HashSet<>();
      bible.path("surfaces").forEach(value -> { surfaces.add(Documents.text(value,"surfaceId")); nodes.add(Documents.text(value,"surfaceId")); });
      bible.path("fixedFeatures").forEach(value -> {
         String featureId=Documents.text(value,"featureId"), support=Documents.text(value,"supportSurfaceId");nodes.add(featureId);
         if(!surfaces.contains(support))throw new IllegalArgumentException("地点 "+Documents.text(location,"locationKey")+" 的固定设施 "+featureId+" 引用了不存在的承载面 "+support);
      });
      bible.path("lightSources").forEach(value -> nodes.add(Documents.text(value,"lightId")));
      for(JsonNode relation:bible.path("spatialRelations"))for(String field:List.of("subjectId","objectId"))
         if(!nodes.contains(Documents.text(relation,field)))throw new IllegalArgumentException("地点 "+Documents.text(location,"locationKey")+" 的空间关系引用了不存在的节点 "+Documents.text(relation,field));
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

      int characterOrder = 0;
      for(JsonNode p : core.path("content").path("characters")) {
         ObjectNode actor = (ObjectNode)p.deepCopy();
         actor.put("projectId", Documents.project(core)).put("storyBibleId", Documents.id(core)).put("storyOrder", ++characterOrder).put("provider", "SEEDREAM").put("sourceType", "IMAGE_REFERENCE").put("providerStatus", "UNBOUND");
         actor.putArray("requiredViews").add("FRONT").add("LEFT").add("RIGHT").add("BACK");
         ObjectNode saved = this.store.create(ResourceKind.CHARACTER, actor);
         chars.put(Documents.text(p, "characterKey"), Documents.id(saved));
         String firstLook = null;

         int lookOrder = 0;
         for(JsonNode l : p.path("looks")) {
            ObjectNode look = (ObjectNode)l.deepCopy();
            look.put("projectId", Documents.project(core)).put("characterId", Documents.id(saved)).put("storyBibleId", Documents.id(core)).put("storyOrder", ++lookOrder);
            ObjectNode created = this.store.create(ResourceKind.CHARACTER_LOOK, look);
            if (firstLook == null) {
               firstLook = Documents.id(created);
            }
         }

         this.store.update(ResourceKind.CHARACTER, Documents.id(saved), Documents.revision(saved), saved.deepCopy().put("baseLookId", firstLook));
      }

      int locationOrder = 0;
      for(JsonNode p : core.path("content").path("locations")) {
         ObjectNode value = (ObjectNode)p.deepCopy();
         value.put("projectId", Documents.project(core)).put("storyBibleId", Documents.id(core)).put("storyOrder", ++locationOrder);
         value.putArray("requiredViews").add("LAYOUT").add("FRONT").add("REVERSE").add("SIDE");
         ObjectNode saved = this.store.create(ResourceKind.LOCATION, value);
         places.put(Documents.text(p, "locationKey"), Documents.id(saved));
      }

      int propOrder = 0;
      for(JsonNode p : core.path("content").path("props")) {
         ObjectNode value = (ObjectNode)p.deepCopy();
         value.put("projectId", Documents.project(core)).put("storyBibleId", Documents.id(core)).put("storyOrder", ++propOrder);
         value.putArray("requiredViews").add("FRONT").add("SIDE").add("BACK").add("SCALE");
         ObjectNode saved = this.store.create(ResourceKind.PROP, value);
         props.put(Documents.text(p, "propKey"), Documents.id(saved));
      }

      return bindings;
   }

   private void materializeEpisode(ObjectNode doc) {
      ObjectNode ep = Documents.obj().put("projectId", Documents.project(doc)).put("storyBibleId", Documents.text(doc, "coreId")).put("storyDocumentId", Documents.id(doc)).put("episodeNo", doc.path("episodeNo").asInt()).put("name", Documents.text(doc.path("content"), "title")).put("summary", Documents.text(doc.path("content"), "summary")).put("script", Documents.text(doc.path("content"), "script")).put("scriptReviewStatus", "CONFIRMED").put("locked", true).put("continuityHash", Documents.text(doc, "continuityHash"));
      for (String field : List.of("episodeFormatId", "beatMode", "targetDurationSec", "beatBoundaries", "midHook")) if (doc.path("content").has(field)) ep.set(field, doc.path("content").path(field).deepCopy());
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

      this.materializeTemporalContinuity(doc);

   }

   private void materializeTemporalContinuity(ObjectNode doc){
      JsonNode content=doc.path("content"),bindings=this.store.get(ResourceKind.STORY_DOCUMENT,Documents.text(doc,"coreId")).path("continuitySnapshot").path("assetBindings");
      Map<String,String> factIds=new LinkedHashMap<>();
      content.path("storyFacts").fields().forEachRemaining(entry->{JsonNode source=entry.getValue();ObjectNode fact=Documents.obj().put("projectId",Documents.project(doc)).put("factKey",entry.getKey()).put("predicate",Documents.text(source,"predicate")).put("statement",Documents.text(source,"statement")).put("status","ACTIVE").put("validFromStoryTime",source.path("validFromStoryTime").asDouble(0)).put("narrativeRole",source.path("narrativeRole").asText("KNOWN_FACT"));
         if(source.path("revealedAtStoryTime").isNumber())fact.set("revealedAtStoryTime",source.path("revealedAtStoryTime").deepCopy());String subject=resolveBinding(bindings,source.path("subjectCharacterKey").asText());if(!subject.isBlank())fact.put("subjectEntityId",subject);String object=resolveBinding(bindings,source.path("objectPropKey").asText());if(!object.isBlank())fact.put("objectEntityId",object);factIds.put(entry.getKey(),Documents.id(this.store.create(ResourceKind.STORY_FACT,fact)));});
      content.path("characterKnowledge").fields().forEachRemaining(character->{String characterId=resolveBinding(bindings.path("characters"),character.getKey());character.getValue().fields().forEachRemaining(fact->{String factId=factIds.get(fact.getKey());if(characterId.isBlank()||factId==null)return;for(JsonNode interval:fact.getValue()){ObjectNode knowledge=Documents.obj().put("projectId",Documents.project(doc)).put("characterId",characterId).put("factId",factId).put("knowledgeState",interval.path("knowledgeState").asText("UNKNOWN")).put("validFromStoryTime",interval.path("validFromStoryTime").asDouble(0)).put("knownFromStoryTime",interval.path("validFromStoryTime").asDouble(0));if(interval.path("validToStoryTime").isNumber())knowledge.set("validToStoryTime",interval.path("validToStoryTime").deepCopy());this.store.create(ResourceKind.CHARACTER_KNOWLEDGE,knowledge);}});});
      content.path("stateLedger").path("props").fields().forEachRemaining(prop->{String propId=resolveBinding(bindings.path("props"),prop.getKey());if(propId.isBlank())return;for(JsonNode interval:prop.getValue()){ObjectNode state=Documents.obj().put("projectId",Documents.project(doc)).put("propId",propId).put("state",interval.path("state").asText("PRESENT")).put("condition",interval.path("condition").asText("INTACT")).put("visible",interval.path("visible").asBoolean(true)).put("validFromStoryTime",interval.path("validFromStoryTime").asDouble(0));String holder=resolveBinding(bindings.path("characters"),interval.path("carriedByCharacterKey").asText());if(!holder.isBlank())state.put("carriedBy",holder);if(interval.hasNonNull("heldByHand"))state.set("heldByHand",interval.path("heldByHand").deepCopy());if(interval.path("validToStoryTime").isNumber())state.set("validToStoryTime",interval.path("validToStoryTime").deepCopy());this.store.create(ResourceKind.PROP_STATE,state);}});
   }

   private String resolveBinding(JsonNode bindings,String key){if(key==null||key.isBlank())return "";String direct=Documents.text(bindings,key);if(!direct.isBlank())return direct;for(String kind:List.of("characters","locations","props")){String value=Documents.text(bindings.path(kind),key);if(!value.isBlank())return value;}return "";}

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
