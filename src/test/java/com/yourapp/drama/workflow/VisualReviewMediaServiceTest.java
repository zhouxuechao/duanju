package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yourapp.drama.persistence.DocumentStore;
import com.yourapp.drama.storage.MediaStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static com.yourapp.drama.persistence.ResourceKind.*;
import static com.yourapp.drama.workflow.Documents.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisualReviewMediaServiceTest {
    @Test void expiredProviderUrlsUseArchivedPixelsForGeneratedReferencesAndPreviousFrame(){
        DocumentStore store=mock(DocumentStore.class);MediaStorage storage=mock(MediaStorage.class);
        byte[] png=new byte[]{(byte)0x89,'P','N','G',13,10,26,10,1,2,3};
        when(storage.open(anyString())).thenAnswer(call->new ByteArrayInputStream(png));
        ObjectNode view=obj().put("id","view-1").put("approved",true).put("archiveKey","asset-views/p/view-1.png");
        when(store.get(ASSET_VIEW,"view-1")).thenReturn(view);
        ObjectNode previous=obj().put("id","frame-previous").put("projectId","project-1").put("shotId","shot-previous")
            .put("selected",true).put("locked",true).put("qcStatus","PASSED").put("archiveUrl","/api/media/keyframes/frame-previous.png");
        when(store.list(KEYFRAME,"project-1","shot-previous")).thenReturn(List.of(previous));

        ObjectNode frame=obj().put("id","frame-current").put("projectId","project-1").put("shotId","shot-current")
            .put("providerUrl","https://expired.example/current.png")
            .put("providerUrlExpiresAt", Instant.now().minusSeconds(30).toString())
            .put("archiveUrl","/api/media/keyframes/frame-current.png");
        frame.putArray("assetReferences").addObject().put("role","CHARACTER_LOOK").put("assetId","actor-1").put("viewId","view-1").put("url","https://expired.example/actor.png");
        ObjectNode generation=obj();generation.putObject("context").putObject("previousShot").put("id","shot-previous");
        ObjectNode expected=obj();

        ObjectNode generated=new VisualReviewMediaService(store,storage,new ObjectMapper()).prepare(frame,generation,expected,obj());

        assertThat(generated.path("dataUrl").asText()).startsWith("data:image/png;base64,");
        assertThat(expected.path("referenceImages").get(0).path("url").asText()).startsWith("data:image/png;base64,");
        assertThat(expected.path("approvedPreviousKeyframe").path("url").asText()).startsWith("data:image/png;base64,");
        verify(storage).open("keyframes/frame-current.png");
        verify(storage).open("asset-views/p/view-1.png");
        verify(storage).open("keyframes/frame-previous.png");
    }
}
