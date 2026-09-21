package com.yourapp.drama.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Service
public class MediaProbeService {
    private final ObjectMapper mapper;private final String ffprobe;
    public MediaProbeService(ObjectMapper mapper,@Value("${drama.render.ffprobe:ffprobe}")String ffprobe){this.mapper=mapper;this.ffprobe=ffprobe;}
    public ObjectNode probe(InputStream media,String suffix){Path input=null,output=null;try{
        input=Files.createTempFile("drama-probe-",suffix);output=Files.createTempFile("drama-probe-",".json");Files.copy(media,input,StandardCopyOption.REPLACE_EXISTING);
        Process process=new ProcessBuilder(ffprobe,"-v","error","-show_entries","format=duration:stream=codec_type,codec_name,width,height,avg_frame_rate,nb_frames,sample_rate,channels","-of","json",input.toAbsolutePath().toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new WorkflowException("MEDIA_PROBE_TIMEOUT","媒体信息探测超过 60 秒");}
        String raw=Files.readString(output,StandardCharsets.UTF_8);if(process.exitValue()!=0)throw new WorkflowException("MEDIA_PROBE_FAILED","媒体信息探测失败："+bounded(raw));return parse(raw);
    }catch(IOException e){throw new WorkflowException("MEDIA_PROBE_UNAVAILABLE","无法启动媒体信息探测工具，请检查 ffprobe 配置");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new WorkflowException("MEDIA_PROBE_INTERRUPTED","媒体信息探测被中断");}finally{delete(input);delete(output);}}
    public ObjectNode parse(String raw){try{
        JsonNode root=mapper.readTree(raw),video=null,audio=null;for(JsonNode stream:root.path("streams")){if(video==null&&"video".equals(stream.path("codec_type").asText()))video=stream;if(audio==null&&"audio".equals(stream.path("codec_type").asText()))audio=stream;}
        double seconds=root.path("format").path("duration").asDouble(-1);if(seconds<=0||!Double.isFinite(seconds)||video==null)throw new WorkflowException("MEDIA_METADATA_INVALID","视频缺少有效的时长或视频轨");
        ObjectNode result=mapper.createObjectNode().put("actualDurationMs",Math.round(seconds*1000)).put("width",video.path("width").asInt()).put("height",video.path("height").asInt()).put("videoCodec",video.path("codec_name").asText("")).put("frameRate",rate(video.path("avg_frame_rate").asText())).put("frameCount",video.path("nb_frames").asLong(0)).put("hasAudio",audio!=null);
        if(audio!=null)result.put("audioCodec",audio.path("codec_name").asText("")).put("audioSampleRate",audio.path("sample_rate").asInt()).put("audioChannels",audio.path("channels").asInt());return result;
    }catch(JsonProcessingException e){throw new WorkflowException("MEDIA_METADATA_INVALID","ffprobe 返回了无法解析的媒体信息");}}
    private double rate(String value){try{String[] pair=value.split("/");double numerator=Double.parseDouble(pair[0]),denominator=pair.length>1?Double.parseDouble(pair[1]):1;return denominator==0?0:numerator/denominator;}catch(RuntimeException e){return 0;}}
    private String bounded(String value){String clean=value.replaceAll("[\\r\\n]+"," ");return clean.length()>500?clean.substring(0,500):clean;}
    private void delete(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException ignored){}}
}
