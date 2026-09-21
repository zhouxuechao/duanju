package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MediaProbeServiceTest {
    @Test void probesTheRealGoldenVideoWithTheProjectFfprobe() throws Exception {
        Path executable=Path.of("frontend/node_modules/ffprobe-static/bin/win32/x64/ffprobe.exe");
        assertThat(executable).as("项目必须自带 ffprobe，不能依赖旧项目或系统 PATH").isRegularFile();
        MediaProbeService service=new MediaProbeService(new ObjectMapper(),executable.toAbsolutePath().toString());
        try(InputStream media=Files.newInputStream(Path.of("test-fixtures/e2e/golden-basic/mock-provider/take-3s.mp4"))){
            ObjectNode metadata=service.probe(media,".mp4");
            assertThat(metadata.path("actualDurationMs").asLong()).isBetween(2_900L,3_100L);
            assertThat(metadata.path("width").asInt()).isGreaterThan(0);
            assertThat(metadata.path("height").asInt()).isGreaterThan(0);
            assertThat(metadata.path("videoCodec").asText()).isNotBlank();
        }
    }

    @Test void parsesActualVideoTimingAndTracksFromFfprobeOutput() throws Exception {
        Class<?> type=Class.forName("com.yourapp.drama.workflow.MediaProbeService");
        Object service=type.getConstructor(ObjectMapper.class,String.class).newInstance(new ObjectMapper(),"ffprobe");
        String output="""
                {"format":{"duration":"3.504"},"streams":[
                  {"codec_type":"video","width":1080,"height":1920,"avg_frame_rate":"25/1","nb_frames":"88"},
                  {"codec_type":"audio","codec_name":"aac","sample_rate":"48000","channels":2}
                ]}
                """;

        ObjectNode metadata=ReflectionTestUtils.invokeMethod(service,"parse",output);

        assertThat(metadata.path("actualDurationMs").asLong()).isEqualTo(3504);
        assertThat(metadata.path("width").asInt()).isEqualTo(1080);
        assertThat(metadata.path("height").asInt()).isEqualTo(1920);
        assertThat(metadata.path("frameRate").asDouble()).isEqualTo(25);
        assertThat(metadata.path("hasAudio").asBoolean()).isTrue();
        assertThat(metadata.path("audioCodec").asText()).isEqualTo("aac");
    }
}
