package com.yourapp.drama.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobTypeContractTest {
    @Test void domainJobTypesContainEveryPersistedWorkerType(){
        assertThat(JobType.values()).extracting(Enum::name)
            .containsExactly("STORY","SCRIPT","DIRECTOR_PLAN","SHOT_DETAIL","STORYBOARD","KEYFRAME","KEYFRAME_QC",
                "VIDEO","VIDEO_QC","TTS","LIPSYNC","TIMELINE","RENDER","ARCHIVE","ASSET_IMAGE")
            .doesNotContain("CHARACTER_PLAN","CHARACTER_LOOK","LOCATION_LOOK","SHOT_PLAN","REPAIR");
    }
}
