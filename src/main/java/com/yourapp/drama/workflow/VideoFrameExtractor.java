package com.yourapp.drama.workflow;

import com.yourapp.drama.production.VideoQualityReviewer;
import com.yourapp.drama.storage.MediaStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

@Service
public class VideoFrameExtractor {
    private static final Pattern DURATION=Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private final MediaStorage storage;private final String ffmpeg;
    public VideoFrameExtractor(MediaStorage storage,@Value("${drama.render.ffmpeg:ffmpeg}")String ffmpeg){this.storage=storage;this.ffmpeg=ffmpeg;}

    public List<VideoQualityReviewer.Frame> extract(String archiveKey){
        Path directory=null;try{
            directory=Files.createTempDirectory("drama-vlm-video-");Path input=directory.resolve("take.mp4");
            try(InputStream source=storage.open(MediaStorage.safeKey(archiveKey))){Files.copy(source,input,StandardCopyOption.REPLACE_EXISTING);}
            double duration=duration(input);List<Double> times=sampleTimes(duration);List<VideoQualityReviewer.Frame> frames=new ArrayList<>();
            for(int i=0;i<times.size();i++){Path output=directory.resolve("frame-"+i+".jpg");run(List.of(ffmpeg,"-hide_banner","-loglevel","error","-ss",String.format(Locale.ROOT,"%.3f",times.get(i)),"-i",input.toString(),"-frames:v","1","-q:v","3","-y",output.toString()),false);byte[] bytes=Files.readAllBytes(output);if(bytes.length==0)throw new WorkflowException("VIDEO_FRAME_EMPTY","视频质检抽帧为空");frames.add(new VideoQualityReviewer.Frame(times.get(i),"data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes)));}
            return frames;
        }catch(WorkflowException error){throw error;}catch(Exception error){throw new WorkflowException("VIDEO_FRAME_EXTRACTION_FAILED","视频自动质检抽帧失败："+error.getClass().getSimpleName());}
        finally{if(directory!=null)cleanup(directory);}
    }
    static List<Double> sampleTimes(double duration){if(!Double.isFinite(duration)||duration<=.1)throw new WorkflowException("VIDEO_DURATION_INVALID","无法读取视频时长");double end=Math.max(0,duration-.04);return List.of(0d,duration*.25,duration*.5,duration*.75,end);}
    private double duration(Path input)throws Exception{
        String output=run(List.of(ffmpeg,"-hide_banner","-i",input.toString()),true);Matcher matcher=DURATION.matcher(output);if(!matcher.find())throw new WorkflowException("VIDEO_DURATION_INVALID","无法读取视频时长");return Integer.parseInt(matcher.group(1))*3600+Integer.parseInt(matcher.group(2))*60+Double.parseDouble(matcher.group(3));
    }
    private String run(List<String> command,boolean capture)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);if(!capture)builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();String output=capture?new String(process.getInputStream().readNBytes(64*1024),StandardCharsets.UTF_8):"";
        if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new WorkflowException("VIDEO_FRAME_TIMEOUT","视频质检抽帧超时");}
        if(!capture&&process.exitValue()!=0)throw new WorkflowException("VIDEO_FRAME_EXTRACTION_FAILED","视频质检抽帧命令失败");return output;
    }
    private void cleanup(Path directory){try(var paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}
}
