package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoTaskRouteContractTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final VideoRequestRouteResolver resolver=new VideoRequestRouteResolver();
    private final VideoModelProfile profile=new ProviderCapabilityRegistry().profile("doubao-seedance-2-5-260628");

    @Test void lockedFirstLastTaskUsesNativeBoundariesOnly() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME","start.png")).add(frame("END_FRAME","end.png"));
        var route=resolver.resolve(VideoTaskType.FIRST_LAST_FRAME_GENERATE,ProviderTaskLockMode.LOCKED,profile,frames,mapper.createObjectNode(),mapper.createArrayNode());
        assertThat(route.route()).isEqualTo(VideoRequestRouteResolver.Route.NATIVE_FIRST_LAST_FRAME);
        assertThat(route.firstFrameUrl()).endsWith("start.png");
        assertThat(route.references()).extracting(r->r.role()).containsExactly("last_frame");
    }

    @Test void unlockedKeyframeTaskUsesSemanticReferencesAndNoNativeBoundary() {
        ArrayNode frames=mapper.createArrayNode().add(frame("START_FRAME","start.png")).add(frame("INTERMEDIATE_KEYFRAME","middle.png"));
        var route=resolver.resolve(VideoTaskType.KEYFRAME_GENERATE,ProviderTaskLockMode.UNLOCKED,profile,frames,mapper.createObjectNode(),mapper.createArrayNode());
        assertThat(route.route()).isEqualTo(VideoRequestRouteResolver.Route.SEMANTIC_REFERENCES);
        assertThat(route.firstFrameUrl()).isNull();
        assertThat(route.references()).extracting(r->r.role()).containsOnly("reference_image");
    }

    @Test void rejectsTaskLockMismatchAndReservedTaskBeforeProviderCall() {
        assertThatThrownBy(()->resolver.resolve(VideoTaskType.FIRST_FRAME_GENERATE,ProviderTaskLockMode.UNLOCKED,profile,mapper.createArrayNode(),mapper.createObjectNode(),mapper.createArrayNode())).hasMessageContaining("VIDEO_TASK_LOCK_MODE_MISMATCH");
        assertThatThrownBy(()->resolver.resolve(VideoTaskType.VIDEO_EDIT,ProviderTaskLockMode.LOCKED,profile,mapper.createArrayNode(),mapper.createObjectNode(),mapper.createArrayNode())).hasMessageContaining("VIDEO_TASK_NOT_IMPLEMENTED");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode frame(String role,String name){return mapper.createObjectNode().put("semanticRole",role).put("providerUrl","https://media.example.com/"+name);}
}
