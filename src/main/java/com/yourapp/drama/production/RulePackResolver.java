package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.util.*;

/** Selects rule ids from story/director context; it never embeds business state. */
@Component
public final class RulePackResolver {
    private static final Set<String> SATISFACTION_TYPES = Set.of(
            "COUNTERATTACK", "REVENGE", "IDENTITY_REVERSAL", "REBIRTH",
            "SON_IN_LAW", "DOMINANT_CEO", "POWER_FANTASY");
    private final RuntimeRulePackLoader loader;

    public RulePackResolver(RuntimeRulePackLoader loader) { this.loader = loader; }

    public List<RuntimeRulePackLoader.RulePack> story(String phase, String storyType, Collection<String> tropes) {
        return story(phase, storyType, tropes, "GENERAL");
    }

    public List<RuntimeRulePackLoader.RulePack> story(String phase, String storyType, Collection<String> tropes,
                                                      String distributionProfile) {
        String normalizedPhase = normalize(phase, "CORE");
        String normalizedType = normalize(storyType, "OTHER");
        List<RuntimeRulePackLoader.RulePack> packs = new ArrayList<>();
        if ("STORY_BRIEF".equals(normalizedPhase)) {
            packs.add(loader.load(RuntimeRulePackLoader.Namespace.STORY_PATTERN, List.of("script-brief")));
            return List.copyOf(packs);
        }
        List<String> core = switch (normalizedPhase) {
            case "CORE" -> List.of("screenplay-compliance", "character-bible", "emotion-contract", "continuity-ledger");
            case "OUTLINE", "OUTLINE_BATCH" -> List.of("emotion-contract", "continuity-ledger", "hook-library", "cliffhanger-formulas", "episode-format");
            case "SCRIPT", "EPISODE_SCRIPT", "STORY_QA" -> List.of("screenplay-compliance", "continuity-ledger", "dialogue-doctor", "hook-library", "cliffhanger-formulas", "episode-format");
            default -> List.of("screenplay-compliance", "continuity-ledger");
        };
        packs.add(loader.load(RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE, core));
        packs.add(loader.load(RuntimeRulePackLoader.Namespace.STORY_PATTERN, List.of("theme-patterns")));
        boolean satisfaction = SATISFACTION_TYPES.contains(normalizedType) || containsAny(tropes, "REBIRTH", "FACE_SLAP", "HIDDEN_IDENTITY_REVERSAL");
        if (satisfaction) packs.add(loader.load(RuntimeRulePackLoader.Namespace.STORY_TYPE, List.of("satisfaction-model")));
        if (Set.of("OUTLINE", "OUTLINE_BATCH").contains(normalizedPhase))
            packs.add(loader.load(RuntimeRulePackLoader.Namespace.STORY_PATTERN, List.of("template-analysis")));
        if (normalize(distributionProfile, "GENERAL").startsWith("HONGGUO"))
            packs.add(loader.load(RuntimeRulePackLoader.Namespace.SCREENWRITING_CORE, List.of("hongguo-beat-sheet")));
        return List.copyOf(packs);
    }

    public List<RuntimeRulePackLoader.RulePack> director(String spatialComplexity, String assetDependency,
                                                         String performanceDetail) {
        String spatial = normalize(spatialComplexity, "S0");
        String assets = normalize(assetDependency, "A0");
        String performance = normalize(performanceDetail, "BASIC");
        List<RuntimeRulePackLoader.RulePack> packs = new ArrayList<>();
        packs.add(loader.load(RuntimeRulePackLoader.Namespace.DIRECTOR,
                List.of("cinematic-dramaturgy", "camera-specs", "camera-transitions", "segment-splicing", "drama-shot-prompt")));
        List<String> spatialRules = new ArrayList<>(List.of("spatial-reference-system-v3", "asset-spatial-ledger"));
        if (Set.of("S3", "S4").contains(spatial)) spatialRules.add("spatial-topview-camera");
        packs.add(loader.load(RuntimeRulePackLoader.Namespace.SPATIAL, spatialRules));
        List<String> performanceRules = new ArrayList<>(List.of("dialogue-7d", "dialogue-speed"));
        if (Set.of("MICRO_EXPRESSION", "FACS").contains(performance)) performanceRules.add("micro-expression");
        if ("FACS".equals(performance)) performanceRules.add("facs");
        packs.add(loader.load(RuntimeRulePackLoader.Namespace.PERFORMANCE, performanceRules));
        List<String> qa = new ArrayList<>(List.of("quality-gate-review"));
        if (Set.of("A2", "A3").contains(assets)) qa.add("asset-first-pipeline");
        packs.add(loader.load(RuntimeRulePackLoader.Namespace.PRODUCTION_QA, qa));
        return List.copyOf(packs);
    }

    private boolean containsAny(Collection<String> values, String... expected) {
        if (values == null) return false;
        Set<String> normalized = new HashSet<>();
        values.forEach(value -> normalized.add(normalize(value, "")));
        return Arrays.stream(expected).anyMatch(normalized::contains);
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
