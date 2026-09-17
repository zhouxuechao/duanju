package com.yourapp.drama.production;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class ShotPlanningSkillTest {
    @Test void sourceSkillTeachesBeatFirstDirectingRatherThanKeywordFilling() throws Exception {
        try(var in=getClass().getResourceAsStream("/development-skills/06-shot-planning/prompt.md")){
            String prompt=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(prompt).contains("directorPlan","dramaticBeats","beatId","Director Intent","Reaction Shot","directorStyleProfile","visibilityPlan","actionUnits");
        }
    }
    @Test void sourceSkillRequiresOneVerbatimMasterAxisAcrossTheScene() throws Exception {
        try(var in=getClass().getResourceAsStream("/development-skills/06-shot-planning/prompt.md")){
            String prompt=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(prompt).contains("先确定一条场景主表演轴", "每镜 basicBlocking.axis 必须逐字复制同一个完整字符串", "不能按人物组合另起局部轴线")
                .doesNotContain("每镜 blocking.axis 必须逐字复制");
        }
    }
    @Test void sourceSkillMakesReactionCoverageAndShotSizeCompatibilityExplicit() throws Exception {
        try(var in=getClass().getResourceAsStream("/development-skills/06-shot-planning/prompt.md")){
            String prompt=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertThat(prompt).contains("HIGH 或 CLIMAX", "至少由两个镜头承载", "至少一个 SHOW_REACTION", "三人以上的群像建立镜头", "requiredDetail 使用 ACTION 或 SILHOUETTE", "FULL_BODY 只能使用 EXTREME_WIDE、WIDE、FULL 或 MEDIUM_FULL", "特写手部动作应使用 ACTION 或 PROP_DETAIL");
        }
    }
}
