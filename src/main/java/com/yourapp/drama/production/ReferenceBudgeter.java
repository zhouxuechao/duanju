package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.*;

/** Applies a model's recommended budget while preserving every required authority. */
@Component
public class ReferenceBudgeter {
    public record Result(List<ObjectNode> selected,List<ObjectNode> excluded){public Result{selected=List.copyOf(selected);excluded=List.copyOf(excluded);}}
    public Result fit(JsonNode refs,VideoModelProfile.ReferenceLimits limits){
        List<ObjectNode> all=new ArrayList<>();if(refs!=null&&refs.isArray())refs.forEach(n->{if(n.isObject())all.add(((ObjectNode)n).deepCopy());});
        all.sort(Comparator.comparing((ObjectNode n)->!n.path("required").asBoolean(false)).thenComparing(Comparator.comparingInt((ObjectNode n)->n.path("authorityPriority").asInt()).reversed()).thenComparing(n->n.path("id").asText()));
        List<ObjectNode> selected=new ArrayList<>(),excluded=new ArrayList<>();int images=0,videos=0,audios=0;
        for(ObjectNode ref:all){String type=ref.path("mediaType").asText(ref.path("type").asText(""));boolean required=ref.path("required").asBoolean(false);boolean fits=selected.size()<limits.total()&&switch(type){case "image_url"->images<limits.images();case "video_url"->videos<limits.videos();case "audio_url"->audios<limits.audios();default->false;};
            if(required||fits){selected.add(ref);switch(type){case "image_url"->images++;case "video_url"->videos++;case "audio_url"->audios++;default->{}}}
            else{ref.put("budgetReason","RECOMMENDED_REFERENCE_BUDGET");excluded.add(ref);}
        }
        return new Result(selected,excluded);
    }
}
