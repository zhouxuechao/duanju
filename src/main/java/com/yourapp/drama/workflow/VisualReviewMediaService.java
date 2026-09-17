package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.storage.MediaStorage;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.*;

/** Resolves immutable visual-review inputs without exposing local-only URLs to a provider. */
@Service
public class VisualReviewMediaService {
    private static final int MAX_IMAGE_BYTES=10*1024*1024;
    private final DocumentStore store;private final MediaStorage storage;private final ObjectMapper mapper;
    public VisualReviewMediaService(DocumentStore store,MediaStorage storage,ObjectMapper mapper){this.store=store;this.storage=storage;this.mapper=mapper;}

    public ObjectNode prepare(ObjectNode frame,JsonNode generation,ObjectNode expected,JsonNode supplied){
        ObjectNode generated=supplied.isObject()?(ObjectNode)supplied.deepCopy():mapper.createObjectNode();
        if(!hasMedia(generated))generated.put("dataUrl",resolve(frame));
        addReferences(frame,generation,expected);
        return generated;
    }
    public void addReferences(ObjectNode record,JsonNode generation,ObjectNode expected){
        ArrayNode references=expected.putArray("referenceImages");
        for(JsonNode reference:record.path("assetReferences")){
            String viewId=text(reference,"viewId");if(viewId.isBlank())continue;
            ObjectNode view=store.get(ASSET_VIEW,viewId);if(view.path("stale").asBoolean()||!view.path("approved").asBoolean())
                throw new WorkflowException("ASSET_VERSION_CHANGED","视觉质检引用的素材版本已被替换");
            ObjectNode item=references.addObject().put("role",text(reference,"role")).put("assetId",text(reference,"assetId")).put("viewId",viewId);
            item.put("url",resolve(view));
        }
        String previousShotId=text(generation.path("context").path("previousShot"),"id");
        if(!previousShotId.isBlank())latestApprovedFrame(project(record),previousShotId).ifPresent(previous->{
            expected.putObject("approvedPreviousKeyframe").put("keyframeId",id(previous)).put("shotId",previousShotId).put("url",resolve(previous));
        });
    }

    public String requiredArchiveKey(JsonNode record){
        String key=text(record,"archiveKey");if(!key.isBlank())return MediaStorage.safeKey(key);
        String url=text(record,"archiveUrl");if(url.startsWith("/api/media/"))return MediaStorage.safeKey(url.substring("/api/media/".length()));
        throw new WorkflowException("VIDEO_ARCHIVE_REQUIRED","视频自动质检需要先完成本地归档");
    }
    public ObjectNode compactExpected(ObjectNode expected){
        ObjectNode compact=expected.deepCopy();for(JsonNode reference:compact.path("referenceImages"))if(reference.isObject()){((ObjectNode)reference).remove(List.of("url","providerUrl","dataUrl"));}
        if(compact.path("approvedPreviousKeyframe").isObject())((ObjectNode)compact.path("approvedPreviousKeyframe")).remove(List.of("url","providerUrl","dataUrl"));return compact;
    }

    private Optional<ObjectNode> latestApprovedFrame(String projectId,String shotId){
        return store.list(KEYFRAME,projectId,shotId).stream()
            .filter(frame->frame.path("locked").asBoolean()&&frame.path("selected").asBoolean()&&"PASSED".equals(text(frame,"qcStatus")))
            .max(Comparator.comparingInt(frame->frame.path("version").asInt()));
    }
    private String resolve(JsonNode record){
        String archiveKey=text(record,"archiveKey");if(!archiveKey.isBlank())return data(archiveKey);
        String archiveUrl=text(record,"archiveUrl");if(archiveUrl.startsWith("/api/media/"))return data(archiveUrl.substring("/api/media/".length()));
        String providerUrl=text(record,"providerUrl");if(!providerUrl.isBlank()&&!expired(record))return providerUrl;
        if(archiveUrl.startsWith("https://")||archiveUrl.startsWith("http://")||archiveUrl.startsWith("data:image/"))return archiveUrl;
        throw new WorkflowException("VISUAL_MEDIA_UNAVAILABLE","视觉质检所需图片没有可读取的归档或有效服务商链接");
    }
    private String data(String key){
        try(InputStream input=storage.open(MediaStorage.safeKey(key))){
            byte[] bytes=input.readNBytes(MAX_IMAGE_BYTES+1);if(bytes.length>MAX_IMAGE_BYTES)throw new WorkflowException("VISUAL_MEDIA_TOO_LARGE","视觉质检单张归档图片不得超过 10MB");
            return "data:"+mime(bytes)+";base64,"+Base64.getEncoder().encodeToString(bytes);
        }catch(UncheckedIOException|IOException error){throw new WorkflowException("VISUAL_MEDIA_UNAVAILABLE","视觉质检无法读取归档图片");}
    }
    private String mime(byte[] bytes){
        if(bytes.length>=8&&bytes[0]==(byte)0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G')return "image/png";
        if(bytes.length>=3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8&&bytes[2]==(byte)0xff)return "image/jpeg";
        if(bytes.length>=12&&bytes[0]=='R'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='F'&&bytes[8]=='W'&&bytes[9]=='E'&&bytes[10]=='B'&&bytes[11]=='P')return "image/webp";
        throw new WorkflowException("VISUAL_MEDIA_FORMAT","视觉质检只支持 PNG、JPEG 或 WebP 归档图片");
    }
    private boolean hasMedia(JsonNode value){return !text(value,"dataUrl").isBlank()||!text(value,"providerUrl").isBlank()||!text(value,"imageUrl").isBlank();}
}
