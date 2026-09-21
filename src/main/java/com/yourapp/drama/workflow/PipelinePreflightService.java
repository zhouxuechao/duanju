package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.DocumentStore;
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
    private final DocumentStore store;private final Environment environment;private final String providerMode,ffmpeg,ffprobe,storageRoot;
    public PipelinePreflightService(DocumentStore store,Environment environment,@Value("${drama.provider.mode:mock}")String providerMode,@Value("${drama.render.ffmpeg:ffmpeg}")String ffmpeg,@Value("${drama.render.ffprobe:ffprobe}")String ffprobe,@Value("${drama.storage.root:./data/media}")String storageRoot){this.store=store;this.environment=environment;this.providerMode=providerMode;this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.storageRoot=storageRoot;}
    public ObjectNode review(String projectId,String requestedMode){String mode=requestedMode==null||requestedMode.isBlank()?providerMode.toUpperCase(Locale.ROOT):requestedMode.toUpperCase(Locale.ROOT);if(!Set.of("MOCK","MEDIA","CANARY","LIVE").contains(mode))throw new IllegalArgumentException("自检模式只支持 MOCK、MEDIA、CANARY 或 LIVE");ArrayNode checks=JsonNodeFactory.instance.arrayNode(),blocking=JsonNodeFactory.instance.arrayNode(),warnings=JsonNodeFactory.instance.arrayNode();ObjectNode project;
        try{project=store.get(PROJECT,projectId);pass(checks,"DATABASE_OK","项目数据库可读写");}catch(RuntimeException error){fail(checks,blocking,"DATABASE_UNAVAILABLE","无法读取项目数据库",false,"检查数据库连接和迁移状态");return result(mode,checks,blocking,warnings);}
        if(project.path("idea").asText().isBlank())fail(checks,blocking,"PROJECT_IDEA_MISSING","项目缺少故事创意",false,"先填写故事创意");else pass(checks,"PROJECT_INPUT_OK","项目基础输入完整");
        if(runtimeResource("00-premise-analysis/prompt.md")&&runtimeResource("01-story-planning/prompt.md")&&runtimeResource("04-script-writing/prompt.md"))pass(checks,"STORY_SKILLS_OK","故事阶段规则可用");else fail(checks,blocking,"STORY_SKILLS_MISSING","故事阶段规则文件不完整",false,"恢复应用包中的前提分析、故事规划和剧本规则");
        if(runtimeResource("vendor/short-drama-factory/UPSTREAM.md"))pass(checks,"VENDOR_RULES_OK","上游编剧规则版本可追溯");else fail(checks,blocking,"VENDOR_RULES_MISSING","上游编剧规则缺少版本记录",false,"恢复应用包中的 vendor 规则与 UPSTREAM.md");
        boolean mediaRequired=Set.of("MEDIA","CANARY","LIVE").contains(mode);tool(checks,blocking,warnings,"FFMPEG",ffmpeg,mediaRequired);tool(checks,blocking,warnings,"FFPROBE",ffprobe,mediaRequired);
        try{Path root=Path.of(storageRoot).toAbsolutePath().normalize();Files.createDirectories(root);if(!Files.isWritable(root))throw new IOException();pass(checks,"STORAGE_OK","媒体存储目录可写");}catch(IOException|RuntimeException error){fail(checks,blocking,"STORAGE_UNAVAILABLE","媒体存储不可写",false,"检查 STORAGE_ROOT 权限或对象存储配置");}
        if("MOCK".equals(mode)||"MEDIA".equals(mode))pass(checks,"PROVIDER_MOCK_OK","自检不会调用付费模型");else liveProvider(checks,blocking);
        return result(mode,checks,blocking,warnings);
    }
    private void liveProvider(ArrayNode checks,ArrayNode blocking){List<String> missing=new ArrayList<>();for(String property:List.of("drama.provider.volcengine.api-key","drama.provider.volcengine.text-model","drama.provider.volcengine.image-model","drama.provider.volcengine.video-model","drama.provider.seed-audio.api-key"))if(blank(environment.getProperty(property)))missing.add(property.substring(property.lastIndexOf('.')+1));if(missing.isEmpty())pass(checks,"LIVE_PROVIDER_CONFIG_OK","文字、图片、视频与语音模型配置完整");else fail(checks,blocking,"LIVE_PROVIDER_CONFIG_MISSING","真实模型配置缺失："+String.join("、",missing),false,"补齐本地环境配置后重新自检；不要把密钥粘贴到页面");}
    private void tool(ArrayNode checks,ArrayNode blocking,ArrayNode warnings,String name,String command,boolean required){if(commandWorks(command))pass(checks,name+"_OK",name+" 可执行");else if(required)fail(checks,blocking,name+"_MISSING",name+" 不可执行",false,"配置 "+name+"_PATH 后重试");else fail(checks,warnings,name+"_MISSING",name+" 不可执行；免费结构流程仍可运行",true,"媒体模式前配置 "+name+"_PATH");}
    private boolean commandWorks(String command){try{Process process=new ProcessBuilder(command,"-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();return process.waitFor(5,TimeUnit.SECONDS)&&process.exitValue()==0;}catch(IOException|InterruptedException error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();return false;}}
    private boolean runtimeResource(String relativePath){ClassPathResource resource=new ClassPathResource("development-skills/"+relativePath);return resource.exists()&&resource.isReadable();}
    private ObjectNode result(String mode,ArrayNode checks,ArrayNode blocking,ArrayNode warnings){ObjectNode result=JsonNodeFactory.instance.objectNode().put("mode",mode).put("ready",blocking.isEmpty());result.set("blocking",blocking);result.set("warnings",warnings);result.set("checks",checks);return result;}
    private void pass(ArrayNode checks,String code,String message){checks.add(JsonNodeFactory.instance.objectNode().put("code",code).put("status","PASS").put("message",message));}
    private void fail(ArrayNode checks,ArrayNode destination,String code,String message,boolean warning,String hint){ObjectNode item=JsonNodeFactory.instance.objectNode().put("code",code).put("status",warning?"WARN":"FAIL").put("message",message).put("fixHint",hint);checks.add(item);destination.add(item.deepCopy());}
    private boolean blank(String value){return value==null||value.isBlank();}
}
