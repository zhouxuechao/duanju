package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static com.yourapp.drama.production.ProductionJson.*;
import static com.yourapp.drama.production.ProductionModels.*;

/** Pure, provider-neutral production operations used by HTTP and workflow adapters. */
@Service
public class ProductionService {
    private final ObjectMapper mapper;
    private final ContinuityEngine continuity;
    private final PromptCompiler prompts;
    private final ProductionStateMachine states;
    private final ProviderCapabilityRegistry capabilities;

    public ProductionService(ObjectMapper mapper, ContinuityEngine continuity, PromptCompiler prompts, ProductionStateMachine states) {
        this(mapper,continuity,prompts,states,new ProviderCapabilityRegistry());
    }
    @Autowired
    public ProductionService(ObjectMapper mapper, ContinuityEngine continuity, PromptCompiler prompts, ProductionStateMachine states,ProviderCapabilityRegistry capabilities) {
        this.mapper = mapper; this.continuity = continuity; this.prompts = prompts; this.states = states;this.capabilities=capabilities;
    }
    public JsonNode planContinuity(JsonNode request) { return value(continuity.plan(request)); }
    public JsonNode applyLockedTake(JsonNode request) { return continuity.applyLockedTake(request); }
    public JsonNode compileImage(JsonNode request) { return value(prompts.compileImage(request)); }
    public String imageCompilerVersion(){return PromptCompiler.IMAGE_COMPILER_VERSION;}
    public JsonNode compileVideo(JsonNode request) { return value(prompts.compileVideo(request)); }
    public JsonNode prepareVideo(JsonNode request) { return prompts.prepareVideo(request); }
    public JsonNode routeVideo(JsonNode request) { return prompts.routeVideo(request); }
    public JsonNode imageCapabilities(){return capabilities.image();}
    public JsonNode videoCapabilities(){return capabilities.video();}
    public JsonNode transitionShot(JsonNode request) { return states.transitionShot(request); }
    public JsonNode transitionJob(JsonNode request) { return states.transitionJob(request); }
    public JsonNode skillCatalog() {
        try (InputStream source = ProductionService.class.getResourceAsStream("/production-skills/catalog.json")) {
            if (source == null) throw new IllegalStateException("生产技能目录未打包");
            return mapper.readTree(source);
        } catch (IOException error) {
            throw new IllegalStateException("生产技能目录无法读取", error);
        }
    }

    public JsonNode soundDesign(JsonNode request) {
        JsonNode scenes=request.path("scenes");
        if(!scenes.isArray())throw new IllegalArgumentException("sound-design 需要 scenes 数组");
        ObjectNode result=mapper.createObjectNode().put("version",1).put("dialoguePriority",true);
        ArrayNode ambience=result.putArray("ambience"),sfx=result.putArray("sfx");
        for(JsonNode scene:scenes){
            String sceneId=text(scene,"id"),name=scene.path("name").asText("场景");
            ambience.add(mapper.createObjectNode().put("sceneId",sceneId).put("description",name+"的稳定环境底噪").put("volume",0.22));
            JsonNode shots=scene.path("shots");
            if(shots.isArray())for(JsonNode shot:shots)if(!shot.path("propIds").isEmpty()||!text(shot,"sfxCue").isBlank())
                sfx.add(mapper.createObjectNode().put("shotId",text(shot,"id")).put("description",text(shot,"sfxCue").isBlank()?"与主要动作同步的轻量拟音":text(shot,"sfxCue")).put("volume",0.55));
        }
        result.set("bgm",mapper.createObjectNode().put("description",request.path("mood").asText("克制、服务叙事的器乐底乐")).put("volume",0.16).put("duckUnderDialogue",true));
        result.put("status","PLAN_READY").putArray("warnings").add("计划不含音频版权授权；导入素材时仍需确认来源");
        return result;
    }

    public JsonNode renderDialect(JsonNode request) {
        String semantic = required(request, "semanticText"),subtitle=required(request,"subtitleText");
        String dialect = text(request, "dialect");
        double strength = request.path("dialectStrength").asDouble(1.0);
        if (strength < 0 || strength > 1) throw new IllegalArgumentException("dialectStrength 必须在 0 至 1 之间");
        ObjectNode result = mapper.createObjectNode().put("semanticText",semantic).put("subtitleText",subtitle).put("dialect", dialect).put("dialectStrength", strength);
        if (strength == 0 || dialect.isBlank()) {
            result.put("spokenText",semantic).put("confidence", 1.0)
                .put("needsHumanCorrection", false).put("status", "READY").putArray("matchedEntryIds");
            return result;
        }
        JsonNode correction = request.path("correction");
        if (correction.path("approved").asBoolean(false)) {
            String spoken=required(correction,"spokenText");
            result.put("spokenText",spoken).put("confidence", 1.0)
                .put("needsHumanCorrection", false).put("status", "HUMAN_CORRECTED");
            result.putArray("matchedEntryIds").add(text(correction, "entryId"));
            return result;
        }
        List<Candidate> candidates = new ArrayList<>();
        JsonNode kb = request.path("knowledgeBase");
        if (kb.isObject()) kb = kb.path("entries");
        if (kb.isArray()) for (JsonNode entry : kb) {
            if (!dialect.equalsIgnoreCase(text(entry, "dialect")) && !dialect.equalsIgnoreCase(text(entry, "locale"))) continue;
            if (!entry.path("approved").asBoolean(false)) continue;
            String source = text(entry, "semanticText");
            double score = similarity(semantic, source);
            if (score >= 0.55 && !text(entry, "spokenText").isBlank()) candidates.add(new Candidate(score, entry));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        ArrayNode suggestions = result.putArray("suggestions");
        for (Candidate candidate : candidates.stream().limit(3).toList()) {
            ObjectNode s = mapper.createObjectNode().put("entryId", text(candidate.entry(), "id"))
                .put("score", candidate.score()).put("semanticText", text(candidate.entry(), "semanticText"))
                .put("spokenText", text(candidate.entry(), "spokenText"));
            suggestions.add(s);
        }
        if (candidates.isEmpty() || candidates.getFirst().score() < 0.8) {
            result.put("spokenText", "").put("confidence", candidates.isEmpty() ? 0 : candidates.getFirst().score())
                .put("needsHumanCorrection", true).put("status", "HUMAN_REVIEW_REQUIRED").putArray("matchedEntryIds");
        } else {
            JsonNode best = candidates.getFirst().entry();
            result.put("spokenText", text(best, "spokenText"))
                .put("confidence", candidates.getFirst().score()).put("needsHumanCorrection", false).put("status", "READY");
            result.putArray("matchedEntryIds").add(text(best, "id"));
        }
        return result;
    }

    public JsonNode subtitle(JsonNode request) {
        JsonNode lines = request.has("dialogues") ? request.path("dialogues") : request.path("cues");
        if (!lines.isArray()) throw new IllegalArgumentException("subtitle 需要 dialogues/cues 数组");
        JsonNode configured=request.path("styleProfile");ObjectNode style=mapper.createObjectNode().put("maxCharsPerLine",configured.path("maxCharsPerLine").asInt(14)).put("maxLines",configured.path("maxLines").asInt(2)).put("maxCps",configured.path("maxCps").asDouble(15)).put("fontSize",configured.path("fontSize").asInt(54)).put("outline",configured.path("outline").asInt(3)).put("shadow",configured.path("shadow").asInt(1)).put("marginV",configured.path("marginV").asInt(180)).put("alignment",configured.path("alignment").asInt(2));
        int maxChars=style.path("maxCharsPerLine").asInt(),maxLines=style.path("maxLines").asInt();double maxCps=style.path("maxCps").asDouble();if(maxChars<4||maxChars>32||maxLines<1||maxLines>2||maxCps<4||maxCps>30)throw new IllegalArgumentException("字幕样式的每行字数、行数或阅读速度范围无效");ArrayNode issues=mapper.createArrayNode();
        List<Cue> cues = new ArrayList<>();
        for (JsonNode line : lines) {
            String display = required(line, "subtitleText");
            long start = timeMs(line, "startMs", "start");
            long end = line.has("endMs") ? timeMs(line, "endMs", "end") : start + timeMs(line, "audioDurationMs", "durationMs");
            if (start < 0 || end <= start) throw new IllegalArgumentException("字幕音频时间必须为非负且 end > start: " + text(line, "dialogueId"));
            String normalized=display.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+"," ").trim(),wrapped=wrapSubtitle(normalized,maxChars,maxLines);double cps=normalized.codePointCount(0,normalized.length())*1000d/(end-start);if(cps>maxCps)issues.add(mapper.createObjectNode().put("code","SUBTITLE_READING_SPEED").put("dialogueId",text(line,"dialogueId")).put("actualCps",Math.round(cps*10)/10d).put("maxCps",maxCps));for(String row:wrapped.split("\\n",-1))if(row.codePointCount(0,row.length())>maxChars)issues.add(mapper.createObjectNode().put("code","SUBTITLE_LINE_TOO_LONG").put("dialogueId",text(line,"dialogueId")).put("maxCharsPerLine",maxChars));
            cues.add(new Cue(text(line, "dialogueId"), wrapped, start, end));
        }
        cues.sort(Comparator.comparingLong(Cue::start));
        StringBuilder srt = new StringBuilder();
        int number = 1;
        for (Cue cue : cues) {
            srt.append(number++).append('\n').append(timestamp(cue.start())).append(" --> ").append(timestamp(cue.end())).append('\n')
                .append(cue.display()).append("\n\n");
        }
        ObjectNode result = mapper.createObjectNode().put("subtitleMode", request.path("burnIn").asBoolean(false) ? "BURN_IN" : "SIDECAR")
            .put("srt", srt.toString()).put("cueCount", cues.size()).put("source", "audio_duration").put("passed",issues.isEmpty());result.set("styleProfile",style);result.set("issues",issues);
        return result;
    }

    public JsonNode planTimeline(JsonNode request) {
        JsonNode items = request.path("items");
        if (!items.isArray() || items.isEmpty()) throw new IllegalArgumentException("timeline 需要至少一个 items");
        List<Risk> risks = new ArrayList<>();
        List<TrackItem> parsed = new ArrayList<>();
        for (JsonNode item : items) {
            String media = text(item, "path");
            if (media.isBlank() || !Path.of(media).isAbsolute()) { error(risks, "MEDIA_PATH_NOT_ABSOLUTE", "items.path", "FFmpeg 输入必须是绝对本地媒体路径"); continue; }
            Path path = Path.of(media).normalize();
            if (!Files.isRegularFile(path)) { error(risks, "MEDIA_NOT_FOUND", media, "媒体不存在或不是普通文件"); continue; }
            long start = item.path("startMs").asLong(0), duration = item.path("durationMs").asLong(0), sourceStart = item.has("sourceInMs")?item.path("sourceInMs").asLong(0):item.path("sourceStartMs").asLong(0);
            if (start < 0 || duration <= 0 || sourceStart < 0) { error(risks, "INVALID_TIMING", media, "startMs/sourceStartMs 必须非负，durationMs 必须大于 0"); continue; }
            String track = text(item, "track").toUpperCase(Locale.ROOT);
            if (!Set.of("VIDEO", "DIALOGUE", "AMBIENCE", "SFX", "BGM").contains(track)) { error(risks, "INVALID_TRACK", "items.track", "仅支持 VIDEO、DIALOGUE、AMBIENCE、SFX、BGM"); continue; }
            boolean qc = item.path("qcPassed").asBoolean(true);
            if (!qc) error(risks, "MEDIA_NOT_APPROVED", media, "时间线只能使用已通过 QC 的成片素材");
            double volume=item.path("volume").asDouble(1),playbackRate=item.path("playbackRate").asDouble(1);
            if (!Double.isFinite(volume) || volume < 0 || volume > 4) { error(risks,"INVALID_VOLUME",media,"volume 必须在 0 至 4 之间"); continue; }
            if(!Double.isFinite(playbackRate)||playbackRate<.9||playbackRate>1.1||("VIDEO".equals(track)&&Math.abs(playbackRate-1)>.0001)){error(risks,"INVALID_PLAYBACK_RATE",media,"音频 playbackRate 必须在 0.9 至 1.1 之间，视频不允许在此阶段变速");continue;}
            String transition=item.path("transition").asText("CUT").toUpperCase(Locale.ROOT);long transitionDuration=item.path("transitionDurationMs").asLong("CROSS_DISSOLVE".equals(transition)?300:0),fadeIn=item.path("fadeInMs").asLong("BGM".equals(track)?500:"AMBIENCE".equals(track)?250:0),fadeOut=item.path("fadeOutMs").asLong("BGM".equals(track)?800:"AMBIENCE".equals(track)?400:0);
            if(!Set.of("CUT","MATCH_CUT","CROSS_DISSOLVE","FADE_TO_BLACK").contains(transition))error(risks,"INVALID_TRANSITION",media,"转场只支持 CUT、MATCH_CUT、CROSS_DISSOLVE、FADE_TO_BLACK");
            if(fadeIn<0||fadeOut<0||fadeIn+fadeOut>=duration)error(risks,"INVALID_AUDIO_FADE",media,"音频淡入淡出必须非负，且总时长要短于素材片段");
            long pauseDuration=EditingEngine.hasOperation(item,EditOperation.PAUSE)?item.path("pauseDurationMs").asLong(-1):0;
            parsed.add(new TrackItem(track, path.toString(), start, sourceStart, duration, volume,playbackRate,transition,transitionDuration,fadeIn,fadeOut,pauseDuration));
        }
        EditingEngine.validate(items,risks);
        for (String track : List.of("VIDEO", "DIALOGUE", "AMBIENCE", "SFX", "BGM")) {
            List<TrackItem> same = parsed.stream().filter(i -> i.track().equals(track)).sorted(Comparator.comparingLong(TrackItem::start)).toList();
            for (int i = 1; i < same.size(); i++) if (same.get(i - 1).start() + same.get(i - 1).duration() > same.get(i).start() && !track.equals("BGM")&&!track.equals("VIDEO"))
                error(risks, "TRACK_OVERLAP", track, "同一条非 BGM 音轨的素材不能重叠");
        }
        List<TrackItem> videoItems=parsed.stream().filter(i->i.track().equals("VIDEO")).sorted(Comparator.comparingLong(TrackItem::start)).toList();
        if(videoItems.isEmpty()) error(risks,"VIDEO_REQUIRED","items","时间线至少需要一段视频");
        long expectedStart=0,previousDuration=0;
        for(int videoIndex=0;videoIndex<videoItems.size();videoIndex++){
            TrackItem item=videoItems.get(videoIndex);long overlap=videoIndex>0&&"CROSS_DISSOLVE".equals(item.transition())?item.transitionDuration():0;
            if(overlap>0&&(overlap<100||overlap>1000||overlap>=Math.min(previousDuration,item.duration())))error(risks,"TRANSITION_DURATION_INVALID","items","叠化时长必须为 100～1000ms 且短于相邻两个镜头");
            long requiredStart=videoIndex==0?0:expectedStart-overlap;if(Math.abs(item.start()-requiredStart)>100) error(risks,"VIDEO_GAP","items","视频轨起点与转场重叠范围不一致，误差不超过 100ms");
            expectedStart=item.start()+item.duration();previousDuration=item.duration();
        }
        double duration = parsed.stream().mapToLong(i -> i.start() + i.duration()).max().orElse(0) / 1000d;
        String output=text(request,"outputPath");
        if(output.isBlank()||!Path.of(output).isAbsolute())error(risks,"OUTPUT_PATH_NOT_ABSOLUTE","outputPath","输出必须是绝对本地路径");
        JsonNode export=request.path("exportProfile");int width=export.path("width").asInt(1080),height=export.path("height").asInt(1920),fps=export.path("fps").asInt(30);String pixelFormat=export.path("pixelFormat").asText("yuv420p"),videoCodec=export.path("videoCodec").asText("libx264"),audioCodec=export.path("audioCodec").asText("aac"),audioBitrate=export.path("audioBitrate").asText("192k");int audioSampleRate=export.path("audioSampleRate").asInt(48000);if(width<16||height<16||fps<1||fps>120)error(risks,"EXPORT_PROFILE_INVALID","exportProfile","输出宽高与帧率无效");
        StringBuilder graph = new StringBuilder();
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-y"));
        for (TrackItem item : parsed) args.addAll(List.of("-i", item.path()));
        List<Integer> videos = new ArrayList<>(), audio = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            TrackItem item = parsed.get(i);
            long sourceDuration=item.track().equals("VIDEO")?item.duration()-Math.max(0,item.pauseDuration()):Math.round(item.duration()*item.playbackRate());
            String trim="start="+seconds(item.sourceStart())+":duration="+seconds(sourceDuration);
            if (item.track().equals("VIDEO")) { videos.add(i); graph.append('[').append(i).append(":v]trim=").append(trim).append(",setpts=PTS-STARTPTS");if(item.pauseDuration()>0)graph.append(",tpad=stop_mode=clone:stop_duration=").append(decimal(item.pauseDuration()/1000d));graph.append(",scale=").append(width).append(':').append(height).append(":force_original_aspect_ratio=decrease,pad=").append(width).append(':').append(height).append(":(ow-iw)/2:(oh-ih)/2:black,fps=").append(fps).append(",setsar=1,settb=AVTB,format=").append(pixelFormat).append("[v").append(i).append("]; "); }
            else { audio.add(i); graph.append('[').append(i).append(":a]atrim=").append(trim).append(",asetpts=PTS-STARTPTS");if(Math.abs(item.playbackRate()-1)>.0001)graph.append(",atempo=").append(decimal(item.playbackRate()));if(item.fadeIn()>0)graph.append(",afade=t=in:st=0:d=").append(decimal(item.fadeIn()/1000d));if(item.fadeOut()>0)graph.append(",afade=t=out:st=").append(decimal((item.duration()-item.fadeOut())/1000d)).append(":d=").append(decimal(item.fadeOut()/1000d));graph.append(",adelay=").append(item.start()).append('|').append(item.start()).append(",volume=").append(item.volume()).append("[a").append(i).append("]; "); }
        }
        if (!videos.isEmpty()) {String current="v"+videos.getFirst();double assembled=parsed.get(videos.getFirst()).duration()/1000d;for(int n=1;n<videos.size();n++){int inputIndex=videos.get(n);TrackItem item=parsed.get(inputIndex);String next="va"+n;if("CROSS_DISSOLVE".equals(item.transition())){double cross=item.transitionDuration()/1000d,offset=assembled-cross;graph.append('[').append(current).append("][v").append(inputIndex).append("]xfade=transition=fade:duration=").append(decimal(cross)).append(":offset=").append(decimal(offset)).append('[').append(next).append("]; ");assembled+=item.duration()/1000d-cross;}else if("FADE_TO_BLACK".equals(item.transition())){double fade=Math.min(.25,Math.min(assembled,item.duration()/1000d)/4),fadeStart=Math.max(0,assembled-fade);String out="vfo"+n,in="vfi"+n;graph.append('[').append(current).append("]fade=t=out:st=").append(decimal(fadeStart)).append(":d=").append(decimal(fade)).append('[').append(out).append("]; [v").append(inputIndex).append("]fade=t=in:st=0:d=").append(decimal(fade)).append('[').append(in).append("]; [").append(out).append("][").append(in).append("]concat=n=2:v=1:a=0[").append(next).append("]; ");assembled+=item.duration()/1000d;}else{graph.append('[').append(current).append("][v").append(inputIndex).append("]concat=n=2:v=1:a=0[").append(next).append("]; ");assembled+=item.duration()/1000d;}current=next;}graph.append('[').append(current).append("]null[vconcat]; ");}
        if (!audio.isEmpty()) {
            List<String> dialogue=audio.stream().filter(i->"DIALOGUE".equals(parsed.get(i).track())).map(i->"a"+i).toList(),bgm=audio.stream().filter(i->"BGM".equals(parsed.get(i).track())).map(i->"a"+i).toList(),other=audio.stream().filter(i->!Set.of("DIALOGUE","BGM").contains(parsed.get(i).track())).map(i->"a"+i).toList(),finalInputs=new ArrayList<>();
            String dialogueBus=mixBus(graph,dialogue,"dialogue_bus"),bgmBus=mixBus(graph,bgm,"bgm_bus");if(dialogueBus!=null&&bgmBus!=null){graph.append('[').append(dialogueBus).append("]asplit=2[dialogue_mix][dialogue_sidechain]; [").append(bgmBus).append("][dialogue_sidechain]sidechaincompress=threshold=0.04:ratio=8:attack=20:release=300[bgm_ducked]; ");finalInputs.add("dialogue_mix");finalInputs.add("bgm_ducked");}else{if(dialogueBus!=null)finalInputs.add(dialogueBus);if(bgmBus!=null)finalInputs.add(bgmBus);}String otherBus=mixBus(graph,other,"effects_bus");if(otherBus!=null)finalInputs.add(otherBus);if(finalInputs.size()==1)graph.append('[').append(finalInputs.getFirst()).append("]adenorm=level=-351:type=dc,loudnorm=I=-16:TP=-1.5:LRA=11,aformat=sample_fmts=fltp:sample_rates="+audioSampleRate+":channel_layouts=stereo,aresample=async=1:first_pts=0[aout]; ");else graph.append(finalInputs.stream().map(label->"["+label+"]").reduce("",String::concat)).append("amix=inputs=").append(finalInputs.size()).append(":duration=longest:normalize=0,adenorm=level=-351:type=dc,loudnorm=I=-16:TP=-1.5:LRA=11,aformat=sample_fmts=fltp:sample_rates="+audioSampleRate+":channel_layouts=stereo,aresample=async=1:first_pts=0[aout]; ");
        }
        String subtitlePath=text(request,"subtitlePath");
        String subtitleMode=text(request,"subtitleMode").isBlank()?(request.path("burnIn").asBoolean(false)?"BURN_IN":"SIDECAR"):text(request,"subtitleMode");
        if(!Set.of("BURN_IN","SIDECAR").contains(subtitleMode))error(risks,"INVALID_SUBTITLE_MODE","subtitleMode","字幕模式必须为 BURN_IN 或 SIDECAR");
        if(subtitleMode.equals("BURN_IN")){
            if(subtitlePath.isBlank()||!Path.of(subtitlePath).isAbsolute()||!Files.isRegularFile(Path.of(subtitlePath)))error(risks,"SUBTITLE_FILE_REQUIRED","subtitlePath","烧录字幕需要已写入的绝对 SRT 路径");
            else {JsonNode subtitleStyle=request.path("subtitleStyleProfile");int fontSize=subtitleStyle.path("fontSize").asInt(54),outline=subtitleStyle.path("outline").asInt(3),shadow=subtitleStyle.path("shadow").asInt(1),marginV=subtitleStyle.path("marginV").asInt(180),alignment=subtitleStyle.path("alignment").asInt(2);graph.append("[vconcat]subtitles=filename='").append(escapeSubtitlePath(Path.of(subtitlePath))).append("':force_style='FontSize=").append(fontSize).append(",Outline=").append(outline).append(",Shadow=").append(shadow).append(",MarginV=").append(marginV).append(",Alignment=").append(alignment).append("'[vout]");}
        }else graph.append("[vconcat]null[vout]");
        args.addAll(List.of("-filter_complex", graph.toString()));
        if (!videos.isEmpty()) args.addAll(List.of("-map", "[vout]"));
        if (!audio.isEmpty()) args.addAll(List.of("-map", "[aout]"));
        else args.add("-an");
        boolean preview="PREVIEW".equalsIgnoreCase(text(request,"quality"));
        args.addAll(List.of("-c:v",videoCodec,"-preset",preview?"veryfast":"medium","-crf",preview?"28":"20","-pix_fmt",pixelFormat,"-r",Integer.toString(fps)));
        if(!audio.isEmpty())args.addAll(List.of("-c:a",audioCodec,"-ar",Integer.toString(audioSampleRate),"-b:a",audioBitrate));
        args.addAll(List.of("-movflags", "+faststart", output));
        String subtitle = text(request, "srt");
        ObjectNode result = mapper.createObjectNode().put("passed", !hasErrors(risks)).put("duration", duration).put("outputPath", output)
            .put("subtitleMode", subtitleMode).put("filterGraph", graph.toString());
        ArrayNode riskArray = result.putArray("risks"); for (Risk risk : risks) riskArray.add(mapper.valueToTree(risk));
        ArrayNode argArray = result.putArray("arguments"); args.forEach(argArray::add);
        if (!subtitle.isBlank()) result.put("srt", subtitle);
        return result;
    }

    public JsonNode agentSchema(String agent) {
        String key = agent.toLowerCase(Locale.ROOT).replace("_", "-");
        ObjectNode schema = mapper.createObjectNode().put("$schema", "https://json-schema.org/draft/2020-12/schema").put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        switch (key) {
            case "storyagent", "story-agent", "story" -> throw new IllegalArgumentException("故事契约按 CORE、OUTLINE_BATCH、EPISODE_SCRIPT 分阶段生成，请使用 /api/story-development 接口审查后推进");
            case "directoragent", "director-agent", "director" -> throw new IllegalArgumentException("导演契约随本次资产版本生成，请向 POST /api/schemas/agents/director 提交场景和 assets 上下文");
            case "continuityqcagent", "continuity-qc-agent", "continuity-qc", "qc" -> { required.add("passed").add("risks").add("repairPlan"); properties.putObject("passed").put("type", "boolean"); properties.putObject("risks").put("type", "array"); properties.putObject("repairPlan").put("type", "array"); schema.put("additionalProperties", false); }
            case "editingagent", "editing-agent", "editing" -> { required.add("items").add("soundDesign").add("subtitleMode");ObjectNode item=properties.putObject("items").put("type","array").putObject("items").put("type","object");ArrayNode itemRequired=item.putArray("required");itemRequired.add("track").add("startMs").add("durationMs").add("editOperations");ObjectNode itemProperties=item.putObject("properties");itemProperties.putObject("track").put("enum",mapper.createArrayNode().add("VIDEO").add("DIALOGUE").add("AMBIENCE").add("SFX").add("BGM"));itemProperties.putObject("startMs").put("type","integer").put("minimum",0);itemProperties.putObject("durationMs").put("type","integer").put("minimum",1);itemProperties.putObject("sourceInMs").put("type","integer").put("minimum",0);itemProperties.putObject("sourceOutMs").put("type","integer").put("minimum",1);itemProperties.putObject("pauseDurationMs").put("type","integer").put("minimum",1);itemProperties.putObject("gapBeforeMs").put("type","integer").put("minimum",1);ArrayNode operationValues=mapper.createArrayNode();for(EditOperation operation:EditOperation.values())operationValues.add(operation.name());itemProperties.putObject("editOperations").put("type","array").putObject("items").set("enum",operationValues);item.put("additionalProperties",true);properties.putObject("soundDesign").put("type","object"); properties.putObject("subtitleMode").put("enum", mapper.createArrayNode().add("BURN_IN").add("SIDECAR")); schema.put("additionalProperties", false); }
            default -> throw new IllegalArgumentException("未知 Agent schema: " + agent);
        }
        return schema;
    }

    private JsonNode value(Object value) { return mapper.valueToTree(value); }
    private long timeMs(JsonNode n, String millis, String seconds) {
        if (n.has(millis)) return n.path(millis).asLong(-1);
        if (n.has(seconds)) return Math.round(n.path(seconds).asDouble(-1) * 1000);
        return -1;
    }
    private String timestamp(long ms) { long h = ms / 3_600_000, m = (ms / 60_000) % 60, s = (ms / 1000) % 60, milli = ms % 1000; return "%02d:%02d:%02d,%03d".formatted(h, m, s, milli); }
    private String wrapSubtitle(String value,int maxChars,int maxLines){List<String> rows=new ArrayList<>();String remaining=value;while(!remaining.isEmpty()&&rows.size()<maxLines){if(remaining.codePointCount(0,remaining.length())<=maxChars){rows.add(remaining);remaining="";break;}if(rows.size()==maxLines-1){rows.add(remaining);remaining="";break;}int end=remaining.offsetByCodePoints(0,maxChars),breakAt=-1;for(int offset=0;offset<end;){int cp=remaining.codePointAt(offset),next=offset+Character.charCount(cp);if("，。！？；：,.!?;:".indexOf(cp)>=0)breakAt=next;offset=next;}if(breakAt<1)breakAt=end;rows.add(remaining.substring(0,breakAt).trim());remaining=remaining.substring(breakAt).trim();}return String.join("\n",rows);}
    private String mixBus(StringBuilder graph,List<String> labels,String output){if(labels.isEmpty())return null;if(labels.size()==1){graph.append('[').append(labels.getFirst()).append("]anull[").append(output).append("]; ");return output;}graph.append(labels.stream().map(label->"["+label+"]").reduce("",String::concat)).append("amix=inputs=").append(labels.size()).append(":duration=longest:normalize=0[").append(output).append("]; ");return output;}
    private String seconds(long millis){return String.format(Locale.ROOT,"%.3f",millis/1000d);}
    private String escapeSubtitlePath(Path value){return value.toAbsolutePath().normalize().toString().replace("\\","/").replace(":","\\:").replace("'","\\'").replace("[","\\[").replace("]","\\]");}
    private double similarity(String a, String b) { if (a.equals(b)) return 1; Set<String> left = chars(a), right = chars(b); if (left.isEmpty() || right.isEmpty()) return 0; Set<String> intersection = new HashSet<>(left); intersection.retainAll(right); return (2d * intersection.size()) / (left.size() + right.size()); }
    private Set<String> chars(String text) { Set<String> result = new HashSet<>(); for (int i = 0; i < text.length(); i++) result.add(text.substring(i, i + 1)); return result; }
    private record Candidate(double score, JsonNode entry) {}
    private record Cue(String id, String display, long start, long end) {}
    private String decimal(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private record TrackItem(String track, String path, long start, long sourceStart, long duration, double volume,double playbackRate,String transition,long transitionDuration,long fadeIn,long fadeOut,long pauseDuration) {}
}
