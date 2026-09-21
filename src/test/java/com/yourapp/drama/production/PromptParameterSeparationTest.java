package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class PromptParameterSeparationTest {
    @Test void videoPromptContainsCreativeConstraintsButNoProviderApiParameters() throws Exception {
        Method fixture=PromptCompilerV2Test.class.getDeclaredMethod("request",String.class);fixture.setAccessible(true);
        ObjectNode request=(ObjectNode)fixture.invoke(new PromptCompilerV2Test(),"ESTABLISHING");
        ObjectNode shot=(ObjectNode)request.path("shot");
        shot.put("feltIntent","让观众意识到人物已经察觉异常").put("endpoint","人物停在门边，道具仍在右手");
        shot.set("promptCarriers",new ObjectMapper().createObjectNode().putArray("performance").add("人物完成一次停顿"));
        shot.putArray("completedBeats");shot.putArray("reservedFutureBeats");
        request.set("videoOutputProfile",new ObjectMapper().createObjectNode().put("ratio","9:16").put("resolution","1080p").put("fps",30));

        String prompt=new PromptCompiler(new ObjectMapper(),new ContinuityEngine(new ObjectMapper())).compileVideo(request).prompt();

        assertThat(prompt).contains("一个 Shot 对应一个连续 Video Take");
        assertThat(prompt).doesNotContain("ratio", "9:16", "resolution", "1080p", "fps", "providerCapabilitiesVersion");
    }
}
