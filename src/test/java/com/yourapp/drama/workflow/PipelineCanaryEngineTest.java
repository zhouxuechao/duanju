package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineCanaryEngineTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void mockPipelineProducesFourShotsTimelineFfmpegRenderAndSanitizedEvidence() throws Exception {
        Harness harness=harness(new FakeOperations(temporary,true));

        PipelineCanaryEngine.Result result=harness.engine(PipelineCanaryEngine.Checkpoint.NONE).execute();

        assertThat(result.state().path("status").asText()).isEqualTo("SUCCEEDED");
        assertCounts(harness.operations,4,4,2);
        assertThat(result.timeline().videoClips()).isEqualTo(4);
        assertThat(result.timeline().dialogueClips()).isEqualTo(2);
        assertThat(result.timeline().subtitleCues()).isEqualTo(2);
        assertThat(result.timeline().ambienceClips()).isEqualTo(1);
        assertThat(result.timeline().durationMs()).isEqualTo(20_000);
        assertThat(result.render().output()).isRegularFile();
        try(InputStream media=Files.newInputStream(result.render().output())){
            ObjectNode metadata=new MediaProbeService(mapper,ffprobe()).probe(media,".mp4");
            assertThat(metadata.path("width").asInt()).isEqualTo(496);
            assertThat(metadata.path("height").asInt()).isEqualTo(864);
            assertThat(metadata.path("actualDurationMs").asLong()).isBetween(19_000L,21_000L);
            assertThat(metadata.path("frameRate").asDouble()).isEqualTo(24);
            assertThat(metadata.path("hasAudio").asBoolean()).isTrue();
        }
        assertThat(subtitleStreams(result.render().output())).contains("mov_text");
        JsonNode evidence=mapper.readTree(harness.evidence.toFile());
        assertThat(evidence.path("shots")).hasSize(4);
        assertThat(evidence.path("paidRequests")).hasSize(10);
        assertThat(evidence.path("timeline").path("videoClips").asInt()).isEqualTo(4);
        assertThat(evidence.path("qa").path("passed").asBoolean()).isTrue();
        String raw=Files.readString(harness.evidence);
        assertThat(raw).doesNotContain("https://","signature=","Authorization","Bearer","ARK_API_KEY");
        assertThat(evidence.path("artifacts")).allSatisfy(artifact->{
            assertThat(artifact.path("generationProfile").asText()).isEqualTo("TEST");
            assertThat(artifact.has("modelId")).isTrue();
            assertThat(artifact.has("sourceArtifactId")).isTrue();
            assertThat(artifact.has("previousShotId")).isTrue();
            assertThat(artifact.path("continuitySnapshotId").asText()).isNotBlank();
            assertThat(artifact.path("promptVersion").asText()).isEqualTo("pipeline-canary-v1");
        });
    }

    @Test void imageSucceededResumeDoesNotGenerateTheImageAgain() throws Exception {
        assertResume("shot-1:KEYFRAME_SUCCEEDED");
    }

    @Test void videoSubmittedResumeOnlyPollsTheExistingTask() throws Exception {
        assertResume("shot-2:VIDEO_SUBMITTED");
    }

    @Test void videoSucceededResumeDoesNotRegenerateEarlierMedia() throws Exception {
        assertResume("shot-3:VIDEO_SUCCEEDED");
    }

    @Test void mediaReadyResumeStartsWithLocalTimelineWork() throws Exception {
        assertResume("MEDIA_READY");
    }

    @Test void renderFailureResumeCreatesNoNewProviderRequests() throws Exception {
        FakeOperations operations=new FakeOperations(temporary,false);operations.failRenderOnce.set(true);
        Harness harness=harness(operations);

        assertThatThrownBy(()->harness.engine(PipelineCanaryEngine.Checkpoint.NONE).execute()).hasMessageContaining("simulated render failure");
        assertThat(PipelineCanaryState.open(harness.state).snapshot().path("status").asText()).isEqualTo("FAILED");
        assertCounts(operations,4,4,2);

        PipelineCanaryEngine.Result result=harness.engine(PipelineCanaryEngine.Checkpoint.NONE).execute();

        assertThat(result.state().path("status").asText()).isEqualTo("SUCCEEDED");
        assertCounts(operations,4,4,2);
        assertThat(operations.renders).isEqualTo(2);
    }

    @Test void anUncertainSubmittingStepStopsInsteadOfDuplicatingIt() {
        FakeOperations operations=new FakeOperations(temporary,false);Harness harness=harness(operations);
        PipelineCanaryState state=PipelineCanaryState.open(harness.state);
        state.storyReady("project-canary","episode-canary","scene-canary");
        state.beginKeyframe("shot-1");

        assertThatThrownBy(()->harness.engine(PipelineCanaryEngine.Checkpoint.NONE).execute())
                .isInstanceOf(WorkflowException.class).hasMessageContaining("reconciliation");
        assertThat(PipelineCanaryState.open(harness.state).snapshot().path("status").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertCounts(operations,0,0,0);
    }

    @Test void fixtureCarriesPropKnowledgeAndPreviousShotContinuity() {
        PipelineCanaryFixture fixture=PipelineCanaryFixture.standard();

        assertThat(fixture.characters()).hasSize(2);
        assertThat(fixture.locations()).hasSize(1);
        assertThat(fixture.props()).hasSize(1);
        assertThat(fixture.dialogues()).hasSize(2);
        assertThat(fixture.shots()).hasSize(4);
        assertThat(fixture.shots().get(0).propHolderAfter()).isEqualTo("character-a");
        assertThat(fixture.shots().get(2).propHolderAfter()).isEqualTo("character-b");
        assertThat(fixture.shots().get(3).knowledgeAfter()).containsEntry("character-b",true);
        assertThat(fixture.shots().subList(1,4)).allSatisfy(shot->assertThat(shot.previousShotId()).isNotBlank());
    }

    private void assertResume(String stopAfter) throws Exception {
        FakeOperations operations=new FakeOperations(temporary,false);Harness harness=harness(operations);
        PipelineCanaryEngine.Checkpoint crash=checkpoint->{if(stopAfter.equals(checkpoint))throw new SimulatedCrash(checkpoint);};

        assertThatThrownBy(()->harness.engine(crash).execute()).isInstanceOf(SimulatedCrash.class);
        int submitsBefore=operations.videoSubmissions,imagesBefore=operations.images,audioBefore=operations.audio;

        PipelineCanaryEngine.Result result=harness.engine(PipelineCanaryEngine.Checkpoint.NONE).execute();

        assertThat(result.state().path("status").asText()).isEqualTo("SUCCEEDED");
        assertCounts(operations,4,4,2);
        assertThat(operations.images).isGreaterThanOrEqualTo(imagesBefore);
        assertThat(operations.videoSubmissions).isGreaterThanOrEqualTo(submitsBefore);
        assertThat(operations.audio).isGreaterThanOrEqualTo(audioBefore);
    }

    private Harness harness(FakeOperations operations){
        Path state=temporary.resolve("state-"+System.nanoTime()+".json");
        Path vault=temporary.resolve("private-"+System.nanoTime()+".json");
        Path evidence=temporary.resolve("evidence-"+System.nanoTime()+".json");
        PipelineCanaryFixture fixture=PipelineCanaryFixture.standard();
        PipelineCanaryState.start(state,"pipeline-test-"+System.nanoTime(),"test-commit",fixture.shots().stream().map(PipelineCanaryFixture.ShotPlan::shotId).toList());
        PipelineCanaryArtifactVault.start(vault,PipelineCanaryState.open(state).snapshot().path("runId").asText());
        return new Harness(state,vault,evidence,operations,fixture);
    }

    private void assertCounts(FakeOperations operations,int images,int videos,int audio){
        assertThat(operations.images).isEqualTo(images);
        assertThat(operations.videoSubmissions).isEqualTo(videos);
        assertThat(operations.audio).isEqualTo(audio);
    }

    private String subtitleStreams(Path media) throws Exception {
        Process process=new ProcessBuilder(ffprobe(),"-v","error","-select_streams","s","-show_entries","stream=codec_name","-of","default=nw=1",media.toString()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();return output;
    }

    private String ffmpeg(){Path bundled=Path.of("frontend","node_modules","ffmpeg-static","ffmpeg.exe");return Files.isRegularFile(bundled)?bundled.toString():"ffmpeg";}
    private String ffprobe(){Path bundled=Path.of("frontend","node_modules","ffprobe-static","bin","win32","x64","ffprobe.exe");return Files.isRegularFile(bundled)?bundled.toString():"ffprobe";}

    private record Harness(Path state,Path vault,Path evidence,FakeOperations operations,PipelineCanaryFixture fixture){
        PipelineCanaryEngine engine(PipelineCanaryEngine.Checkpoint checkpoint){
            return new PipelineCanaryEngine(fixture,PipelineCanaryState.open(state),PipelineCanaryArtifactVault.open(vault),operations,checkpoint,evidence);
        }
    }

    private static final class SimulatedCrash extends RuntimeException {SimulatedCrash(String checkpoint){super(checkpoint);}}

    private final class FakeOperations implements PipelineCanaryEngine.Operations {
        private final Path directory;private final boolean realRender;private int images,videoSubmissions,videoPolls,audio,timelines,renders,qa;
        private final AtomicBoolean failRenderOnce=new AtomicBoolean();
        FakeOperations(Path directory,boolean realRender){this.directory=directory;this.realRender=realRender;}
        @Override public PipelineCanaryEngine.StoryIds plan(PipelineCanaryFixture fixture){return new PipelineCanaryEngine.StoryIds("project-canary","episode-canary","scene-canary");}
        @Override public PipelineCanaryEngine.KeyframeResult keyframe(PipelineCanaryFixture.ShotPlan shot){images++;return new PipelineCanaryEngine.KeyframeResult("https://mock.volcengine.invalid/keyframe/"+shot.shotId()+"?signature=secret","image-request-"+shot.index(),"keyframe-artifact-"+shot.index(),"doubao-seedream-5-0-260128",true);}
        @Override public PipelineCanaryEngine.VideoSubmission submitVideo(PipelineCanaryFixture.ShotPlan shot,String firstFrame){assertThat(firstFrame).contains(shot.shotId());videoSubmissions++;return new PipelineCanaryEngine.VideoSubmission("video-request-"+shot.index(),"video-task-"+shot.index(),true);}
        @Override public PipelineCanaryEngine.VideoResult pollVideo(PipelineCanaryFixture.ShotPlan shot,String taskId){videoPolls++;assertThat(taskId).isEqualTo("video-task-"+shot.index());return new PipelineCanaryEngine.VideoResult("SUCCEEDED","https://mock.volcengine.invalid/video/"+shot.shotId()+"?signature=secret","video-artifact-"+shot.index(),5_000,"doubao-seedance-2-0-fast-260128",true);}
        @Override public PipelineCanaryEngine.AudioResult tts(PipelineCanaryFixture.Dialogue dialogue){audio++;return new PipelineCanaryEngine.AudioResult("audio-request-"+audio,"audio-artifact-"+audio,"mock-audio",1_000,true);}
        @Override public PipelineCanaryEngine.TimelineResult timeline(PipelineCanaryFixture fixture,ObjectNode state,PipelineCanaryArtifactVault vault){timelines++;return new PipelineCanaryEngine.TimelineResult("timeline-artifact",4,2,2,1,20_000);}
        @Override public PipelineCanaryEngine.RenderResult render(PipelineCanaryEngine.TimelineResult timeline) throws Exception {renders++;if(failRenderOnce.compareAndSet(true,false))throw new IllegalStateException("simulated render failure");Path output=directory.resolve("pipeline-render-"+System.nanoTime()+".mp4");if(realRender)renderMedia(output);else Files.write(output,new byte[]{1,2,3});return new PipelineCanaryEngine.RenderResult("render-artifact",output,496,864,20_000,24,true,true);}
        @Override public PipelineCanaryEngine.QaResult finalQa(PipelineCanaryEngine.RenderResult render,PipelineCanaryEngine.TimelineResult timeline){qa++;return new PipelineCanaryEngine.QaResult(Files.isRegularFile(render.output())&&timeline.videoClips()==4&&timeline.dialogueClips()==2&&render.hasAudio()&&render.subtitlePresent(),List.of());}
        private void renderMedia(Path output) throws Exception {
            Path srt=directory.resolve("pipeline-"+System.nanoTime()+".srt");Files.writeString(srt,"1\n00:00:05,500 --> 00:00:07,500\n发生什么事了？\n\n2\n00:00:15,500 --> 00:00:17,500\n原来消息是真的。\n",StandardCharsets.UTF_8);
            Process process=new ProcessBuilder(ffmpeg(),"-y","-f","lavfi","-i","color=c=0x283747:s=496x864:r=24:d=20","-f","lavfi","-i","sine=frequency=220:sample_rate=48000:duration=20","-f","srt","-i",srt.toAbsolutePath().toString(),"-map","0:v","-map","1:a","-map","2:s","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-c:a","aac","-c:s","mov_text",output.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }
    }
}
