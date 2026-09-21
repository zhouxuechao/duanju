package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialPreflightTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final VideoModelProfile profile=new ProviderCapabilityRegistry().profile("doubao-seedance-2-5-260628");

    @Test void hardLimitBlocksBeforePaidSubmission() {
        ArrayNode refs=mapper.createArrayNode();
        for(int i=0;i<31;i++)refs.add(ref("image_url","CHARACTER_IDENTITY",i,true));

        MaterialPreflight.Result result=new MaterialPreflight().validate(refs,profile);

        assertThat(result.passed()).isFalse();
        assertThat(result.blockingCodes()).contains("HARD_IMAGE_REFERENCE_LIMIT_EXCEEDED");
    }

    @Test void recommendedLimitWarnsButKeepsRequiredReferences() {
        ArrayNode refs=mapper.createArrayNode();
        for(int i=0;i<9;i++)refs.add(ref("image_url","CHARACTER_IDENTITY",i,true));

        MaterialPreflight.Result result=new MaterialPreflight().validate(refs,profile);

        assertThat(result.passed()).isTrue();
        assertThat(result.warnings()).contains("RECOMMENDED_IMAGE_REFERENCE_LIMIT_EXCEEDED");
        assertThat(result.requiredKeep()).hasSize(9);
    }
    @Test void rejectsAReferenceWhoseMediaTypeCannotServeItsDeclaredAuthority() {
        ArrayNode refs=mapper.createArrayNode().add(ref("image_url","VOICE_IDENTITY",1,true));
        MaterialPreflight.Result result=new MaterialPreflight().validate(refs,profile);
        assertThat(result.blockingCodes()).contains("REFERENCE_ROLE_MEDIA_MISMATCH");
    }

    @Test void blocksUnreadableMismatchedOrOverlongMediaBeforeSubmission() {
        ArrayNode refs=mapper.createArrayNode()
                .add(ref("video_url","MOTION_REFERENCE",1,true).put("durationSeconds",20).put("mimeType","audio/mpeg"))
                .add(ref("video_url","MOTION_REFERENCE",2,true).put("durationSeconds",11).put("readable",false));

        MaterialPreflight.Result result=new MaterialPreflight().validate(refs,profile);

        assertThat(result.blockingCodes()).contains("REFERENCE_MEDIA_UNREADABLE","REFERENCE_MIME_TYPE_MISMATCH","HARD_VIDEO_DURATION_LIMIT_EXCEEDED");
    }

    private ObjectNode ref(String type,String role,int index,boolean required){return mapper.createObjectNode().put("id","r"+index).put("mediaType",type).put("role",role).put("url","https://media.example.com/"+index+".png").put("required",required);}
}
