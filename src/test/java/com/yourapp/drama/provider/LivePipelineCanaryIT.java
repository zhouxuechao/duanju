package com.yourapp.drama.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.model.ImageGenerator;
import com.yourapp.drama.model.VideoGenerator;
import com.yourapp.drama.model.voice.VoiceGenerator;
import com.yourapp.drama.production.ProviderCapabilityRegistry;
import com.yourapp.drama.provider.audio.SeedAudioProperties;
import com.yourapp.drama.provider.audio.SeedAudioVoiceGenerator;
import com.yourapp.drama.provider.volcengine.ArkHttpClient;
import com.yourapp.drama.provider.volcengine.VolcengineImageGenerator;
import com.yourapp.drama.provider.volcengine.VolcengineProperties;
import com.yourapp.drama.provider.volcengine.VolcengineVideoGenerator;
import com.yourapp.drama.workflow.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled Phase B entry point. The normal test suite never executes billable work. */
class LivePipelineCanaryIT {
    @Test
    @EnabledIfEnvironmentVariable(named="RUN_LIVE_PIPELINE_CANARY",matches="(?i)true")
    void runsOrResumesTheFourShotPipelineWithoutAutomaticRetries() throws Exception {
        LiveCanaryPlan.pipeline().requireLiveFlags(System.getenv());ObjectMapper mapper=new ObjectMapper();
        new PipelineCanaryCapabilityGate(mapper).requireReady(Path.of("target","provider-capability-snapshot.json"));
        ObjectNode preflight=LiveCanaryPlan.pipeline().validateLive(System.getenv(),List.of());assertThat(preflight.path("ready").asBoolean()).as(preflight.path("blocking").toString()).isTrue();
        PipelineCanaryFixture fixture=PipelineCanaryFixture.standard();Path root=Path.of("target","live-canary"),statePath=root.resolve("pipeline-canary-state.json"),vaultPath=root.resolve("private").resolve("pipeline-canary-runtime.json"),evidencePath=root.resolve("pipeline-canary-evidence.json");
        PipelineCanaryState state;PipelineCanaryArtifactVault vault;
        if(Files.exists(statePath)){state=PipelineCanaryState.open(statePath);vault=PipelineCanaryArtifactVault.open(vaultPath);}
        else{String runId=optional("DRAMA_TEST_RUN_ID","pipeline-canary-"+UUID.randomUUID());state=PipelineCanaryState.start(statePath,runId,gitCommit(),fixture.shots().stream().map(PipelineCanaryFixture.ShotPlan::shotId).toList());vault=PipelineCanaryArtifactVault.start(vaultPath,runId);}

        VolcengineProperties properties=volcengineProperties();ProviderCapabilityRegistry capabilities=new ProviderCapabilityRegistry(properties.getVideoModel(),properties.getImageModel(),properties.getVideoResolution(),properties.getImageSize(),properties.getVideoMinDuration(),properties.getVideoMaxDuration(),properties.getVideoDurationStep());
        ArkHttpClient client=new ArkHttpClient(mapper,properties);ImageGenerator image=new VolcengineImageGenerator(client,properties);VideoGenerator video=new VolcengineVideoGenerator(client,properties,capabilities);VoiceGenerator voice=new SeedAudioVoiceGenerator(audioProperties(),mapper);
        TestBudgetGuard budget=pipelineBudget();Path runDirectory=root.resolve(state.snapshot().path("runId").asText());Files.createDirectories(runDirectory);
        LiveOperations operations=new LiveOperations(mapper,image,video,voice,budget,state.snapshot().path("runId").asText(),vault,runDirectory,required("FFMPEG_PATH"),required("FFPROBE_PATH"));

        PipelineCanaryEngine.Result result=new PipelineCanaryEngine(fixture,state,vault,operations,PipelineCanaryEngine.Checkpoint.NONE,evidencePath).execute();

        assertThat(result.state().path("status").asText()).isEqualTo("SUCCEEDED");assertThat(result.qa().passed()).isTrue();assertThat(result.render().output()).isRegularFile();
    }

    private static final class LiveOperations implements PipelineCanaryEngine.Operations {
        private final ObjectMapper mapper;private final ImageGenerator image;private final VideoGenerator video;private final VoiceGenerator voice;private final TestBudgetGuard budget;private final String runId;private final PipelineCanaryArtifactVault vault;private final Path directory;private final String ffmpeg,ffprobe;
        LiveOperations(ObjectMapper mapper,ImageGenerator image,VideoGenerator video,VoiceGenerator voice,TestBudgetGuard budget,String runId,PipelineCanaryArtifactVault vault,Path directory,String ffmpeg,String ffprobe){this.mapper=mapper;this.image=image;this.video=video;this.voice=voice;this.budget=budget;this.runId=runId;this.vault=vault;this.directory=directory;this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;}
        @Override public PipelineCanaryEngine.StoryIds plan(PipelineCanaryFixture fixture){return new PipelineCanaryEngine.StoryIds("canary-project-"+runId,"canary-episode-1","canary-scene-1");}
        @Override public PipelineCanaryEngine.KeyframeResult keyframe(PipelineCanaryFixture.ShotPlan shot){
            ObjectNode reservation=budgetInput("IMAGE",perRequest("PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY",4));budget.reserve("KEYFRAME",reservation);
            try{
                List<String> references=shot.previousShotId().isBlank()?List.of():List.of(vault.require(shot.previousShotId()+".keyframeProviderUrl"));
                ImageGenerator.ImageResult result=image.generate(new ImageGenerator.ImageRequest(ProviderCapabilityRegistry.SEEDREAM_50,keyframePrompt(shot),references,Map.of("size","2K","watermark",false)));
                budget.settle("KEYFRAME",reservation,false);return new PipelineCanaryEngine.KeyframeResult(result.providerUrl(),result.requestId(),"keyframe-"+shot.shotId(),result.model(),false);
            }catch(RuntimeException error){budget.settle("KEYFRAME",reservation,true);throw error;}
        }
        @Override public PipelineCanaryEngine.VideoSubmission submitVideo(PipelineCanaryFixture.ShotPlan shot,String firstFrameProviderUrl){
            ObjectNode reservation=budgetInput("VIDEO",perRequest("PIPELINE_TEST_VIDEO_ESTIMATED_COST_CNY",4));budget.reserve("VIDEO",reservation);
            try{
                VideoGenerator.VideoRequest request=new VideoGenerator.VideoRequest(ProviderCapabilityRegistry.SEEDANCE_20_FAST,videoPrompt(shot),firstFrameProviderUrl,List.of(),Map.of("duration",5,"resolution","480p","watermark",false,"generate_audio",false));
                VideoGenerator.Submission submitted=video.submit(request);budget.settle("VIDEO",reservation,false);return new PipelineCanaryEngine.VideoSubmission(submitted.requestId(),submitted.taskId(),false);
            }catch(RuntimeException error){budget.settle("VIDEO",reservation,true);throw error;}
        }
        @Override public PipelineCanaryEngine.VideoResult pollVideo(PipelineCanaryFixture.ShotPlan shot,String taskId) throws Exception {
            Instant deadline=Instant.now().plus(Duration.ofMinutes(20));VideoGenerator.VideoTask task;
            do{task=video.poll(taskId);if(task.status()==VideoGenerator.Status.SUCCEEDED)break;if(List.of(VideoGenerator.Status.FAILED,VideoGenerator.Status.CANCELLED,VideoGenerator.Status.EXPIRED).contains(task.status()))return new PipelineCanaryEngine.VideoResult(task.status().name(),"","video-"+shot.shotId(),0,ProviderCapabilityRegistry.SEEDANCE_20_FAST,false);Thread.sleep(10_000);}while(Instant.now().isBefore(deadline));
            if(task.status()!=VideoGenerator.Status.SUCCEEDED)throw new com.yourapp.drama.model.ProviderException("VIDEO_POLL_TIMEOUT","Video task did not reach a final state; reconcile the existing task ID",task.requestId(),0,false,true);
            Path videoPath=directory.resolve("video-"+shot.index()+".mp4");download(task.providerUrl(),videoPath,200_000_000);Path imagePath=directory.resolve("keyframe-"+shot.index()+".png");if(!Files.isRegularFile(imagePath))download(vault.require(shot.shotId()+".keyframeProviderUrl"),imagePath,40_000_000);
            ObjectNode metadata;try(InputStream stream=Files.newInputStream(videoPath)){metadata=new MediaProbeService(mapper,ffprobe).probe(stream,".mp4");}
            if(metadata.path("width").asInt()!=496||metadata.path("height").asInt()!=864||metadata.path("hasAudio").asBoolean())throw new WorkflowException("PIPELINE_CANARY_VIDEO_INVALID","Video media does not match the live-verified 496x864 silent profile");
            return new PipelineCanaryEngine.VideoResult("SUCCEEDED",task.providerUrl(),"video-"+shot.shotId(),metadata.path("actualDurationMs").asLong(),ProviderCapabilityRegistry.SEEDANCE_20_FAST,false);
        }
        @Override public PipelineCanaryEngine.AudioResult tts(PipelineCanaryFixture.Dialogue dialogue) throws Exception {
            ObjectNode reservation=budgetInput("AUDIO",0);budget.reserve("TTS",reservation);
            try{
                VoiceGenerator.VoiceResult result=voice.generate(new VoiceGenerator.VoiceRequest(dialogue.id(),dialogue.spokenText(),required("CANARY_TTS_VOICE_ID"),"普通话",1,"普通话自然对白，语气克制，保持同一角色音色。",Map.of()));
                String suffix=result.contentType().contains("mpeg")?".mp3":".wav";Files.write(directory.resolve("audio-"+dialogue.shotId()+suffix),result.content());budget.settle("TTS",reservation,false);
                return new PipelineCanaryEngine.AudioResult(result.requestId(),"audio-"+dialogue.id(),result.model(),Math.round(result.durationSeconds()*1000),false);
            }catch(RuntimeException error){budget.settle("TTS",reservation,true);throw error;}
        }
        @Override public PipelineCanaryEngine.TimelineResult timeline(PipelineCanaryFixture fixture,ObjectNode state,PipelineCanaryArtifactVault vault) throws Exception {
            ArrayNode clips=mapper.createArrayNode();for(PipelineCanaryFixture.ShotPlan shot:fixture.shots()){Path media=directory.resolve("video-"+shot.index()+".mp4");if(!Files.isRegularFile(media))download(vault.require(shot.shotId()+".videoProviderUrl"),media,200_000_000);clips.add(obj().put("shotId",shot.shotId()).put("track","VIDEO").put("startMs",(shot.index()-1)*5000L).put("durationMs",5000).put("artifactId","video-"+shot.shotId()));}
            ObjectNode timeline=obj().put("artifactId","pipeline-timeline").put("durationMs",20_000).put("videoClips",4).put("dialogueClips",2).put("subtitleCues",2).put("ambienceClips",1);timeline.set("clips",clips);mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("timeline.json").toFile(),timeline);Files.writeString(directory.resolve("subtitles.srt"),"1\n00:00:05,500 --> 00:00:07,500\n发生什么事了？\n\n2\n00:00:15,500 --> 00:00:17,500\n原来消息是真的。\n",StandardCharsets.UTF_8);
            return new PipelineCanaryEngine.TimelineResult("pipeline-timeline",4,2,2,1,20_000);
        }
        @Override public PipelineCanaryEngine.RenderResult render(PipelineCanaryEngine.TimelineResult timeline) throws Exception {
            Path concat=directory.resolve("videos.txt");StringBuilder inputs=new StringBuilder();for(int i=1;i<=4;i++)inputs.append("file '").append(directory.resolve("video-"+i+".mp4").toAbsolutePath().toString().replace("'","''").replace('\\','/')).append("'\n");Files.writeString(concat,inputs,StandardCharsets.UTF_8);
            Path picture=directory.resolve("picture.mp4"),output=directory.resolve("pipeline-final.mp4"),srt=directory.resolve("subtitles.srt");run(List.of(ffmpeg,"-y","-f","concat","-safe","0","-i",concat.toString(),"-vf","scale=496:864,fps=24","-t","20","-an","-c:v","libx264","-pix_fmt","yuv420p",picture.toString()));
            Path firstAudio=findAudio("shot-2"),secondAudio=findAudio("shot-4");
            run(List.of(ffmpeg,"-y","-i",picture.toString(),"-f","lavfi","-t","20","-i","anoisesrc=color=pink:amplitude=0.01:sample_rate=48000","-i",firstAudio.toString(),"-i",secondAudio.toString(),"-f","srt","-i",srt.toString(),"-filter_complex","[1:a]volume=0.08[a0];[2:a]adelay=5500|5500[a1];[3:a]adelay=15500|15500[a2];[a0][a1][a2]amix=inputs=3:duration=longest:dropout_transition=0[a]","-map","0:v","-map","[a]","-map","4:s","-c:v","copy","-c:a","aac","-c:s","mov_text","-t","20",output.toString()));
            ObjectNode metadata;try(InputStream stream=Files.newInputStream(output)){metadata=new MediaProbeService(mapper,ffprobe).probe(stream,".mp4");}
            return new PipelineCanaryEngine.RenderResult("pipeline-render",output,metadata.path("width").asInt(),metadata.path("height").asInt(),metadata.path("actualDurationMs").asLong(),metadata.path("frameRate").asDouble(),metadata.path("hasAudio").asBoolean(),hasSubtitle(output));
        }
        @Override public PipelineCanaryEngine.QaResult finalQa(PipelineCanaryEngine.RenderResult render,PipelineCanaryEngine.TimelineResult timeline){List<String> failures=new ArrayList<>();if(!Files.isRegularFile(render.output()))failures.add("VIDEO_MISSING");if(render.width()!=496||render.height()!=864)failures.add("GEOMETRY_MISMATCH");if(render.durationMs()<19_000||render.durationMs()>21_000)failures.add("DURATION_DRIFT");if(render.fps()<23||render.fps()>25)failures.add("FPS_MISMATCH");if(!render.hasAudio())failures.add("AUDIO_MISSING");if(!render.subtitlePresent())failures.add("SUBTITLE_MISSING");if(timeline.videoClips()!=4||timeline.dialogueClips()!=2)failures.add("TIMELINE_INCOMPLETE");return new PipelineCanaryEngine.QaResult(failures.isEmpty(),failures);}
        @Override public boolean live(){return true;}
        private String keyframePrompt(PipelineCanaryFixture.ShotPlan shot){return common(shot)+"\n生成竖屏 9:16 关键帧，现代客厅布局固定，人物脸、服装、发型、年龄和手机外观必须与上一镜一致。";}
        private String videoPrompt(PipelineCanaryFixture.ShotPlan shot){return common(shot)+"\n从输入首帧连续运动 5 秒，不切镜，不改变人物身份、服装、客厅布局或手机外观，不生成文字，不生成声音。";}
        private String common(PipelineCanaryFixture.ShotPlan shot){return "镜头 "+shot.index()+"："+shot.action()+"。机位："+shot.camera()+"。上一镜头："+shot.previousShotId()+"。连续性快照："+shot.continuitySnapshotId()+"。手机持有者从 "+shot.propHolderBefore()+" 到 "+shot.propHolderAfter()+"。角色知识状态："+shot.knowledgeAfter();}
        private ObjectNode budgetInput(String category,double estimate){return obj().put("testRun",true).put("testRunId",runId).put("testPhase","PIPELINE").put("category",category).put("estimatedCost",estimate);}
        private double perRequest(String name,int requests){return Double.parseDouble(required(name))/requests;}
        private Path findAudio(String shotId) throws Exception {try(var files=Files.list(directory)){return files.filter(path->path.getFileName().toString().startsWith("audio-"+shotId)).findFirst().orElseThrow(()->new IllegalStateException("Audio artifact missing for "+shotId));}}
        private boolean hasSubtitle(Path media) throws Exception {Process process=new ProcessBuilder(ffprobe,"-v","error","-select_streams","s","-show_entries","stream=codec_type","-of","csv=p=0",media.toString()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);return process.waitFor(30,TimeUnit.SECONDS)&&process.exitValue()==0&&output.contains("subtitle");}
        private void run(List<String> command) throws Exception {Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();if(!process.waitFor(10,TimeUnit.MINUTES)){process.destroyForcibly();throw new WorkflowException("PIPELINE_CANARY_RENDER_TIMEOUT","FFmpeg timed out");}if(process.exitValue()!=0)throw new WorkflowException("PIPELINE_CANARY_RENDER_FAILED","FFmpeg failed");}
        private void download(String value,Path output,int maxBytes) throws Exception {URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)throw new IllegalArgumentException("Provider media URL is invalid");HttpResponse<InputStream> response=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build().send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());try(InputStream input=response.body()){if(response.statusCode()!=200)throw new IllegalStateException("Provider media download failed: HTTP "+response.statusCode());byte[] bytes=input.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw new IllegalArgumentException("Provider media exceeds the Canary limit");Files.write(output,bytes);}}
    }

    private VolcengineProperties volcengineProperties(){VolcengineProperties value=new VolcengineProperties();value.setApiKey(required("ARK_API_KEY"));value.setBaseUrl(URI.create(optional("ARK_BASE_URL","https://ark.cn-beijing.volces.com/api/v3")));value.setTextModel(optional("ARK_TEXT_MODEL","unused-pipeline-canary"));value.setImageModel(required("ARK_IMAGE_MODEL"));value.setImageSize(required("ARK_IMAGE_SIZE"));value.setVideoModel(required("ARK_VIDEO_MODEL"));value.setVideoResolution(required("ARK_VIDEO_RESOLUTION"));value.setRequestTimeout(Duration.ofMinutes(10));value.validate();return value;}
    private SeedAudioProperties audioProperties(){SeedAudioProperties value=new SeedAudioProperties();value.setApiKey(required("SEED_AUDIO_API_KEY"));value.setModel(optional("SEED_AUDIO_MODEL","seed-audio-1.0"));value.setEndpoint(URI.create(optional("SEED_AUDIO_ENDPOINT","https://openspeech.bytedance.com/api/v3/tts/create")));value.setRequestTimeout(Duration.ofMinutes(5));value.validate();return value;}
    private TestBudgetGuard pipelineBudget(){MockEnvironment env=new MockEnvironment().withProperty("drama.provider.mode","volcengine");for(String key:List.of("PIPELINE_TEST_MAX_COST_CNY","PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS","PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS","PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS","PIPELINE_TEST_MAX_REAL_LLM_REQUESTS","PIPELINE_TEST_MAX_REAL_VLM_REQUESTS"))env.setProperty(key,required(key));return new TestBudgetGuard(env);}
    private String gitCommit() throws Exception {Process process=new ProcessBuilder("git","rev-parse","HEAD").redirectErrorStream(true).start();String value=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();if(!process.waitFor(10,TimeUnit.SECONDS)||process.exitValue()!=0||!value.matches("[a-f0-9]{40}"))throw new IllegalStateException("Git commit cannot be resolved");return value;}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;}
    private static String optional(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
}
