package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.production.ProviderCapabilityRegistry;
import com.yourapp.drama.production.RuntimeRulePackLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.yourapp.drama.persistence.ResourceKind.PROJECT;

@Service
public class PipelinePreflightService {
    private final DocumentStore store;private final Environment environment;private final ObjectMapper mapper;private final RuntimeRulePackLoader rulePacks;private final ProviderCapabilityRegistry capabilities;private final TestBudgetGuard budget;private final String providerMode,ffmpeg,ffprobe,storageRoot;
    public PipelinePreflightService(DocumentStore store,Environment environment,ObjectMapper mapper,RuntimeRulePackLoader rulePacks,ProviderCapabilityRegistry capabilities,TestBudgetGuard budget,@Value("${drama.provider.mode:mock}")String providerMode,@Value("${drama.render.ffmpeg:ffmpeg}")String ffmpeg,@Value("${drama.render.ffprobe:ffprobe}")String ffprobe,@Value("${drama.storage.root:./data/media}")String storageRoot){this.store=store;this.environment=environment;this.mapper=mapper;this.rulePacks=rulePacks;this.capabilities=capabilities;this.budget=budget;this.providerMode=providerMode;this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.storageRoot=storageRoot;}
    public ObjectNode review(String projectId,String requestedMode){String mode=requestedMode==null||requestedMode.isBlank()?providerMode.toUpperCase(Locale.ROOT):requestedMode.toUpperCase(Locale.ROOT);if(!Set.of("MOCK","MEDIA","CANARY","LIVE").contains(mode))throw new IllegalArgumentException("自检模式只支持 MOCK、MEDIA、CANARY 或 LIVE");ArrayNode checks=JsonNodeFactory.instance.arrayNode(),blocking=JsonNodeFactory.instance.arrayNode(),warnings=JsonNodeFactory.instance.arrayNode();ObjectNode project;
        try{project=store.get(PROJECT,projectId);pass(checks,"DATABASE_OK","项目数据库可读写");}catch(RuntimeException error){fail(checks,blocking,"DATABASE_UNAVAILABLE","无法读取项目数据库",false,"检查数据库连接和迁移状态");return result(mode,checks,blocking,warnings);}
        if(project.path("idea").asText().isBlank())fail(checks,blocking,"PROJECT_IDEA_MISSING","项目缺少故事创意",false,"先填写故事创意");else pass(checks,"PROJECT_INPUT_OK","项目基础输入完整");
        if(runtimeResource("00-premise-analysis/prompt.md")&&runtimeResource("01-story-planning/prompt.md")&&runtimeResource("04-script-writing/prompt.md"))pass(checks,"STORY_SKILLS_OK","故事阶段规则可用");else fail(checks,blocking,"STORY_SKILLS_MISSING","故事阶段规则文件不完整",false,"恢复应用包中的前提分析、故事规划和剧本规则");
        if(runtimeResource("vendor/short-drama-factory/UPSTREAM.md")&&runtimeResource("vendor/manju-laoli-skill/UPSTREAM.md")&&runtimeResource("vendor/oiuv-ai-short-drama/UPSTREAM.md"))pass(checks,"VENDOR_RULES_OK","三套上游规则版本可追溯");else fail(checks,blocking,"VENDOR_RULES_MISSING","上游规则缺少版本记录",false,"恢复应用包中的三套 vendor 规则与 UPSTREAM.md");
        runtimeDependencies(checks,blocking);
        boolean mediaRequired=Set.of("MEDIA","CANARY","LIVE").contains(mode);tool(checks,blocking,warnings,"FFMPEG",ffmpeg,mediaRequired);tool(checks,blocking,warnings,"FFPROBE",ffprobe,mediaRequired);
        try{Path root=Path.of(storageRoot).toAbsolutePath().normalize();Files.createDirectories(root);if(!Files.isWritable(root))throw new IOException();pass(checks,"STORAGE_OK","媒体存储目录可写");}catch(IOException|RuntimeException error){fail(checks,blocking,"STORAGE_UNAVAILABLE","媒体存储不可写",false,"检查 STORAGE_ROOT 权限或对象存储配置");}
        if("MOCK".equals(mode)||"MEDIA".equals(mode))pass(checks,"PROVIDER_MOCK_OK","自检不会调用付费模型");else liveProvider(checks,blocking);
        return result(mode,checks,blocking,warnings);
    }
    private void liveProvider(ArrayNode checks,ArrayNode blocking){List<String> missing=new ArrayList<>();for(String property:List.of("drama.provider.volcengine.api-key","drama.provider.volcengine.text-model","drama.provider.volcengine.image-model","drama.provider.volcengine.video-model","drama.provider.seed-audio.api-key"))if(blank(environment.getProperty(property)))missing.add(property.substring(property.lastIndexOf('.')+1));if(missing.isEmpty())pass(checks,"LIVE_PROVIDER_CONFIG_OK","文字、图片、视频与语音模型配置完整");else fail(checks,blocking,"LIVE_PROVIDER_CONFIG_MISSING","真实模型配置缺失："+String.join("、",missing),false,"补齐本地环境配置后重新自检；不要把密钥粘贴到页面");}
    private void runtimeDependencies(ArrayNode checks,ArrayNode blocking){
        try{
            var story=rulePacks.load(RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE,List.of("character-bible"));
            var director=rulePacks.load(RuntimeRulePackLoader.Namespace.SPATIAL,List.of("spatial-reference-system-v3"));
            var provider=rulePacks.load(RuntimeRulePackLoader.Namespace.PROVIDER_SEEDANCE_25,List.of("locked-routing"));
            if(story.content().length()<100||director.content().length()<100||provider.content().length()<100)throw new IllegalStateException("规则正文为空");
            pass(checks,"RUNTIME_RULE_PACK_OK","编剧、导演和视频服务商规则正文可由统一 Loader 读取");
        }catch(RuntimeException error){fail(checks,blocking,"RUNTIME_RULE_PACK_MISSING","运行时规则正文无法加载："+error.getMessage(),false,"恢复运行时规则资源并重新打包");}
        try(var stream=new ClassPathResource("development-skills/manifest.json").getInputStream()){
            JsonNode manifest=mapper.readTree(stream);String raw=manifest.toString();
            if(!manifest.path("skills").isArray()||!raw.contains("short-drama-factory")||!raw.contains("manju-laoli-skill")||!raw.contains("oiuv-ai-short-drama"))throw new IllegalStateException("Manifest 未声明三套运行时依赖");
            pass(checks,"SKILL_MANIFEST_OK","Skill Manifest 已打包并声明全部运行时规则依赖");
        }catch(Exception error){fail(checks,blocking,"SKILL_MANIFEST_INVALID","Skill Manifest 不可用："+error.getMessage(),false,"修复 manifest 并将其打入运行时 classpath");}
        try{var profile=capabilities.profile(environment.getProperty("drama.provider.volcengine.video-model","doubao-seedance-2-0"));if(profile.modelId().isBlank()||profile.supportedTaskTypes().isEmpty())throw new IllegalStateException("模型能力为空");pass(checks,"VIDEO_MODEL_PROFILE_OK","视频模型 Profile 与能力路由可用");}catch(RuntimeException error){fail(checks,blocking,"VIDEO_MODEL_PROFILE_INVALID","视频模型能力不可用："+error.getMessage(),false,"修复模型 Profile 或 Capability Registry");}
        try{budget.snapshot("__preflight__");pass(checks,"BUDGET_GUARD_OK","测试预算持久化账本可读");}catch(RuntimeException error){fail(checks,blocking,"BUDGET_GUARD_UNAVAILABLE","测试预算账本不可用："+error.getMessage(),false,"检查预算迁移和数据库连接");}
    }
    private void tool(ArrayNode checks,ArrayNode blocking,ArrayNode warnings,String name,String command,boolean required){if(commandWorks(command))pass(checks,name+"_OK",name+" 可执行");else if(required)fail(checks,blocking,name+"_MISSING",name+" 不可执行",false,"配置 "+name+"_PATH 后重试");else fail(checks,warnings,name+"_MISSING",name+" 不可执行；免费结构流程仍可运行",true,"媒体模式前配置 "+name+"_PATH");}
    private boolean commandWorks(String command){try{Process process=new ProcessBuilder(command,"-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();return process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0;}catch(IOException|InterruptedException error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();return false;}}
    private boolean runtimeResource(String relativePath){ClassPathResource resource=new ClassPathResource("development-skills/"+relativePath);return resource.exists()&&resource.isReadable();}
    private ObjectNode result(String mode,ArrayNode checks,ArrayNode blocking,ArrayNode warnings){ObjectNode result=JsonNodeFactory.instance.objectNode().put("mode",mode).put("ready",blocking.isEmpty());result.set("blocking",blocking);result.set("warnings",warnings);result.set("checks",checks);return result;}
    private void pass(ArrayNode checks,String code,String message){checks.add(JsonNodeFactory.instance.objectNode().put("code",code).put("status","PASS").put("message",message));}
    private void fail(ArrayNode checks,ArrayNode destination,String code,String message,boolean warning,String hint){ObjectNode item=JsonNodeFactory.instance.objectNode().put("code",code).put("status",warning?"WARN":"FAIL").put("message",message).put("fixHint",hint);checks.add(item);destination.add(item.deepCopy());}
    private boolean blank(String value){return value==null||value.isBlank();}
}
