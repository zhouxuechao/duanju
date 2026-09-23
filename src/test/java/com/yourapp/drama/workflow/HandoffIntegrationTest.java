package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.job.*;
import com.yourapp.drama.model.*;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.VisualExpectedContextService;
import com.yourapp.drama.production.VideoQualityReviewer;
import com.yourapp.drama.production.VisualQualityReviewer;
import com.yourapp.drama.production.FakeVisualQualityReviewer;
import com.yourapp.drama.production.FakeVideoQualityReviewer;
import com.yourapp.drama.production.VisualQualityProtocol;
import com.yourapp.drama.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HandoffIntegrationTest {
    @Autowired DocumentStore store;@Autowired WorkflowService workflow;@Autowired GenerationWorker worker;@MockitoSpyBean JobService jobs;@Autowired StudioService studio;@Autowired AssetViewService assetViews;@Autowired WebApplicationContext web;@Autowired AutomaticVisualReviewService automaticVisualReview;@Autowired AutomaticVideoReviewService automaticVideoReview;@Autowired VisualExpectedContextService visualExpected;@Autowired QualityMetricsService qualityMetrics;@Autowired VisualCalibrationService calibration;@Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean ImageGenerator images;@MockitoBean VideoGenerator videos;@MockitoBean MediaStorage storage;@MockitoBean ProviderMediaFetcher fetcher;@MockitoBean MediaProbeService mediaProbe;@MockitoBean VideoFrameExtractor videoFrames;@MockitoBean VisualQualityReviewer visualReviewer;@MockitoBean VideoQualityReviewer videoReviewer;
    private String projectId,shotId;
    private static final String ORIGINAL="https://image.volces.com/seedream/frame.png?token=a%2Fb+Z&sig=ABC%2B123%3D&x=1";
    @BeforeEach void setup(){
        ObjectNode project=store.create(PROJECT,obj().put("name","链路集成测试").put("idea","人物停下看向远方").put("ratio","9:16").put("episodeCount",10).put("targetDuration",20));projectId=id(project);
        ObjectNode episode=store.create(EPISODE,obj().put("projectId",projectId).put("name","第一集"));
        ObjectNode scene=store.create(SCENE,obj().put("projectId",projectId).put("episodeId",id(episode)).put("name","田边"));
        ObjectNode location=store.create(LOCATION,obj().put("projectId",projectId).put("name","田边").put("description","夕阳下的田埂"));
        ObjectNode character=store.create(ResourceKind.CHARACTER,obj().put("projectId",projectId).put("name","主角").put("sourceType","IMAGE_REFERENCE"));
        ObjectNode look=store.create(CHARACTER_LOOK,obj().put("projectId",projectId).put("characterId",id(character)).put("name","田间装").put("description","蓝色棉布外套，深色长裤"));
        store.update(ResourceKind.CHARACTER,id(character),revision(character),character.deepCopy().put("baseLookId",id(look)).put("identityLocked",true));
        NewWorkflowTestFixtures.install(store,assetViews,project,episode,character,look,location);
        ObjectNode shot=obj().put("projectId",projectId).put("sceneId",id(scene)).put("locationId",id(location)).put("purpose","发现远处的人").put("duration",3)
            .put("shotSize","MEDIUM").put("cameraAngle","EYE_LEVEL").put("cameraMovement","STATIC").put("action","主角缓慢转头一次").put("relationToPrevious","ESTABLISHING").put("difficulty","B");
        shot.set("cameraPlan",obj().put("position","田埂北侧固定机位").put("height","成人胸口高度").put("distance","4米").put("lensMm",35).put("horizontalAngle","朝向院门").put("verticalAngle","俯角2度").put("subjectPlacement","画面右三分之一").put("focusPoint","主角眼睛").put("depthOfField","中等景深").put("lightingDirection","西侧夕阳"));
        shot.putArray("characterIds").add(id(character));shot.putArray("propIds");shot.putArray("dialogueIds");ObjectNode refs=obj().put(id(look),"FRONT").put(id(location),"FRONT");shot.set("referenceViews",refs);ObjectNode start=obj().put("locationId",id(location));start.putObject("characters").putObject(id(character)).put("identityId",id(character)).put("lookId",id(look));start.set("referenceViews",NewWorkflowTestFixtures.approvedViewIds(store,projectId,id(look)));shot.set("startState",start);shot.set("endState",obj().put("locationId",id(location)));
        shotId=id(studio.create(SHOT,shot));
        when(images.generate(any())).thenReturn(new ImageGenerator.ImageResult(ORIGINAL,Instant.now().plusSeconds(3600),"image-request-1","configured-seedream",false));
        when(videos.submit(any())).thenReturn(new VideoGenerator.Submission("video-task-1","request-1",false));
        when(videos.poll(anyString())).thenReturn(new VideoGenerator.VideoTask("video-task-1",VideoGenerator.Status.SUCCEEDED,"https://video.volces.com/result.mp4",null,"poll-1",null,null,false));
        when(fetcher.open(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));
        when(storage.put(anyString(),any(),anyString())).thenReturn("/api/media/archive.png");
        when(mediaProbe.probe(nullable(java.io.InputStream.class),eq(".mp4"))).thenReturn(obj().put("actualDurationMs",3000).put("width",1080).put("height",1920).put("frameRate",25).put("hasAudio",false));
        when(videoFrames.extract(anyString())).thenReturn(java.util.stream.IntStream.range(0,5).mapToObj(i->new VideoQualityReviewer.Frame(i,"data:image/jpeg;base64,/9j/2Q==")).toList());
        lenient().when(visualReviewer.review(any(),any())).thenAnswer(call->new FakeVisualQualityReviewer(mapper).review(call.getArgument(0),call.getArgument(1)));
        lenient().when(videoReviewer.review(any(),anyList())).thenAnswer(call->new FakeVideoQualityReviewer(mapper).review(call.getArgument(0),call.getArgument(1)));
    }
    private ObjectNode generateFrame(){ObjectNode job=workflow.image(shotId,"KEYFRAME",obj());worker.tick();assertThat(text(store.get(GENERATION_JOB,id(job)),"status")).isEqualTo("SUCCESS");return store.list(KEYFRAME,projectId,shotId).getFirst();}
    @Test void promptVersionPersistsProviderNeutralIrForAuditAndDeterministicRepair(){
        generateFrame();
        ObjectNode prompt=store.list(PROMPT_VERSION,projectId,null).stream().filter(item->shotId.equals(text(item,"shotId"))).findFirst().orElseThrow();
        assertThat(prompt.path("promptIR").path("taskType").asText()).isEqualTo("KEYFRAME");
        assertThat(prompt.path("promptIR").path("identity")).isNotEmpty();
        assertThat(prompt.path("promptIR").path("continuity").path("startState").isObject()).isTrue();
        assertThat(prompt.path("promptIR").path("negativeConstraints").isArray()).isTrue();
    }
    @Test void seedreamOriginalUrlHandedDirectlyToSeedanceBeforeAnyArchiveAndVideoCompletes(){
        ObjectNode frame=generateFrame();verifyNoInteractions(storage,fetcher);
        workflow.review(KEYFRAME,id(frame),obj().put("passed",true).put("score",95));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","p1-integration"));worker.tick();
        verify(videos).submit(argThat(request->request.firstFrameUrl().equals(ORIGINAL)));
        JsonNode audit=job.path("inputSnapshot");assertThat(audit.path("providerOptions").path("generate_audio").asBoolean()).isFalse();assertThat(audit.path("sequenceCompilerVersion").asText()).isEqualTo("4.0.0-sequence");assertThat(audit.path("normalizedPromptHash").asText()).hasSize(64);assertThat(audit.path("referenceBindingsHash").asText()).hasSize(64);assertThat(audit.path("continuitySnapshotHash").asText()).hasSize(64);assertThat(audit.path("sequenceStateFingerprint").asText()).hasSize(64);assertThat(audit.path("referenceAuthorityFingerprint").asText()).hasSize(64);assertThat(audit.path("providerCapabilitiesVersion").asText()).isNotBlank();assertThat(audit.path("capabilityFingerprint").asText()).hasSize(64);assertThat(job.path("capabilityFingerprint").asText()).hasSize(64);assertThat(audit.path("sequenceRelation").asText()).isEqualTo("SEQUENCE_FIRST_CLIP");
        verifyNoInteractions(storage,fetcher);
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();
        assertThat(text(take,"sourceProviderUrlSnapshot")).isEqualTo(ORIGINAL);assertThat(text(take,"sequenceCompilerVersion")).isEqualTo("4.0.0-sequence");assertThat(text(take,"sequenceRelation")).isEqualTo("SEQUENCE_FIRST_CLIP");assertThat(take.path("sequenceStateFingerprint").asText()).hasSize(64);assertThat(take.path("capabilityFingerprint").asText()).hasSize(64);assertThat(text(store.get(KEYFRAME,id(frame)),"handoffStatus")).isEqualTo("HANDED_OFF");
        ObjectNode videoPrompt=store.list(PROMPT_VERSION,projectId,null).stream().filter(item->"VIDEO".equals(text(item,"purpose"))).findFirst().orElseThrow();assertThat(videoPrompt.path("capabilityFingerprint").asText()).hasSize(64);
        for(String field:List.of("modelId","modelProfileVersion","taskType","lockMode","route","activatedMaterials","excludedMaterials","referenceMapping","referenceAuthority","referenceBudget","providerParameters","prompt","rulePackFingerprint"))assertThat(take.has(field)).as(field).isTrue();
        worker.tick();
        assertThat(text(store.get(GENERATION_JOB,id(job)),"status")).isEqualTo("SUCCESS");
        assertThat(text(store.get(VIDEO_TAKE,id(take)),"videoUrl")).endsWith("result.mp4");
        verify(storage).put(startsWith("keyframes/"),any(),eq("image/png"));
        assertThat(text(store.get(KEYFRAME,id(frame)),"providerUrl")).isEqualTo(ORIGINAL);
        assertThat(text(store.get(KEYFRAME,id(frame)),"archiveUrl")).isEqualTo("/api/media/archive.png");
    }
    @Test void videoRequestPreviewDoesNotSubmitOrEnqueueAndNeverExposesSecrets(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        int jobsBefore=store.list(GENERATION_JOB,projectId,null).size();

        ObjectNode preview=workflow.videoPreview(id(frame),obj());

        assertThat(preview.path("modelId").asText()).isNotBlank();
        assertThat(preview.path("route").asText()).isEqualTo("NATIVE_FIRST_FRAME");
        assertThat(preview.path("referenceMapping")).isNotEmpty();
        assertThat(preview.path("providerParameters").path("generate_audio").asBoolean()).isFalse();
        assertThat(preview.toString().toLowerCase()).doesNotContain("authorization","api_key","apikey","secret");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(jobsBefore);
        verify(videos,never()).submit(any());
    }
    @Test void videoTakeKeepsProviderRequestIdSeparateFromTaskId(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","provider-id-separation"));worker.tick();
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();

        assertThat(text(take,"providerRequestId")).isEqualTo("request-1");
        assertThat(text(take,"providerTaskId")).isEqualTo("video-task-1");
    }
    @Test void fullModalReferenceStillArchivesTheSourceKeyframeProviderUrl(){
        ObjectNode current=store.get(SHOT,shotId),earlier=current.deepCopy();
        for(String field:List.of("id","revision","createdAt","updatedAt"))earlier.remove(field);
        earlier.put("shotNo",1).put("relationToPrevious","ESTABLISHING");
        ObjectNode previousShot=store.create(SHOT,earlier);
        ObjectNode previousFrame=store.create(KEYFRAME,obj().put("projectId",projectId).put("shotId",id(previousShot))
            .put("provider","VOLCENGINE").put("providerUrl","https://image.volces.com/previous.png").put("version",1));
        ObjectNode previousTake=obj().put("projectId",projectId).put("shotId",id(previousShot)).put("videoUrl","https://video.volces.com/previous.mp4")
            .put("sourceKeyframeId",id(previousFrame)).put("sourceProviderUrlSnapshot","https://image.volces.com/previous.png")
            .put("providerStatus","SUCCEEDED").put("qcStatus","PASSED").put("selected",true).put("locked",true).put("continuationDepth",0);
        previousTake.set("observedState",obj().put("locationId",text(earlier,"locationId")));
        store.create(VIDEO_TAKE,previousTake);
        store.update(SHOT,shotId,revision(current),current.deepCopy().put("shotNo",2).put("relationToPrevious","CONTINUOUS").put("sequenceRelation","SEAMLESS_CONTINUATION"));
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));

        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","full-modal-archive-source"));
        assertThat(text(job.path("inputSnapshot"),"videoRequestRoute")).isEqualTo("FULL_MODAL_REFERENCE");
        assertThat(job.path("inputSnapshot").has("firstFrameProviderUrl")).isFalse();
        worker.tick();

        verify(videos).submit(argThat(request->request.firstFrameUrl()==null));
        ObjectNode archive=store.list(GENERATION_JOB,projectId,null).stream()
            .filter(candidate->"ARCHIVE".equals(text(candidate,"type"))&&id(frame).equals(text(candidate.path("inputSnapshot"),"targetId")))
            .findFirst().orElseThrow();
        assertThat(text(archive.path("inputSnapshot"),"providerUrl")).isEqualTo(ORIGINAL);
        worker.tick();
        assertThat(text(store.get(GENERATION_JOB,id(archive)),"status")).isEqualTo("SUCCESS");
        assertThat(text(store.get(KEYFRAME,id(frame)),"archiveUrl")).isEqualTo("/api/media/archive.png");
        verify(fetcher).open(ORIGINAL);
    }
    @Test void videoInputSnapshotKeepsCompactKeyframeProvenanceWithoutRecursiveGenerationContext(){
        ObjectNode frame=generateFrame();
        ObjectNode bloated=frame.deepCopy();
        bloated.set("generationInputSnapshot",obj().put("largePayload","x".repeat(200_000)));
        frame=store.update(KEYFRAME,id(frame),revision(frame),bloated);
        workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));

        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","compact-video-provenance"));
        JsonNode input=job.path("inputSnapshot"),keyframeSnapshot=input.path("keyframeSnapshot"),contextFrame=input.path("context").path("keyframe");

        assertThat(keyframeSnapshot.has("generationInputSnapshot")).isFalse();
        assertThat(contextFrame.has("generationInputSnapshot")).isFalse();
        assertThat(keyframeSnapshot.path("id").asText()).isEqualTo(id(frame));
        assertThat(keyframeSnapshot.path("providerRequestId").asText()).isEqualTo(text(frame,"providerRequestId"));
        assertThat(input.toString().length()).isLessThan(50_000);
    }
    @Test void archiveFailureCanRetryWithoutResubmittingVideo(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        workflow.video(id(frame),obj().put("requestKey","archive-recovery"));worker.tick();
        doThrow(new RuntimeException("archive down")).when(fetcher).open(anyString());worker.tick();
        ObjectNode failedArchive=store.list(GENERATION_JOB,projectId,null).stream().filter(j->"ARCHIVE".equals(text(j,"type"))&&"FAILED".equals(text(j,"status"))).findFirst().orElseThrow();

        doReturn(new ByteArrayInputStream(new byte[]{1,2,3})).when(fetcher).open(anyString());
        ObjectNode retried=jobs.retry(id(failedArchive));worker.tick();worker.tick();

        assertThat(text(store.get(GENERATION_JOB,id(retried)),"status")).isEqualTo("SUCCESS");
        verify(videos,times(1)).submit(any());
    }
    @Test void qcAndLockAreMandatoryAndArchiveCannotBypassThem(){
        ObjectNode frame=generateFrame();
        assertThatThrownBy(()->workflow.video(id(frame),obj())).isInstanceOf(WorkflowException.class).hasMessageContaining("质检");
        workflow.review(KEYFRAME,id(frame),obj().put("passed",true));
        assertThatThrownBy(()->workflow.video(id(frame),obj())).isInstanceOf(WorkflowException.class).hasMessageContaining("锁定");
        verifyNoInteractions(videos,storage);
    }

    @Test void lockedKeyframeCanBeRevokedWhenLaterHumanReviewFindsAVisualDefect(){
        ObjectNode frame=generateFrame();
        workflow.review(KEYFRAME,id(frame),obj().put("passed",true).put("notes","首次检查通过"));
        workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));

        ObjectNode revoked=workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("reviewer","HUMAN")
            .put("decision","REGENERATE").put("notes","固定陈设被错误附着到门上"));

        assertThat(revoked.path("qcStatus").asText()).isEqualTo("FAILED");
        assertThat(revoked.path("locked").asBoolean()).isFalse();
        assertThat(revoked.path("selected").asBoolean()).isFalse();
        assertThat(store.get(SHOT,shotId).path("status").asText()).isEqualTo("NEEDS_REPAIR");
        assertThat(store.list(QC_RESULT,projectId,null).stream().filter(q->id(frame).equals(text(q,"targetId")))).hasSize(2);
    }
    @Test void shotReviewPersistsHumanQualityDimensionsAndDecision(){
        ObjectNode frame=generateFrame(); ObjectNode review=obj().put("passed",false).put("decision","REGENERATE").put("characterConsistency",72).put("clothingConsistency",68).put("locationConsistency",88).put("propConsistency",64).put("composition",61).put("actionAccuracy",70).put("styleConsistency",79).put("motionContinuity",70).put("voiceConsistency",90).put("storyAccuracy",84).put("visualQuality",75);
        workflow.review(KEYFRAME,id(frame),review);
        ObjectNode saved=store.list(QC_RESULT,projectId,null).getLast();
        assertThat(saved.path("decision").asText()).isEqualTo("REGENERATE"); assertThat(saved.path("characterConsistency").asInt()).isEqualTo(72); assertThat(saved.path("clothingConsistency").asInt()).isEqualTo(68);assertThat(saved.path("composition").asInt()).isEqualTo(61);assertThat(saved.path("actionAccuracy").asInt()).isEqualTo(70);assertThat(saved.path("styleConsistency").asInt()).isEqualTo(79);assertThat(saved.path("storyAccuracy").asInt()).isEqualTo(84);
    }
    @Test void expiredProviderUrlNeverFallsBackToArchive(){
        ObjectNode expiredFrame=obj().put("projectId",projectId).put("shotId",shotId).put("provider","VOLCENGINE").put("sourceModel","seedream").put("providerUrl",ORIGINAL)
            .put("archiveUrl","https://my-bucket.example.com/archive.png").put("handoffStatus","HANDED_OFF").put("providerUrlExpiresAt",Instant.now().minusSeconds(1).toString()).put("qcStatus","PASSED").put("locked",true).put("version",1);
        String locationId=id(store.list(LOCATION,projectId,null).getFirst());String lookId=id(store.list(CHARACTER_LOOK,projectId,null).getFirst());ArrayNode expiredRefs=NewWorkflowTestFixtures.approvedViewIds(store,projectId,locationId);expiredRefs.addAll(NewWorkflowTestFixtures.approvedViewIds(store,projectId,lookId));expiredFrame.set("assetViewIds",expiredRefs);ObjectNode frame=store.create(KEYFRAME,expiredFrame);
        assertThatThrownBy(()->workflow.video(id(frame),obj())).isInstanceOf(WorkflowException.class).hasMessageContaining("过期");verifyNoInteractions(videos,storage);
    }
    @Test void publicApiCannotForgeGeneratedResourcesOrRewriteLockedIdentity(){
        assertThatThrownBy(()->studio.create(KEYFRAME,obj())).isInstanceOf(WorkflowException.class);
        ObjectNode actor=store.list(ResourceKind.CHARACTER,projectId,null).getFirst();
        assertThatThrownBy(()->studio.update(ResourceKind.CHARACTER,id(actor),obj().put("revision",revision(actor)).put("providerAssetId","another"))).isInstanceOf(WorkflowException.class);
    }
    @Test void archiveFailureDoesNotUndoVideoSubmission(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj());worker.tick();when(fetcher.open(anyString())).thenThrow(new IllegalArgumentException("归档不可用"));worker.tick();
        assertThat(text(store.get(GENERATION_JOB,id(job)),"status")).isEqualTo("SUCCESS");assertThat(text(store.get(KEYFRAME,id(frame)),"handoffStatus")).isEqualTo("HANDED_OFF");
        verify(videos,times(1)).submit(any());
    }
    @Test void jobRequestKeyIsIdempotentAndCancellationIsPersistent(){
        ObjectNode first=jobs.enqueue(projectId,null,"STORY",obj(),"unique-story");ObjectNode second=jobs.enqueue(projectId,null,"STORY",obj(),"unique-story");
        assertThat(id(first)).isEqualTo(id(second));jobs.cancel(id(first));assertThat(text(store.get(GENERATION_JOB,id(first)),"status")).isEqualTo("CANCELLED");worker.tick();verifyNoInteractions(images,videos);
    }
    @Test void repeatedStoryClicksReuseTheActiveJob(){
        ObjectNode first=workflow.story(projectId,obj().put("requestKey","click-one"));
        ObjectNode second=workflow.story(projectId,obj().put("requestKey","click-two"));
        assertThat(id(second)).isEqualTo(id(first));
    }
    @Test void projectSettingsMustUseNumericEpisodeCountAndDuration(){
        assertThatThrownBy(()->studio.create(PROJECT,obj().put("name","类型校验").put("idea","测试").put("episodeCount","10").put("targetDuration",20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("episodeCount");
        assertThatThrownBy(()->studio.create(PROJECT,obj().put("name","类型校验").put("idea","测试").put("episodeCount",10).put("targetDuration","20")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("targetDuration");
    }
    @Test void projectRejectsUnknownDirectorStyleFieldsBeforeTheyCanBreakShotPlanning(){
        ObjectNode request=obj().put("name","导演字段校验").put("idea","测试");
        request.putObject("directorStyleProfile").put("cuttingPace","FAST");
        assertThatThrownBy(()->studio.create(PROJECT,request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cuttingPace");
    }
    @Test void providerFailurePreservesRequestIdWithoutRetry(){
        when(images.generate(any())).thenThrow(new ProviderException("INVALID_STRUCTURED_OUTPUT","模型输出缺少必要字段","request-invalid-output",200,false,false));
        ObjectNode submitted=workflow.image(shotId,"KEYFRAME",obj());worker.tick();
        ObjectNode failed=store.get(GENERATION_JOB,id(submitted));
        assertThat(text(failed,"status")).isEqualTo("FAILED");
        assertThat(text(failed,"providerRequestId")).isEqualTo("request-invalid-output");
        assertThat(text(failed,"failureCode")).isEqualTo("INVALID_STRUCTURED_OUTPUT");
        verify(images,times(1)).generate(any());
    }
    @Test void persistenceConstraintFailureIsReportedAsDataConflict(){
        when(images.generate(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        ObjectNode submitted=workflow.image(shotId,"KEYFRAME",obj().put("requestKey","data-conflict"));worker.tick();
        ObjectNode failed=store.get(GENERATION_JOB,id(submitted));
        assertThat(text(failed,"status")).isEqualTo("FAILED");
        assertThat(text(failed,"failureCode")).isEqualTo("DATA_CONFLICT");
        assertThat(text(failed,"failureReason")).doesNotContain("DataIntegrityViolationException");
    }
    @Test void persistenceFailureAfterVideoAcceptanceCannotBeRetriedAsAnUnsubmittedRequest(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","accepted-then-db-failed"));
        AtomicBoolean accepted=new AtomicBoolean();
        when(videos.submit(any())).thenAnswer(invocation->{accepted.set(true);return new VideoGenerator.Submission("accepted-task","accepted-request",false);});
        doAnswer(invocation->{if(accepted.getAndSet(false))throw new DataIntegrityViolationException("injected post-submit write failure");return invocation.callRealMethod();})
            .when(jobs).mutate(eq(id(job)),any());
        worker.tick();
        ObjectNode failed=store.get(GENERATION_JOB,id(job));
        assertThat(text(failed,"status")).isEqualTo("FAILED");
        assertThat(failed.path("submissionUncertain").asBoolean()).isTrue();
        assertThatThrownBy(()->jobs.retry(id(job))).isInstanceOf(WorkflowException.class).hasMessageContaining("核对");
        verify(videos,times(1)).submit(any());
    }
    @Test void videoRequestKeyCannotHideChangedProviderOptions(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode first=obj().put("requestKey","same-video-key");first.putObject("providerOptions").put("resolution","720p");
        ObjectNode second=obj().put("requestKey","same-video-key");second.putObject("providerOptions").put("resolution","1080p");
        workflow.video(id(frame),first);
        assertThatThrownBy(()->workflow.video(id(frame),second)).isInstanceOf(WorkflowException.class).hasMessageContaining("请求标识");
        assertThat(store.list(GENERATION_JOB,projectId,null).stream().filter(j->"VIDEO".equals(text(j,"type")))).hasSize(1);
    }
    @Test void uncertainSubmissionBlocksNewGenerationForTheSameShot(){
        ObjectNode uncertain=jobs.enqueue(projectId,shotId,"KEYFRAME",obj().put("shotId",shotId),"uncertain-keyframe");
        jobs.claim();
        jobs.fail(id(uncertain),"REQUEST_TIMEOUT","服务商可能已接收请求",false,true);

        assertThatThrownBy(()->workflow.image(shotId,"KEYFRAME",obj().put("requestKey","new-keyframe-after-timeout")))
                .isInstanceOf(WorkflowException.class)
                .hasMessageContaining("核对");
    }
    @Test void cancelRejectionStillPollsAndCompletesVideo(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","cancel-rejection"));worker.tick();
        jobs.cancel(id(job));
        doThrow(new ProviderException("CANCEL_NOT_ALLOWED","火山仅支持取消排队中的任务；当前状态：RUNNING","cancel-request",409,false,false)).when(videos).cancel("video-task-1");
        worker.tick();

        assertThat(text(store.get(GENERATION_JOB,id(job)),"status")).isEqualTo("SUCCESS");
        verify(videos).poll("video-task-1");
    }
    @Test void permanentPollFailureBecomesUnknownAndCanResumeTheExistingPaidTask(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","poll-auth-failure"));worker.tick();
        when(videos.poll("video-task-1")).thenThrow(new ProviderException("HTTP_401","服务商拒绝查询","poll-401",401,false,false));
        worker.tick();

        ObjectNode unknown=store.get(GENERATION_JOB,id(job));
        assertThat(text(unknown,"status")).isEqualTo("UNKNOWN");
        assertThat(unknown.path("reconciliationRequired").asBoolean()).isTrue();
        assertThat(unknown.path("submissionUncertain").asBoolean()).isFalse();
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();
        assertThat(text(take,"providerStatus")).isEqualTo("RECONCILIATION_REQUIRED");
        ObjectNode resumed=jobs.reconcile(id(job),obj().put("decision","CONFIRMED_SUBMITTED").put("evidence","服务商控制台仍有该任务").put("reviewer","TEST"));
        assertThat(text(resumed,"status")).isEqualTo("RUNNING");
        assertThat(text(resumed,"providerTaskId")).isEqualTo("video-task-1");
    }
    @Test void unexpectedPollRuntimeFailureEventuallyEndsJobWithReconciliationState(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        ObjectNode job=workflow.video(id(frame),obj().put("requestKey","poll-runtime-failure"));worker.tick();
        when(videos.poll("video-task-1")).thenThrow(new RuntimeException("network down"));
        for(int attempt=1;attempt<=3;attempt++){
            worker.tick();
            if(attempt<3) jobs.mutate(id(job),j->j.put("nextPollAt",Instant.now().minusSeconds(1).toString()));
        }
        assertThat(text(store.get(GENERATION_JOB,id(job)),"status")).isEqualTo("UNKNOWN");
        assertThat(store.get(GENERATION_JOB,id(job)).path("reconciliationRequired").asBoolean()).isTrue();
    }
    @Test void keyframeRegenerationReusesOriginalPromptReferencesAndProviderOptions(){
        ObjectNode frame=generateFrame(),originalJob=store.get(GENERATION_JOB,required(frame,"generationJobId"));
        ObjectNode regenerated=workflow.regenerateKeyframe(id(frame),obj().put("requestKey","same-settings"));
        JsonNode original=originalJob.path("inputSnapshot"),next=regenerated.path("inputSnapshot");
        assertThat(text(next,"regenerationMode")).isEqualTo("REPLAY_ORIGINAL");
        assertThat(text(next,"parentKeyframeId")).isEqualTo(id(frame));
        assertThat(text(next,"promptVersionId")).isEqualTo(text(original,"promptVersionId"));
        assertThat(next.path("prompt")).isEqualTo(original.path("prompt"));assertThat(next.path("referenceImageUrls")).isEqualTo(original.path("referenceImageUrls"));assertThat(next.path("providerOptions")).isEqualTo(original.path("providerOptions"));
    }
    @Test void keyframePinsTheDirectorVersionsActuallyUsed(){
        ObjectNode shot=store.get(SHOT,shotId);store.update(SHOT,shotId,revision(shot),shot.deepCopy().put("directorPlanVersion",7).put("dramaticBeatVersion",6).put("shotPlanVersion",9));
        ObjectNode frame=generateFrame();
        assertThat(frame.path("directorPlanVersion").asInt()).isEqualTo(7);assertThat(frame.path("dramaticBeatVersion").asInt()).isEqualTo(6);assertThat(frame.path("shotPlanVersion").asInt()).isEqualTo(9);
    }
    @Test void keyframeRegenerationKeepsOriginalAssetReferenceSnapshot(){
        ObjectNode frame=generateFrame(),originalJob=store.get(GENERATION_JOB,required(frame,"generationJobId"));
        ObjectNode shot=store.get(SHOT,shotId),changed=shot.deepCopy();
        String locationId=store.list(LOCATION,projectId,null).getFirst().path("id").asText();
        changed.withObject("referenceViews").put(locationId,"SIDE");
        store.update(SHOT,shotId,revision(shot),changed);
        ObjectNode regenerated=workflow.regenerateKeyframe(id(frame),obj().put("requestKey","preserve-reference-snapshot"));
        assertThat(regenerated.path("inputSnapshot").path("assetViewIds")).isEqualTo(originalJob.path("inputSnapshot").path("assetViewIds"));
        assertThat(regenerated.path("inputSnapshot").path("assetReferences")).isEqualTo(originalJob.path("inputSnapshot").path("assetReferences"));
    }
    @Test void latestRegenerationIsExplicitAndKeepsLineage(){
        ObjectNode frame=generateFrame(); ObjectNode shot=store.get(SHOT,shotId);
        store.update(SHOT,shotId,revision(shot),shot.deepCopy().put("action","LATEST_ACTION_MARKER"));
        ObjectNode regenerated=workflow.regenerateLatestKeyframe(id(frame),obj().put("requestKey","latest-lineage"));
        assertThat(regenerated.path("inputSnapshot").path("regenerationMode").asText()).isEqualTo("LATEST");
        assertThat(regenerated.path("inputSnapshot").path("parentKeyframeId").asText()).isEqualTo(id(frame));
        assertThat(regenerated.path("inputSnapshot").path("context").path("shot").path("action").asText()).isEqualTo("LATEST_ACTION_MARKER");
    }
    @Test void imageRequestLocksTheProjectAspectRatio(){
        ObjectNode frame=generateFrame(),job=store.get(GENERATION_JOB,required(frame,"generationJobId"));
        assertThat(job.path("inputSnapshot").path("ratio").asText()).isEqualTo("9:16");
        assertThat(job.path("inputSnapshot").path("providerOptions").path("size").asText()).isEqualTo("2K");
        assertThat(job.path("inputSnapshot").path("providerOptions").path("watermark").isBoolean()).isTrue();
        assertThat(job.path("inputSnapshot").path("providerOptions").path("watermark").asBoolean()).isFalse();
    }
    @Test void newImageRevisionUsesTheLatestFailedVisualReview(){
        ObjectNode frame=generateFrame();
        workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("notes","人物站到了门的另一侧，恢复已确认的东侧站位"));
        ObjectNode revised=workflow.image(required(frame,"shotId"),"KEYFRAME",obj().put("requestKey","visual-repair"));
        assertThat(revised.path("inputSnapshot").path("prompt").asText()).contains("恢复已确认的东侧站位");
        assertThat(revised.path("inputSnapshot").path("context").path("imageTaskType").asText()).isEqualTo("REPAIR_EDIT");
    }
    @Test void sameVisualFailureCannotTriggerAnUnboundedPaidRetryLoop(){
        ObjectNode frame=generateFrame();
        for(int attempt=1;attempt<=3;attempt++){
            workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("notes","构图错误：中景变成全身镜头"));
            if(attempt<3){workflow.regenerateLatestKeyframe(id(frame),obj().put("requestKey","visual-attempt-"+attempt));worker.tick();frame=store.list(KEYFRAME,projectId,shotId).getLast();}
        }
        ObjectNode rejected=frame;
        assertThatThrownBy(()->workflow.regenerateLatestKeyframe(id(rejected),obj().put("requestKey","visual-attempt-4")))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("连续 3 次");
        verify(images,times(3)).generate(any());

        ObjectNode olderCompiler=store.create(PROMPT_VERSION,obj().put("projectId",projectId).put("shotId",shotId)
            .put("purpose","KEYFRAME").put("version",999).put("prompt","旧策略").put("compilerVersion","3.1.0"));
        ObjectNode oldVersionFrame=store.get(KEYFRAME,id(rejected)),repointed=oldVersionFrame.deepCopy().put("promptVersionId",id(olderCompiler));
        store.update(KEYFRAME,id(oldVersionFrame),revision(oldVersionFrame),repointed);
        ObjectNode afterCompilerFix=workflow.regenerateLatestKeyframe(id(rejected),obj().put("requestKey","visual-after-compiler-fix"));
        assertThat(text(afterCompilerFix,"status")).isEqualTo("QUEUED");
    }
    @Test void projectQualityMetricsAreQueryable() throws Exception{
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("notes","人物身份错误"));
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/projects/{id}/quality-metrics",projectId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewedShots").value(1)).andExpect(jsonPath("$.firstPassRate").value(0.0))
            .andExpect(jsonPath("$.identityFailureRate").value(1.0)).andExpect(jsonPath("$.clothingFailureRate").value(0.0))
            .andExpect(jsonPath("$.locationFailureRate").value(0.0)).andExpect(jsonPath("$.propFailureRate").value(0.0))
            .andExpect(jsonPath("$.compositionFailureRate").value(0.0)).andExpect(jsonPath("$.providerOutputQualityFailureRate").value(1.0)).andExpect(jsonPath("$.providerFailureRate").value(0.0));
    }
    @Test void qualityMetricsCountTheLatestReviewOncePerGeneratedAttempt() throws Exception{
        ObjectNode frame=generateFrame();
        workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("notes","人物身份错误").put("failureOrigin","PROVIDER_OUTPUT"));
        workflow.review(KEYFRAME,id(frame),obj().put("passed",true).put("notes","人工复核确认身份正确"));

        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/projects/{id}/quality-metrics",projectId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewedAttempts").value(1))
            .andExpect(jsonPath("$.identityFailureRate").value(0.0)).andExpect(jsonPath("$.providerOutputQualityFailureRate").value(0.0));
    }
    @Test void qualityMetricsExposeSpatialFailuresRequiredByDirectorAcceptance() throws Exception{
        ObjectNode frame=generateFrame();ObjectNode review=obj().put("projectId",projectId).put("targetKind","keyframes").put("targetId",id(frame)).put("shotId",shotId).put("reviewer","AUTOMATIC").put("shadow",false).put("passed",false);
        review.putObject("diagnosis").put("failureOrigin","PROVIDER_OUTPUT").putArray("failureCodes").add("POSITION_MISMATCH");store.create(QC_RESULT,review);
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/projects/{id}/quality-metrics",projectId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.spatialFailureRate").value(1.0));
    }
    @Test void qualityMetricsCountHumanPassOrRegenerateAsManualIntervention() throws Exception{
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true).put("notes","人工确认画面通过"));
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/projects/{id}/quality-metrics",projectId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.manualInterventionRate").value(1.0));
    }
    @Test void qualityMetricsCountTechnicalProviderFailuresSeparately() throws Exception{
        when(images.generate(any())).thenThrow(new ProviderException("HTTP_500","服务商失败","provider-request-failed",500,false,false));
        workflow.image(shotId,"KEYFRAME",obj().put("requestKey","metrics-provider-failure"));worker.tick();
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/projects/{id}/quality-metrics",projectId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.providerFailureRate").value(1.0)).andExpect(jsonPath("$.providerOutputQualityFailureRate").value(0.0));
    }
    @Test void firstPassRateUsesTheFirstReviewedAttemptInsteadOfAnOlderPendingDraft() throws Exception{
        ObjectNode pending=generateFrame();ObjectNode retry=workflow.regenerateLatestKeyframe(id(pending),obj().put("requestKey","first-reviewed-attempt"));worker.tick();
        ObjectNode reviewed=store.list(KEYFRAME,projectId,shotId).getLast();workflow.review(KEYFRAME,id(reviewed),obj().put("passed",true));
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/projects/{id}/quality-metrics",projectId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewedShots").value(1)).andExpect(jsonPath("$.firstPassRate").value(1.0));
    }
    @Test void resourceListApiBindsOptionalProjectAndParentParameters() throws Exception{
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/resources/keyframes").param("projectId",projectId))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
    }
    @Test void unknownApiRouteIsReportedAsNotFoundInsteadOfInternalFailure() throws Exception{
        MockMvcBuilders.webAppContextSetup(web).build().perform(get("/api/does-not-exist"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void automaticRepairRetriesOnlyWhenTheProviderOutputWasWrong(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("notes","人物脸与身份锁不一致").put("failureOrigin","PROVIDER_OUTPUT"));
        ObjectNode repair=workflow.repairKeyframe(id(frame),obj().put("requestKey","automatic-safe-retry"));
        assertThat(text(repair,"type")).isEqualTo("KEYFRAME");assertThat(text(repair.path("inputSnapshot"),"regeneratedFromId")).isEqualTo(id(frame));
    }
    @Test void automaticRepairDoesNotPayForKnownStateErrors(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("notes","服装状态错误").put("failureOrigin","STORY_STATE"));
        ObjectNode repair=workflow.repairKeyframe(id(frame),obj());
        assertThat(text(repair,"status")).isEqualTo("ACTION_REQUIRED");assertThat(text(repair,"recommendedRepair")).isEqualTo("UPDATE_CHARACTER_STATE");
        verify(images,times(1)).generate(any());
    }
    @Test void automaticVisualReviewDefaultsToShadowWithoutChangingTheQualityGate(){
        ObjectNode frame=generateFrame();ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));
        ObjectNode observed=obj();observed.set("observedConstraints",expected.path("requiredConstraints").deepCopy());
        ObjectNode reviewed=automaticVisualReview.review(id(frame),observed);
        assertThat(text(reviewed,"qcStatus")).isEqualTo("PENDING");
        ObjectNode assessment=store.list(QC_RESULT,projectId,null).getLast();
        assertThat(assessment.path("reviewer").asText()).isEqualTo("AUTOMATIC");
        assertThat(assessment.path("shadow").asBoolean()).isTrue();
        assertThat(assessment.path("routingDecision").asText()).isNotBlank();
        assertThat(assessment.path("expectedContext").findValues("url")).isEmpty();
    }
    @Test void deterministicContextFailureStopsBeforePaidVisualReview(){
        ObjectNode frame=generateFrame(),corrupt=frame.deepCopy();
        ((ArrayNode)corrupt.path("generationInputSnapshot").path("context").path("shot").path("characterIds")).add("missing-character");
        store.update(KEYFRAME,id(frame),revision(frame),corrupt);

        assertThatThrownBy(()->automaticVisualReview.review(id(frame),obj()))
            .isInstanceOf(WorkflowException.class)
            .extracting(error->((WorkflowException)error).code()).isEqualTo("DETERMINISTIC_RULE_FAILED");
        verify(visualReviewer,never()).review(any(),any());
    }
    @Test void shadowAssessmentsDoNotChangeProductionQualityMetrics(){
        ObjectNode frame=generateFrame();ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));
        ObjectNode observed=obj();observed.set("observedConstraints",expected.path("requiredConstraints").deepCopy());automaticVisualReview.review(id(frame),observed);
        ObjectNode metrics=qualityMetrics.metrics(projectId);
        assertThat(metrics.path("reviewedAttempts").asInt()).isZero();
        assertThat(metrics.path("manualInterventionRate").asDouble()).isZero();
    }
    @Test void videoAutomaticReviewUsesFiveFramesAndDefaultsToShadow(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        workflow.video(id(frame),obj().put("requestKey","video-vlm-shadow"));worker.tick();worker.tick();worker.tick();
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();ObjectNode reviewed=automaticVideoReview.review(id(take),obj());
        assertThat(text(reviewed,"qcStatus")).isEqualTo("PENDING");
        ObjectNode assessment=store.list(QC_RESULT,projectId,null).getLast();assertThat(assessment.path("targetKind").asText()).isEqualTo("video-takes");assertThat(assessment.path("shadow").asBoolean()).isTrue();
        verify(videoFrames).extract("archive.png");
    }
    @Test void deterministicContextFailureStopsBeforePaidVideoReview(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        workflow.video(id(frame),obj().put("requestKey","video-rule-preflight"));worker.tick();worker.tick();worker.tick();
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst(),corrupt=take.deepCopy();
        ((ArrayNode)corrupt.path("inputSnapshot").path("context").path("shot").path("characterIds")).add("missing-character");
        store.update(VIDEO_TAKE,id(take),revision(take),corrupt);clearInvocations(videoReviewer,videoFrames);

        assertThatThrownBy(()->automaticVideoReview.review(id(take),obj()))
            .isInstanceOf(WorkflowException.class)
            .extracting(error->((WorkflowException)error).code()).isEqualTo("DETERMINISTIC_RULE_FAILED");
        verifyNoInteractions(videoReviewer,videoFrames);
    }
    @Test void completedVideoArchiveAutomaticallyRunsAppliedVideoQc(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        workflow.video(id(frame),obj().put("requestKey","automatic-video-qc-source"));
        for(int i=0;i<8;i++)worker.tickProject(projectId);

        assertThat(store.list(GENERATION_JOB,projectId,null)).anyMatch(job->"VIDEO_QC".equals(text(job,"type"))&&"SUCCESS".equals(text(job,"status")));
        assertThat(store.list(QC_RESULT,projectId,null)).anyMatch(review->VIDEO_TAKE.path().equals(text(review,"targetKind"))&&"AUTOMATIC".equals(text(review,"reviewer"))&&!review.path("shadow").asBoolean());
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();
        assertThat(text(take,"qcStatus")).isEqualTo("PASSED");
    }
    @Test void appliedHighConfidenceVideoFailureCreatesOneBoundedReplacementJob(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        workflow.video(id(frame),obj().put("requestKey","video-vlm-repair-source"));worker.tick();worker.tick();worker.tick();
        ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();
        doAnswer(call->{
            ObjectNode result=(ObjectNode)new FakeVideoQualityReviewer(mapper).review(call.getArgument(0),call.getArgument(1));
            result.set("actionAccuracy",obj().put("pass",false).put("score",25).put("confidence",.97).put("reason","动作方向与剧本相反").put("evidence","采样帧中的手臂运动方向与计划相反"));
            result.withArray("failureCodes").add("ACTION_MISMATCH");
            result.put("overallScore",70).put("overallConfidence",.97).put("decision","REGENERATE").put("reason","动作方向与剧本相反");return result;
        }).when(videoReviewer).review(any(),anyList());
        ObjectNode request=obj().put("apply",true).put("requestKey","video-vlm-repair-once");int before=store.list(GENERATION_JOB,projectId,null).size();
        ObjectNode reviewed=automaticVideoReview.review(id(take),request);
        assertThat(text(reviewed.path("automaticRepairJob"),"type")).isEqualTo("VIDEO");
        JsonNode retake=reviewed.path("automaticRepairJob").path("inputSnapshot").path("context").path("retake");
        assertThat(retake.path("repairPlan").path("repairDimensions")).extracting(node->node.asText()).containsExactly("ACTION");
        assertThat(retake.path("repairPlan").path("preserveDimensions")).extracting(node->node.asText()).contains("IDENTITY","COSTUME","BACKGROUND","CAMERA");
        assertThat(retake.path("changedPromptSections")).extracting(node->node.asText()).containsExactly("TIMED BEATS");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(before+1);
        automaticVideoReview.review(id(take),request);
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(before+1);
        automaticVideoReview.review(id(take),obj().put("apply",true).put("requestKey","video-vlm-repair-same-take-different-client-key"));
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(before+1);
    }
    @Test void thirdConsecutiveVideoFailureWithSameCodeEscalatesToHumanWithoutAnotherPaidJob(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));
        workflow.video(id(frame),obj().put("requestKey","video-vlm-limit-source"));worker.tick();worker.tick();worker.tick();ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();
        for(int i=1;i<=2;i++){ObjectNode qc=obj().put("projectId",projectId).put("targetKind","video-takes").put("targetId","previous-take-"+i).put("shotId",shotId).put("reviewer","AUTOMATIC").put("shadow",false).put("passed",false);qc.putObject("diagnosis").putArray("failureCodes").add("ACTION_MISMATCH");store.create(QC_RESULT,qc);}
        doAnswer(call->{ObjectNode result=(ObjectNode)new FakeVideoQualityReviewer(mapper).review(call.getArgument(0),call.getArgument(1));result.set("actionAccuracy",obj().put("pass",false).put("score",25).put("confidence",.97).put("reason","动作方向与剧本相反").put("evidence","采样帧中的手臂运动方向与计划相反"));result.withArray("failureCodes").add("ACTION_MISMATCH");return result.put("overallScore",70).put("overallConfidence",.97).put("decision","REGENERATE").put("reason","动作方向与剧本相反");}).when(videoReviewer).review(any(),anyList());
        int before=store.list(GENERATION_JOB,projectId,null).size();ObjectNode reviewed=automaticVideoReview.review(id(take),obj().put("apply",true).put("requestKey","video-vlm-third-failure"));
        assertThat(reviewed.path("automaticReview").path("routingDecision").asText()).isEqualTo("MANUAL_REVIEW");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(before);
    }
    @Test void calibrationPairsLatestShadowAssessmentWithHumanGroundTruth(){
        ObjectNode frame=generateFrame();ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));ObjectNode observed=obj();observed.set("observedConstraints",expected.path("requiredConstraints").deepCopy());
        automaticVisualReview.review(id(frame),observed);workflow.review(KEYFRAME,id(frame),obj().put("passed",true).put("reviewer","HUMAN"));
        ObjectNode metrics=calibration.metrics(projectId);
        assertThat(metrics.path("groundTruthSamples").asInt()).isEqualTo(1);
        assertThat(metrics.path("manualReviewRate").asDouble()).isEqualTo(1.0);
        assertThat(metrics.path("targets").get(0).path("targetId").asText()).isEqualTo(id(frame));
    }
    @Test void calibrationDoesNotTreatCodesInferredFromHumanFreeTextAsExplicitGroundTruth(){
        ObjectNode frame=generateFrame();ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));ObjectNode observed=obj();observed.set("observedConstraints",expected.path("requiredConstraints").deepCopy());
        automaticVisualReview.review(id(frame),observed);workflow.review(KEYFRAME,id(frame),obj().put("passed",false).put("reviewer","HUMAN").put("notes","人物身份和脸不一致"));
        ObjectNode metrics=calibration.metrics(projectId);
        assertThat(metrics.path("failureCodeGroundTruthSamples").asInt()).isZero();
        assertThat(metrics.path("targets").get(0).path("humanFailureCodesLabeled").asBoolean()).isFalse();
    }
    @Test void shadowAssessmentCannotHideTheLatestHumanRepairDiagnosis(){
        ObjectNode frame=generateFrame(),human=obj().put("passed",false).put("failureOrigin","PROVIDER_OUTPUT").put("notes","人物身份不一致");human.putArray("failureCodes").add("IDENTITY_MISMATCH");workflow.review(KEYFRAME,id(frame),human);
        ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));ObjectNode observed=obj();observed.set("observedConstraints",expected.path("requiredConstraints").deepCopy());automaticVisualReview.review(id(frame),observed);
        ObjectNode repair=workflow.repairKeyframe(id(frame),obj().put("requestKey","shadow-does-not-hide-diagnosis"));
        assertThat(text(repair,"type")).isEqualTo("KEYFRAME");
    }
    @Test void appliedHighConfidenceProviderFailureCreatesOneBoundedRepairJob(){
        ObjectNode frame=generateFrame(),shot=store.get(SHOT,shotId);ObjectNode earlier=shot.deepCopy();earlier.remove("id");earlier.remove("revision");earlier.remove("createdAt");earlier.remove("updatedAt");earlier.put("storyTime",1).put("shotNo",1);store.create(SHOT,earlier);
        store.update(SHOT,shotId,revision(shot),shot.deepCopy().put("storyTime",2).put("shotNo",2));
        ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));ObjectNode observedConstraints=expected.path("requiredConstraints").deepCopy();observedConstraints.set("clothing",obj().put("wrong",true));
        ObjectNode request=obj().put("apply",true);request.set("observedConstraints",observedConstraints);int before=store.list(GENERATION_JOB,projectId,null).size();
        ObjectNode reviewed=automaticVisualReview.review(id(frame),request);
        assertThat(text(reviewed.path("automaticRepairJob"),"type")).isEqualTo("KEYFRAME");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(before+1);
    }
    @Test void appliedAutomaticReviewKeepsTheVlmProviderRequestId(){
        ObjectNode frame=generateFrame(),body=obj().put("passed",false).put("reviewer","AUTOMATIC").put("notes","构图错误");body.putObject("qualityReviewResult").putObject("_provider").put("requestId","vlm-request-123");
        workflow.review(KEYFRAME,id(frame),body);
        assertThat(text(store.list(QC_RESULT,projectId,null).getLast(),"providerRequestId")).isEqualTo("vlm-request-123");
    }
    @Test void missingSecondCharacterStateIsRejectedBeforeAnyPaidGenerationJob(){
        ObjectNode second=store.create(ResourceKind.CHARACTER,obj().put("projectId",projectId).put("name","第二角色"));ObjectNode shot=store.get(SHOT,shotId),next=shot.deepCopy();next.withArray("characterIds").add(id(second));store.update(SHOT,shotId,revision(shot),next);
        int jobsBefore=store.list(GENERATION_JOB,projectId,null).size();
        assertThatThrownBy(()->workflow.image(shotId,"KEYFRAME",obj())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lookId");
        assertThat(store.list(GENERATION_JOB,projectId,null)).hasSize(jobsBefore);
    }
    @Test void repeatedVisualAssessmentKeyReusesOneStoredResult(){
        ObjectNode frame=generateFrame();ObjectNode expected=visualExpected.build(frame.path("generationInputSnapshot").path("context"));ObjectNode request=obj().put("requestKey","same-vlm-assessment");request.set("observedConstraints",expected.path("requiredConstraints").deepCopy());
        automaticVisualReview.review(id(frame),request);automaticVisualReview.review(id(frame),request);
        assertThat(store.list(QC_RESULT,projectId,null).stream().filter(q->"same-vlm-assessment".equals(text(q,"assessmentKey"))).count()).isEqualTo(1);
    }
    @Test void defaultAppliedAssessmentKeyIsProtocolVersionedAndIdempotent(){
        ObjectNode frame=generateFrame(),request=obj().put("apply",true);
        automaticVisualReview.review(id(frame),request);automaticVisualReview.review(id(frame),request);
        ObjectNode qc=store.list(QC_RESULT,projectId,null).getLast();
        assertThat(text(qc,"assessmentKey")).startsWith("vlm:keyframe:v"+VisualQualityProtocol.VERSION+":");
        verify(visualReviewer,times(1)).review(any(),any());
    }
    @Test void uncertainVlmFailureIsRecordedAndSameKeyIsNeverResubmitted(){
        ObjectNode frame=generateFrame();doThrow(new ProviderException("REQUEST_TIMEOUT","质检请求超时",null,0,false,true).withRawOutput("{\"partial\":true}")).when(visualReviewer).review(any(),any());ObjectNode request=obj().put("requestKey","uncertain-vlm-once");
        assertThatThrownBy(()->automaticVisualReview.review(id(frame),request)).isInstanceOf(ProviderException.class);
        ObjectNode failure=store.list(QC_RESULT,projectId,null).getLast();assertThat(text(failure,"assessmentKey")).isEqualTo("uncertain-vlm-once");assertThat(failure.path("submissionUncertain").asBoolean()).isTrue();assertThat(text(failure,"providerOutputRaw")).isEqualTo("{\"partial\":true}");
        automaticVisualReview.review(id(frame),request);verify(visualReviewer,times(1)).review(any(),any());
    }
    @Test void uncertainVideoVlmFailureIsRecordedAndSameKeyIsNeverResubmitted(){
        ObjectNode frame=generateFrame();workflow.review(KEYFRAME,id(frame),obj().put("passed",true));workflow.lock(KEYFRAME,id(frame),obj().put("generateVideo",false));workflow.video(id(frame),obj().put("requestKey","video-vlm-failure-source"));worker.tick();worker.tick();worker.tick();ObjectNode take=store.list(VIDEO_TAKE,projectId,shotId).getFirst();
        doThrow(new ProviderException("REQUEST_TIMEOUT","视频质检请求超时",null,0,false,true)).when(videoReviewer).review(any(),anyList());ObjectNode request=obj().put("requestKey","uncertain-video-vlm-once");
        assertThatThrownBy(()->automaticVideoReview.review(id(take),request)).isInstanceOf(ProviderException.class);ObjectNode failure=store.list(QC_RESULT,projectId,null).getLast();assertThat(failure.path("submissionUncertain").asBoolean()).isTrue();
        automaticVideoReview.review(id(take),request);verify(videoReviewer,times(1)).review(any(),anyList());
    }
}
