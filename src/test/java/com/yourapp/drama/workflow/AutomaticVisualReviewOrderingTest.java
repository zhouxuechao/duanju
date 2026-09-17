package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

class AutomaticVisualReviewOrderingTest {
    @Test void equalStoryTimeFallsBackToShotOrderWithinTheScene() throws Exception {
        var earlier=JsonNodeFactory.instance.objectNode().put("sceneId","scene").put("shotNo",1).put("storyTime",0);
        var current=JsonNodeFactory.instance.objectNode().put("sceneId","scene").put("shotNo",2).put("storyTime",0);
        var service=new AutomaticVisualReviewService(null,null,null,null,null,null,null);
        Method method=AutomaticVisualReviewService.class.getDeclaredMethod("precedes",JsonNode.class,JsonNode.class,double.class);
        method.setAccessible(true);
        assertThat((boolean)method.invoke(service,earlier,current,0d)).isTrue();
    }
}
