package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class VideoRequestRouteResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final VideoRequestRouteResolver resolver = new VideoRequestRouteResolver();

    @Test void continuousAcceptedTakeUsesFullModalReferenceWithoutFirstFrame() {
        ObjectNode capabilities = capabilities(true, true);
        ObjectNode keyframe = mapper.createObjectNode().put("providerUrl", "https://media.example.com/keyframe.png");
        ObjectNode previous = acceptedTake().put("videoUrl", "https://media.example.com/previous.mp4");
        ArrayNode bindings = mapper.createArrayNode()
                .add(binding("PREVIOUS_TAKE", "https://media.example.com/previous.mp4"))
                .add(binding("KEYFRAME_PROVIDER", "https://media.example.com/keyframe.png"));

        var route = resolver.resolve("SEAMLESS_CONTINUATION", capabilities, keyframe, previous, bindings);

        assertThat(route.route()).isEqualTo(VideoRequestRouteResolver.Route.FULL_MODAL_REFERENCE);
        assertThat(route.firstFrameUrl()).isNull();
        assertThat(route.references()).extracting(r -> r.role()).containsExactly("reference_video", "reference_image");
    }

    @Test void cutUsesOnlyFirstFrame() {
        var route = resolver.resolve("CUT", capabilities(true, true),
                mapper.createObjectNode().put("providerUrl", "https://media.example.com/keyframe.png"),
                mapper.createObjectNode(), mapper.createArrayNode());

        assertThat(route.route()).isEqualTo(VideoRequestRouteResolver.Route.FIRST_FRAME);
        assertThat(route.firstFrameUrl()).isEqualTo("https://media.example.com/keyframe.png");
        assertThat(route.references()).isEmpty();
    }

    @Test void continuousFallsBackToPreviousLastFrameWithoutReferenceVideo() {
        ObjectNode previous = acceptedTake().put("lastFrameUrl", "https://media.example.com/previous-last.jpg");
        var route = resolver.resolve("SEAMLESS_CONTINUATION", capabilities(false, true),
                mapper.createObjectNode().put("providerUrl", "https://media.example.com/keyframe.png"),
                previous, mapper.createArrayNode());

        assertThat(route.route()).isEqualTo(VideoRequestRouteResolver.Route.CONTINUATION_LAST_FRAME);
        assertThat(route.firstFrameUrl()).isEqualTo("https://media.example.com/previous-last.jpg");
        assertThat(route.references()).isEmpty();
    }

    @Test void unsupportedContinuationIsBlockedBeforeSubmission() {
        assertThatThrownBy(() -> resolver.resolve("SEAMLESS_CONTINUATION", capabilities(false, false),
                mapper.createObjectNode().put("providerUrl", "https://media.example.com/keyframe.png"),
                acceptedTake(), mapper.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VIDEO_ROUTE_UNSUPPORTED");
    }

    private ObjectNode capabilities(boolean referenceVideo, boolean lastFrame) {
        return mapper.createObjectNode().put("supportsReferenceVideo", referenceVideo)
                .put("supportsStartEndFrame", lastFrame).put("maxVideoRefs", referenceVideo ? 1 : 0).put("maxImageRefs", 1);
    }
    private ObjectNode acceptedTake() {
        return mapper.createObjectNode().put("locked", true).put("selected", true).put("qcPassed", true);
    }
    private ObjectNode binding(String role, String url) { return mapper.createObjectNode().put("role", role).put("url", url); }
}
