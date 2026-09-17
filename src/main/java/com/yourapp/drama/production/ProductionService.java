package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    public ProductionService(ObjectMapper mapper, ContinuityEngine continuity, PromptCompiler prompts, ProductionStateMachine states) {
        this.mapper = mapper; this.continuity = continuity; this.prompts = prompts; this.states = states;
    }
    public JsonNode planContinuity(JsonNode request) { return value(continuity.plan(request)); }
    public JsonNode applyLockedTake(JsonNode request) { return continuity.applyLockedTake(request); }
    public JsonNode compileImage(JsonNode request) { return value(prompts.compileImage(request)); }
    public String imageCompilerVersion(){return PromptCompiler.IMAGE_COMPILER_VERSION;}
    public JsonNode compileVideo(JsonNode request) { return value(prompts.compileVideo(request)); }
    public JsonNode routeVideo(JsonNode request) { return prompts.routeVideo(request); }
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
        String display = required(request, "displayText");
        String dialect = text(request, "dialect");
        double strength = request.path("dialectStrength").asDouble(1.0);
        if (strength < 0 || strength > 1) throw new IllegalArgumentException("dialectStrength 必须在 0 至 1 之间");
        ObjectNode result = mapper.createObjectNode().put("displayText", display).put("dialect", dialect).put("dialectStrength", strength);
        if (strength == 0 || dialect.isBlank()) {
            result.put("dialectText", display).put("speechText", display).put("confidence", 1.0)
                .put("needsHumanCorrection", false).put("status", "READY").putArray("matchedEntryIds");
            return result;
        }
        JsonNode correction = request.path("correction");
        if (correction.path("approved").asBoolean(false)) {
            String d = required(correction, "dialectText"), s = required(correction, "speechText");
            result.put("dialectText", d).put("speechText", s).put("confidence", 1.0)
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
            String source = text(entry, "displayText");
            double score = similarity(display, source);
            if (score >= 0.55 && !text(entry, "dialectText").isBlank() && !text(entry, "speechText").isBlank()) candidates.add(new Candidate(score, entry));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        ArrayNode suggestions = result.putArray("suggestions");
        for (Candidate candidate : candidates.stream().limit(3).toList()) {
            ObjectNode s = mapper.createObjectNode().put("entryId", text(candidate.entry(), "id"))
                .put("score", candidate.score()).put("displayText", text(candidate.entry(), "displayText"))
                .put("dialectText", text(candidate.entry(), "dialectText"));
            suggestions.add(s);
        }
        if (candidates.isEmpty() || candidates.getFirst().score() < 0.8) {
            result.put("dialectText", "").put("speechText", "").put("confidence", candidates.isEmpty() ? 0 : candidates.getFirst().score())
                .put("needsHumanCorrection", true).put("status", "HUMAN_REVIEW_REQUIRED").putArray("matchedEntryIds");
        } else {
            JsonNode best = candidates.getFirst().entry();
            result.put("dialectText", text(best, "dialectText")).put("speechText", text(best, "speechText"))
                .put("confidence", candidates.getFirst().score()).put("needsHumanCorrection", false).put("status", "READY");
            result.putArray("matchedEntryIds").add(text(best, "id"));
        }
        return result;
    }

    public JsonNode subtitle(JsonNode request) {
        JsonNode lines = request.has("dialogues") ? request.path("dialogues") : request.path("cues");
        if (!lines.isArray()) throw new IllegalArgumentException("subtitle 需要 dialogues/cues 数组");
        List<Cue> cues = new ArrayList<>();
        for (JsonNode line : lines) {
            String display = required(line, "displayText");
            long start = timeMs(line, "startMs", "start");
            long end = line.has("endMs") ? timeMs(line, "endMs", "end") : start + timeMs(line, "audioDurationMs", "durationMs");
            if (start < 0 || end <= start) throw new IllegalArgumentException("字幕音频时间必须为非负且 end > start: " + text(line, "dialogueId"));
            cues.add(new Cue(text(line, "dialogueId"), display.replaceAll("[\\r\\n]+", " ").trim(), start, end));
        }
        cues.sort(Comparator.comparingLong(Cue::start));
        StringBuilder srt = new StringBuilder();
        int number = 1;
        for (Cue cue : cues) {
            srt.append(number++).append('\n').append(timestamp(cue.start())).append(" --> ").append(timestamp(cue.end())).append('\n')
                .append(cue.display()).append("\n\n");
        }
        ObjectNode result = mapper.createObjectNode().put("subtitleMode", request.path("burnIn").asBoolean(false) ? "BURN_IN" : "SIDECAR")
            .put("srt", srt.toString()).put("cueCount", cues.size()).put("source", "audio_duration");
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
            long start = item.path("startMs").asLong(0), duration = item.path("durationMs").asLong(0), sourceStart = item.path("sourceStartMs").asLong(0);
            if (start < 0 || duration <= 0 || sourceStart < 0) { error(risks, "INVALID_TIMING", media, "startMs/sourceStartMs 必须非负，durationMs 必须大于 0"); continue; }
            String track = text(item, "track").toUpperCase(Locale.ROOT);
            if (!Set.of("VIDEO", "DIALOGUE", "AMBIENCE", "SFX", "BGM").contains(track)) { error(risks, "INVALID_TRACK", "items.track", "仅支持 VIDEO、DIALOGUE、AMBIENCE、SFX、BGM"); continue; }
            boolean locked = item.path("locked").asBoolean(true), qc = item.path("qcPassed").asBoolean(true);
            if (!locked || !qc) error(risks, "MEDIA_NOT_APPROVED", media, "时间线只能使用已通过 QC 且锁定的成片素材");
            double volume=item.path("volume").asDouble(1);
            if (!Double.isFinite(volume) || volume < 0 || volume > 4) { error(risks,"INVALID_VOLUME",media,"volume 必须在 0 至 4 之间"); continue; }
            parsed.add(new TrackItem(track, path.toString(), start, sourceStart, duration, volume));
        }
        for (String track : List.of("VIDEO", "DIALOGUE", "AMBIENCE", "SFX", "BGM")) {
            List<TrackItem> same = parsed.stream().filter(i -> i.track().equals(track)).sorted(Comparator.comparingLong(TrackItem::start)).toList();
            for (int i = 1; i < same.size(); i++) if (same.get(i - 1).start() + same.get(i - 1).duration() > same.get(i).start() && !track.equals("BGM"))
                error(risks, "TRACK_OVERLAP", track, "同一条非 BGM 音轨的素材不能重叠");
        }
        List<TrackItem> videoItems=parsed.stream().filter(i->i.track().equals("VIDEO")).sorted(Comparator.comparingLong(TrackItem::start)).toList();
        if(videoItems.isEmpty()) error(risks,"VIDEO_REQUIRED","items","时间线至少需要一段视频");
        long expectedStart=0;
        for(TrackItem item:videoItems){
            if(Math.abs(item.start()-expectedStart)>100) error(risks,"VIDEO_GAP","items","视频轨必须从 0 开始并连续排列，误差不超过 100ms");
            expectedStart=item.start()+item.duration();
        }
        double duration = parsed.stream().mapToLong(i -> i.start() + i.duration()).max().orElse(0) / 1000d;
        String output=text(request,"outputPath");
        if(output.isBlank()||!Path.of(output).isAbsolute())error(risks,"OUTPUT_PATH_NOT_ABSOLUTE","outputPath","输出必须是绝对本地路径");
        StringBuilder graph = new StringBuilder();
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-y"));
        for (TrackItem item : parsed) args.addAll(List.of("-i", item.path()));
        List<Integer> videos = new ArrayList<>(), audio = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            TrackItem item = parsed.get(i);
            String trim="start="+seconds(item.sourceStart())+":duration="+seconds(item.duration());
            if (item.track().equals("VIDEO")) { videos.add(i); graph.append('[').append(i).append(":v]trim=").append(trim).append(",setpts=PTS-STARTPTS[v").append(i).append("]; "); }
            else { audio.add(i); graph.append('[').append(i).append(":a]atrim=").append(trim).append(",asetpts=PTS-STARTPTS,adelay=").append(item.start()).append('|').append(item.start()).append(",volume=").append(item.volume()).append("[a").append(i).append("]; "); }
        }
        if (!videos.isEmpty()) {
            graph.append(videos.stream().map(i -> "[v" + i + "]").reduce("", String::concat))
                .append("concat=n=").append(videos.size()).append(":v=1:a=0[vconcat]; ");
        }
        if (!audio.isEmpty()) {
            graph.append(audio.stream().map(i -> "[a" + i + "]").reduce("", String::concat))
                .append("amix=inputs=").append(audio.size()).append(":duration=longest:normalize=0,aresample=async=1:first_pts=0[aout]; ");
        }
        String subtitlePath=text(request,"subtitlePath");
        String subtitleMode=text(request,"subtitleMode").isBlank()?(request.path("burnIn").asBoolean(false)?"BURN_IN":"SIDECAR"):text(request,"subtitleMode");
        if(!Set.of("BURN_IN","SIDECAR").contains(subtitleMode))error(risks,"INVALID_SUBTITLE_MODE","subtitleMode","字幕模式必须为 BURN_IN 或 SIDECAR");
        if(subtitleMode.equals("BURN_IN")){
            if(subtitlePath.isBlank()||!Path.of(subtitlePath).isAbsolute()||!Files.isRegularFile(Path.of(subtitlePath)))error(risks,"SUBTITLE_FILE_REQUIRED","subtitlePath","烧录字幕需要已写入的绝对 SRT 路径");
            else graph.append("[vconcat]subtitles=filename='").append(escapeSubtitlePath(Path.of(subtitlePath))).append("'[vout]");
        }else graph.append("[vconcat]null[vout]");
        args.addAll(List.of("-filter_complex", graph.toString()));
        if (!videos.isEmpty()) args.addAll(List.of("-map", "[vout]"));
        if (!audio.isEmpty()) args.addAll(List.of("-map", "[aout]"));
        else args.add("-an");
        boolean preview="PREVIEW".equalsIgnoreCase(text(request,"quality"));
        args.addAll(List.of("-c:v","libx264","-preset",preview?"veryfast":"medium","-crf",preview?"28":"20","-pix_fmt","yuv420p"));
        if(!audio.isEmpty())args.addAll(List.of("-c:a","aac","-b:a","192k"));
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
            case "editingagent", "editing-agent", "editing" -> { required.add("items").add("soundDesign").add("subtitleMode"); properties.putObject("items").put("type", "array"); properties.putObject("soundDesign"); properties.putObject("subtitleMode").put("enum", mapper.createArrayNode().add("BURN_IN").add("SIDECAR")); schema.put("additionalProperties", false); }
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
    private String seconds(long millis){return String.format(Locale.ROOT,"%.3f",millis/1000d);}
    private String escapeSubtitlePath(Path value){return value.toAbsolutePath().normalize().toString().replace("\\","/").replace(":","\\:").replace("'","\\'").replace("[","\\[").replace("]","\\]");}
    private double similarity(String a, String b) { if (a.equals(b)) return 1; Set<String> left = chars(a), right = chars(b); if (left.isEmpty() || right.isEmpty()) return 0; Set<String> intersection = new HashSet<>(left); intersection.retainAll(right); return (2d * intersection.size()) / (left.size() + right.size()); }
    private Set<String> chars(String text) { Set<String> result = new HashSet<>(); for (int i = 0; i < text.length(); i++) result.add(text.substring(i, i + 1)); return result; }
    private record Candidate(double score, JsonNode entry) {}
    private record Cue(String id, String display, long start, long end) {}
    private record TrackItem(String track, String path, long start, long sourceStart, long duration, double volume) {}
}
