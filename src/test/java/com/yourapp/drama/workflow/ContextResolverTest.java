package com.yourapp.drama.workflow;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static com.yourapp.drama.workflow.Documents.*;
import static org.assertj.core.api.Assertions.*;

class ContextResolverTest {
    @Test void filtersLargeAssetCatalogToVisibleShotInputs() {
        ObjectNode assets=obj(); ArrayNode chars=assets.putArray("characters"),looks=assets.putArray("looks"),locs=assets.putArray("locations"),props=assets.putArray("props");
        for(int i=0;i<100;i++) chars.add(obj().put("id","c"+i));
        for(int i=0;i<100;i++) looks.add(obj().put("id","l"+i));
        for(int i=0;i<50;i++) locs.add(obj().put("id","loc"+i));
        for(int i=0;i<100;i++) props.add(obj().put("id","p"+i));
        ObjectNode shot=obj().put("locationId","loc7"); shot.putArray("characterIds").add("c1").add("c2").add("c3"); shot.putArray("propIds").add("p4").add("p5");
        ObjectNode characterState=shot.putObject("startState").putObject("characters"); characterState.putObject("c1").put("lookId","l1"); characterState.putObject("c2").put("lookId","l2"); characterState.putObject("c3").put("lookId","l3");
        ObjectNode filtered=new ContextResolver().filterAssets(assets,shot);
        assertThat(filtered.path("characters")).hasSize(3); assertThat(filtered.path("looks")).hasSize(3); assertThat(filtered.path("locations")).hasSize(1); assertThat(filtered.path("props")).hasSize(2);
    }
}
