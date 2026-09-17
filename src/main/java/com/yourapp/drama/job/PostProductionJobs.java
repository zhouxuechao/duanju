package com.yourapp.drama.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.model.voice.VoiceGenerator;
import com.yourapp.drama.persistence.*;
import com.yourapp.drama.production.ProductionService;
import com.yourapp.drama.production.DirectorSceneReview;
import com.yourapp.drama.storage.MediaStorage;
import com.yourapp.drama.workflow.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

@Service
public class PostProductionJobs {
    private final DocumentStore store;private final ObjectMapper mapper;private final JobService jobs;private final VoiceGenerator voice;private final MediaStorage storage;private final ProductionService production;private final WorkflowService workflow;private final String ffmpeg;
    public PostProductionJobs(DocumentStore store,ObjectMapper mapper,JobService jobs,VoiceGenerator voice,MediaStorage storage,ProductionService production,WorkflowService workflow,@Value("${drama.render.ffmpeg:ffmpeg}")String ffmpeg){this.store=store;this.mapper=mapper;this.jobs=jobs;this.voice=voice;this.storage=storage;this.production=production;this.workflow=workflow;this.ffmpeg=ffmpeg;}
    public void process(ObjectNode job){switch(text(job,"type")){case "TTS"->tts(job);case "TIMELINE"->store.transaction(()->{timeline(job);return null;});case "RENDER"->render(job);default->throw new WorkflowException("UNSUPPORTED_JOB","不支持的后期任务："+text(job,"type"));}}
    private void tts(ObjectNode job){JsonNode in=job.path("inputSnapshot");VoiceGenerator.VoiceResult result=voice.generate(new VoiceGenerator.VoiceRequest(required(in,"dialogueLineId"),required(in,"speechText"),text(in,"providerVoiceId"),in.path("dialect").asText("MANDARIN"),in.path("speed").asDouble(.95),voiceOptions(in)));
        jobs.mutate(id(job),j->j.put("providerRequestId",result.requestId()).put("model",result.model()).put("simulated",result.simulated()));
        boolean archiveFromProvider=!result.simulated()&&result.providerUrl()!=null&&!result.providerUrl().isBlank();
        String finalArchive="";
        if(!archiveFromProvider){String ext=result.contentType().contains("wav")?".wav":".mp3",key="audio/"+required(in,"dialogueLineId")+"/"+id(job)+ext;try(InputStream source=new ByteArrayInputStream(result.content())){finalArchive=storage.put(key,source,result.contentType());}catch(IOException e){throw new UncheckedIOException(e);}}
        String archiveUrl=finalArchive;store.transaction(()->{ObjectNode clip=obj().put("projectId",project(job)).put("shotId",required(job,"shotId")).put("dialogueLineId",required(in,"dialogueLineId")).put("generationJobId",id(job)).put("provider",result.provider()).put("model",result.model()).put("providerRequestId",result.requestId()).put("providerStatus","SUCCEEDED").put("duration",result.durationSeconds()).put("selected",false).put("locked",false).put("simulated",result.simulated()).put("archiveStatus",archiveFromProvider?"PENDING":"SUCCEEDED");if(!archiveUrl.isBlank())clip.put("archiveUrl",archiveUrl);if(result.providerUrl()!=null&&!result.providerUrl().isBlank())clip.put("providerUrl",result.providerUrl());ObjectNode saved=store.create(AUDIO_CLIP,clip);if(archiveFromProvider)jobs.enqueue(project(job),required(job,"shotId"),"ARCHIVE",obj().put("targetKind","audio-clips").put("targetId",id(saved)).put("providerUrl",result.providerUrl()).put("simulated",false),"archive:audio:"+id(saved));jobs.succeed(id(job),obj().put("audioClipId",id(saved)).put("simulated",result.simulated()).put("archivePending",archiveFromProvider));return null;});}
    private Map<String,Object> voiceOptions(JsonNode input){
        Map<String,Object> options=new LinkedHashMap<>();if(input.path("options").isObject())input.path("options").fields().forEachRemaining(e->options.put(e.getKey(),mapper.convertValue(e.getValue(),Object.class)));
        String url=text(input.path("voiceProfile"),"referenceAudioUrl");if(url.isBlank())return options;
        if(!url.startsWith("/api/media/"))throw new WorkflowException("VOICE_REFERENCE_INVALID","声音参考只能使用本站已归档的 /api/media/ 音频");
        try(InputStream source=storage.open(url.substring("/api/media/".length()))){byte[] bytes=source.readNBytes(10_000_001);if(bytes.length>10_000_000)throw new WorkflowException("VOICE_REFERENCE_TOO_LARGE","声音参考不能超过 10MB");options.put("referenceAudioData",Base64.getEncoder().encodeToString(bytes));return options;}catch(IOException e){throw new WorkflowException("VOICE_REFERENCE_UNREADABLE","声音参考文件无法读取");}
    }
    private void timeline(ObjectNode job){
        JsonNode in=job.path("inputSnapshot");ObjectNode episode=store.get(EPISODE,required(in,"episodeId"));
        List<ObjectNode> scenes=new ArrayList<>(store.list(SCENE,project(episode),id(episode)));scenes.sort(Comparator.comparingInt(n->n.path("sceneNo").asInt(Integer.MAX_VALUE)));
        ArrayNode directorReviews=JsonNodeFactory.instance.arrayNode();List<ObjectNode> orderedShots=new ArrayList<>();for(ObjectNode scene:scenes)if(!scene.path("stale").asBoolean()){List<ObjectNode> shots=new ArrayList<>(store.list(SHOT,project(episode),id(scene)).stream().filter(s->!s.path("stale").asBoolean()).toList());shots.sort(Comparator.comparingInt(n->n.path("shotNo").asInt(Integer.MAX_VALUE)));
            if(!shots.isEmpty()&&shots.getFirst().has("directorPlanVersion")){ObjectNode plan=obj();plan.set("directorPlan",shots.getFirst().path("directorPlanSnapshot").deepCopy());ArrayNode beats=plan.putArray("dramaticBeats"),plannedShots=plan.putArray("shots");Set<String> seen=new LinkedHashSet<>();for(ObjectNode shot:shots){String beatId=text(shot,"beatId");if(seen.add(beatId))beats.add(shot.path("dramaticBeatSnapshot").deepCopy());plannedShots.add(shot.deepCopy());}ObjectNode review=new DirectorSceneReview(mapper).review(plan).put("sceneId",id(scene));if(!review.path("passed").asBoolean())throw new WorkflowException("DIRECTOR_SCENE_REVIEW_FAILED","场景镜头语言验收未通过："+review.path("failureCodes"));directorReviews.add(review);}
            orderedShots.addAll(shots);}
        if(orderedShots.isEmpty())throw new WorkflowException("SHOTS_REQUIRED","此集尚无镜头");
        ObjectNode timelineDraft=obj().put("projectId",project(episode)).put("episodeId",id(episode)).put("name",episode.path("name").asText("Episode")+" 时间线").put("version",store.list(TIMELINE,project(episode),id(episode)).size()+1).put("generationJobId",id(job)).put("status","READY").put("subtitleMode",in.path("subtitleMode").asText("SIDECAR")).put("locked",false);
        timelineDraft.set("directorReviews",directorReviews);
        if(in.path("soundDesign").isObject())timelineDraft.set("soundDesign",in.path("soundDesign").deepCopy());
        ObjectNode timeline=store.create(TIMELINE,timelineDraft);long cursor=0;ArrayNode subtitleCues=JsonNodeFactory.instance.arrayNode();
        for(ObjectNode shot:orderedShots){
            ObjectNode take=selected(VIDEO_TAKE,project(shot),id(shot),null);
            if(take==null||!"PASSED".equals(text(take,"qcStatus")))throw new WorkflowException("LOCKED_TAKE_REQUIRED","镜头 "+id(shot)+" 缺少已质检并采用的视频");
            checkTakeReferences(take);
            long providerDuration=Math.round(shot.path("duration").asDouble()*1000);if(providerDuration<2000||providerDuration>5000)throw new WorkflowException("SHOT_DURATION_INVALID","镜头 "+id(shot)+" 时长必须在 2～5 秒");
            long duration=Math.round(shot.path("editDuration").asDouble(shot.path("duration").asDouble())*1000);if(duration<1250||duration>providerDuration)throw new WorkflowException("EDIT_DURATION_INVALID","剪辑时长必须在 1.25 秒至实际生成视频时长之间");
            ObjectNode videoItem=obj().put("projectId",project(shot)).put("timelineId",id(timeline)).put("shotId",id(shot)).put("videoTakeId",id(take)).put("track","VIDEO").put("startMs",cursor).put("durationMs",duration).put("sourceUrl",requiredArchive(take)).put("locked",true).put("qcPassed",true);
            ObjectNode keyframe=store.get(KEYFRAME,required(take,"sourceKeyframeId"));videoItem.set("assetViewIds",keyframe.path("assetViewIds").deepCopy());store.create(TIMELINE_ITEM,videoItem);
            List<ObjectNode> lines=new ArrayList<>(store.list(DIALOGUE_LINE,project(shot),id(shot)));lines.sort(Comparator.comparingLong(l->l.path("startMs").asLong(Long.MAX_VALUE)));
            long previousEnd=0;
            for(ObjectNode line:lines){
                if(!line.has("startMs")||!line.has("endMs"))throw new WorkflowException("DIALOGUE_TIMING_REQUIRED","对白“"+text(line,"displayText")+"”缺少 startMs/endMs，不能使用默认时段");
                long start=line.path("startMs").asLong(-1),end=line.path("endMs").asLong(-1);if(start<0||end<=start||start<previousEnd||end>duration)throw new WorkflowException("DIALOGUE_TIMING_INVALID","对白“"+text(line,"displayText")+"”的时间必须按镜内毫秒顺序落在 0～"+duration+"ms");
                ObjectNode clip=selected(AUDIO_CLIP,project(shot),id(shot),text(line,"id"));if(clip==null)throw new WorkflowException("LOCKED_AUDIO_REQUIRED","对白尚未采用配音："+text(line,"displayText"));
                long audioDuration=Math.round(clip.path("duration").asDouble(-1)*1000);if(audioDuration<=0)throw new WorkflowException("AUDIO_DURATION_REQUIRED","配音没有提供实测时长："+text(line,"displayText"));if(start+audioDuration>end)throw new WorkflowException("DIALOGUE_AUDIO_OVERRUN","对白“"+text(line,"displayText")+"”配音实测 "+audioDuration+"ms，超过剧本时段 "+(end-start)+"ms");
                long absoluteStart=cursor+start;ObjectNode dialogueItem=obj().put("projectId",project(shot)).put("timelineId",id(timeline)).put("shotId",id(shot)).put("audioClipId",id(clip)).put("dialogueLineId",id(line)).put("track","DIALOGUE").put("startMs",absoluteStart).put("durationMs",audioDuration).put("sourceUrl",requiredArchive(clip)).put("locked",true).put("qcPassed",true);store.create(TIMELINE_ITEM,dialogueItem);
                subtitleCues.add(obj().put("dialogueId",id(line)).put("displayText",required(line,"displayText")).put("startMs",absoluteStart).put("audioDurationMs",audioDuration));previousEnd=end;
            }
            cursor+=duration;
        }
        if(in.path("soundItems").isArray())for(JsonNode sound:in.path("soundItems")){
            String track=required(sound,"track").toUpperCase(Locale.ROOT),source=required(sound,"sourceUrl");if(!Set.of("AMBIENCE","SFX","BGM").contains(track))throw new WorkflowException("SOUND_TRACK_INVALID","声音轨只支持 AMBIENCE、SFX 或 BGM");if(!source.startsWith("/api/media/"))throw new WorkflowException("SOUND_ARCHIVE_REQUIRED","声音素材必须先上传并归档");long start=sound.path("startMs").asLong(-1),duration=sound.path("durationMs").asLong(-1);if(start<0||duration<=0||start+duration>cursor)throw new WorkflowException("SOUND_TIMING_INVALID","声音素材时间必须落在成片范围内");double volume=sound.path("volume").asDouble(track.equals("BGM")?.16:.55);if(volume<0||volume>4)throw new WorkflowException("SOUND_VOLUME_INVALID","声音音量必须在 0 至 4 之间");store.create(TIMELINE_ITEM,obj().put("projectId",project(episode)).put("timelineId",id(timeline)).put("track",track).put("startMs",start).put("durationMs",duration).put("sourceUrl",source).put("volume",volume).put("locked",true).put("qcPassed",true));
        }
        JsonNode subtitle=production.subtitle(obj().set("dialogues",subtitleCues));ObjectNode latest=store.get(TIMELINE,id(timeline)),next=latest.deepCopy().put("durationMs",cursor).put("srt",subtitle.path("srt").asText()).put("cueCount",subtitle.path("cueCount").asInt());ObjectNode saved=store.update(TIMELINE,id(timeline),revision(latest),next);jobs.succeed(id(job),obj().put("timelineId",id(saved)));
    }
    private void checkTakeReferences(ObjectNode take){
        ObjectNode keyframe=store.get(KEYFRAME,required(take,"sourceKeyframeId"));if(keyframe.path("stale").asBoolean()||!keyframe.path("locked").asBoolean()||!keyframe.path("selected").asBoolean()||!"PASSED".equals(text(keyframe,"qcStatus")))throw new WorkflowException("KEYFRAME_NOT_APPROVED","视频引用的关键帧已经失效或未锁定");
        ObjectNode snapshot=obj();snapshot.set("assetViewIds",keyframe.path("assetViewIds").deepCopy());workflow.checkReferenceSnapshot(snapshot);
    }
    private void render(ObjectNode job){JsonNode in=job.path("inputSnapshot");ObjectNode timeline=store.get(TIMELINE,required(in,"timelineId"));checkTimelineReferences(timeline);if(!timeline.path("locked").asBoolean())throw new WorkflowException("TIMELINE_NOT_LOCKED","时间线未锁定");Path temp=null;try{temp=Files.createTempDirectory("drama-render-");ArrayNode planned=JsonNodeFactory.instance.arrayNode();int index=0;for(ObjectNode item:store.list(TIMELINE_ITEM,project(timeline),id(timeline))){String track=required(item,"track");String ext=track.equals("VIDEO")?".mp4":".audio";Path staged=temp.resolve("input-"+(index++)+ext);stage(required(item,"sourceUrl"),staged);ObjectNode p=item.deepCopy();p.put("path",staged.toAbsolutePath().toString());planned.add(p);}String mode=in.path("subtitleMode").asText(timeline.path("subtitleMode").asText("SIDECAR"));Path srt=temp.resolve("subtitles.srt");String srtText=timeline.path("srt").asText("");if(!srtText.isBlank())Files.writeString(srt,srtText,StandardCharsets.UTF_8);Path output=temp.resolve("result.mp4");ObjectNode request=obj().put("outputPath",output.toAbsolutePath().toString()).put("subtitleMode",mode).put("quality",in.path("quality").asText("PREVIEW"));request.set("items",planned);if(mode.equals("BURN_IN"))request.put("subtitlePath",srt.toAbsolutePath().toString());JsonNode plan=production.planTimeline(request);if(!plan.path("passed").asBoolean())throw new WorkflowException("TIMELINE_INVALID","时间线校验未通过："+plan.path("risks"));List<String> args=new ArrayList<>();plan.path("arguments").forEach(n->args.add(n.asText()));args.set(0,ffmpeg);Path log=temp.resolve("ffmpeg.log");Process process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!process.waitFor(20,TimeUnit.MINUTES)){process.destroyForcibly();throw new WorkflowException("RENDER_TIMEOUT","渲染超过 20 分钟已停止");}if(process.exitValue()!=0||!Files.isRegularFile(output)||Files.size(output)<1024){String detail=Files.exists(log)?tail(log,4000):"";throw new WorkflowException("RENDER_FAILED","FFmpeg 渲染失败："+detail);}String quality=in.path("quality").asText("PREVIEW").toLowerCase(Locale.ROOT),key="renders/"+id(timeline)+"/"+quality+"-"+id(job)+".mp4",url;try(InputStream media=Files.newInputStream(output)){url=storage.put(key,media,"video/mp4");}String subtitleUrl="";if(mode.equals("SIDECAR")&&!srtText.isBlank())try(InputStream text=Files.newInputStream(srt)){subtitleUrl=storage.put("renders/"+id(timeline)+"/"+quality+"-"+id(job)+".srt",text,"application/x-subrip");}String finalSubtitle=subtitleUrl,finalUrl=url;store.transaction(()->{ObjectNode latest=store.getForUpdate(TIMELINE,id(timeline));ObjectNode next=latest.deepCopy().put(quality+"Url",finalUrl).put("lastRenderJobId",id(job));if(!finalSubtitle.isBlank())next.put(quality+"SubtitleUrl",finalSubtitle);store.update(TIMELINE,id(timeline),revision(latest),next);ObjectNode result=obj().put("videoUrl",finalUrl).put("quality",quality.toUpperCase(Locale.ROOT));if(!finalSubtitle.isBlank())result.put("subtitleUrl",finalSubtitle);jobs.succeed(id(job),result);return null;});}catch(IOException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new WorkflowException("RENDER_IO","渲染执行失败："+e.getClass().getSimpleName());}finally{if(temp!=null)deleteTemp(temp);}}
    private void checkTimelineReferences(ObjectNode timeline){
        if(timeline.path("stale").asBoolean())throw new WorkflowException("STALE_TIMELINE","时间线依赖的剧本版本已经修改");
        for(ObjectNode item:store.list(TIMELINE_ITEM,project(timeline),id(timeline)))if("VIDEO".equals(text(item,"track"))){
            JsonNode snapshot=item.path("assetViewIds");if(!snapshot.isArray()||snapshot.isEmpty())throw new WorkflowException("ASSET_REFERENCES_REQUIRED","时间线缺少镜头素材视图版本快照");
            workflow.checkReferenceSnapshot(obj().set("assetViewIds",snapshot.deepCopy()));
            ObjectNode take=store.get(VIDEO_TAKE,required(item,"videoTakeId"));checkTakeReferences(take);
        }
    }
    private ObjectNode selected(ResourceKind kind,String projectId,String parentId,String dialogueId){for(ObjectNode item:store.list(kind,projectId,parentId))if(item.path("selected").asBoolean()&&item.path("locked").asBoolean()&&(dialogueId==null||dialogueId.equals(text(item,"dialogueLineId"))))return item;return null;}
    private String requiredArchive(ObjectNode asset){String url=text(asset,"archiveUrl");if(url.isBlank()&&!asset.path("simulated").asBoolean())throw new WorkflowException("ARCHIVE_REQUIRED","渲染前必须等待归档完成");if(url.isBlank())url=asset.path("previewUrl").asText("/demo/take.mp4");return url;}
    private void stage(String url,Path target)throws IOException{try(InputStream source=url.startsWith("/api/media/")?storage.open(url.substring("/api/media/".length())):url.startsWith("/demo/")?new ClassPathResource("static"+url).getInputStream():null){if(source==null)throw new WorkflowException("UNSAFE_MEDIA_SOURCE","渲染只使用已归档的本地/S3 媒体");Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);}}
    private String tail(Path file,int max)throws IOException{byte[] all=Files.readAllBytes(file);return new String(all,Math.max(0,all.length-max),Math.min(max,all.length),StandardCharsets.UTF_8).replaceAll("[\\r\\n]+"," ");}
    private void deleteTemp(Path root){try{Path base=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(),target=root.toAbsolutePath().normalize();if(!target.getParent().equals(base)||!target.getFileName().toString().startsWith("drama-render-"))return;try(var walk=Files.walk(target)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}catch(IOException ignored){}}
}
