package com.yourapp.drama.production;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** The single runtime entry point for vendored production knowledge. */
@Component
public class RuntimeRulePackLoader {
    public static final String SHORT_DRAMA_FACTORY_COMMIT = "edd0df754320c2f3949fb198cea7847c71d0cde0";
    public static final String MANJU_LAOLI_COMMIT = "079df685f7cf2f0de635362bd359c233db38f9fe";
    public static final String OIUV_COMMIT = "4f318097c54a2e24ea34d3c9d23d30f5ec332f11";
    public static final String SEEDANCE_25_UPSTREAM_COMMIT = OIUV_COMMIT;

    public enum Namespace {
        SCREENWRITING_CORE, STORY_TYPE, STORY_PATTERN,
        DIRECTOR, PERFORMANCE, SPATIAL, PRODUCTION_QA,
        PROVIDER_SEEDANCE_20, PROVIDER_SEEDANCE_25,
        PROVIDER_GENERIC
    }

    public record RuleFragment(String ruleId, Namespace namespace, String content, String contentHash,
                               int priority, String sourceRepo, String upstreamCommit, String sourcePath) {
        public String source() { return resourcePath(namespace, ruleId); }
    }

    public record RulePack(Namespace namespace, List<String> ruleIds, String content, String upstreamCommit,
                           String fingerprint, List<RuleFragment> rules) {
        public RulePack { ruleIds = List.copyOf(ruleIds); rules = List.copyOf(rules); }
        public RulePack(Namespace namespace, List<String> ruleIds, String content, String upstreamCommit, String fingerprint) {
            this(namespace, ruleIds, content, upstreamCommit, fingerprint, List.of());
        }
    }

    private record RuleSource(String resource, String sourceRepo, String commit, String sourcePath, int priority) {}
    private static final Map<String, RuleSource> SOURCES = sources();

    public RulePack load(Namespace requestedNamespace, Collection<String> requested) {
        Namespace namespace = requestedNamespace;
        LinkedHashSet<String> ids = new LinkedHashSet<>(requested == null ? List.of() : requested);
        if (ids.isEmpty()) throw new IllegalArgumentException("RULE_PACK_EMPTY: " + namespace);
        StringBuilder combined = new StringBuilder();
        List<RuleFragment> fragments = new ArrayList<>();
        LinkedHashSet<String> commits = new LinkedHashSet<>();
        for (String id : ids) {
            if (!id.matches("[a-z0-9-]+")) throw new IllegalArgumentException("RULE_ID_INVALID: " + id);
            RuleSource source = SOURCES.get(key(namespace, id));
            if (source == null) throw new IllegalArgumentException("RULE_RESOURCE_MISSING: " + namespace + "/" + id);
            String content = read(source.resource()).trim();
            if (combined.length() > 0) combined.append("\n\n");
            combined.append("## ").append(id).append('\n').append(content);
            commits.add(source.commit());
            fragments.add(new RuleFragment(id, namespace, content, sha256(content), source.priority(),
                    source.sourceRepo(), source.commit(), source.sourcePath()));
        }
        String content = combined.toString();
        String commitIdentity = String.join(",", commits);
        String hashes = fragments.stream().map(RuleFragment::contentHash).reduce((a, b) -> a + "," + b).orElse("");
        return new RulePack(namespace, List.copyOf(ids), content, commitIdentity,
                sha256(namespace + "|" + commitIdentity + "|" + hashes), fragments);
    }

    private static Map<String, RuleSource> sources() {
        Map<String, RuleSource> rules = new LinkedHashMap<>();
        String sdf = "/development-skills/vendor/short-drama-factory/";
        add(rules, Namespace.SCREENWRITING_CORE, "character-bible", sdf + "references/character-bible.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/character-bible.md", 980);
        add(rules, Namespace.SCREENWRITING_CORE, "emotion-contract", sdf + "references/emotion-flow-roundtrip.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/emotion-flow-roundtrip.md", 970);
        add(rules, Namespace.SCREENWRITING_CORE, "continuity-ledger", sdf + "references/continuity-ledger.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/continuity-ledger.md", 960);
        add(rules, Namespace.SCREENWRITING_CORE, "hook-library", sdf + "references/golden-3s-hook-library.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/golden-3s-hook-library.md", 850);
        add(rules, Namespace.SCREENWRITING_CORE, "cliffhanger-formulas", sdf + "references/cliffhanger-master-formulas.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/cliffhanger-master-formulas.md", 850);
        add(rules, Namespace.SCREENWRITING_CORE, "dialogue-doctor", sdf + "references/dialogue-doctor-anti-ai.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/dialogue-doctor-anti-ai.md", 900);
        add(rules, Namespace.SCREENWRITING_CORE, "screenplay-compliance", sdf + "references/screenplay-compliance-rules.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/screenplay-compliance-rules.md", 990);
        add(rules, Namespace.SCREENWRITING_CORE, "episode-format", sdf + "templates/episode-format.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "templates/episode-format.md", 930);
        add(rules, Namespace.SCREENWRITING_CORE, "hongguo-beat-sheet", sdf + "references/hongguo-beat-sheet.md", "lixiaoxiao9888-create/short-drama-factory", SHORT_DRAMA_FACTORY_COMMIT, "references/hongguo-beat-sheet.md", 700);

        String oiuv = "/development-skills/vendor/oiuv-ai-short-drama/";
        add(rules, Namespace.STORY_PATTERN, "script-brief", oiuv + "script-brief/SKILL.md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/script-brief/SKILL.md", 960);
        add(rules, Namespace.STORY_TYPE, "satisfaction-model", oiuv + "drama-script/references/satisfaction-model.md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/drama-script/references/satisfaction-model.md", 820);
        add(rules, Namespace.STORY_PATTERN, "theme-patterns", oiuv + "drama-script/references/theme-patterns.md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/drama-script/references/theme-patterns.md", 650);
        add(rules, Namespace.STORY_PATTERN, "template-analysis", oiuv + "drama-script/references/template-analysis.md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/drama-script/references/template-analysis.md", 300);
        add(rules, Namespace.DIRECTOR, "drama-shot-prompt", oiuv + "drama-shot-prompt/SKILL.md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/drama-shot-prompt/SKILL.md", 760);
        add(rules, Namespace.PROVIDER_SEEDANCE_20, "sd2-pe", oiuv + "sd2-pe/SKILL.md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/sd2-pe/SKILL.md", 900);

        String manju = "/development-skills/vendor/manju-laoli-skill/short-drama-director/references/";
        add(rules, Namespace.DIRECTOR, "cinematic-dramaturgy", manju + "cinematic-dramaturgy-rules.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/cinematic-dramaturgy-rules.md", 950);
        add(rules, Namespace.DIRECTOR, "camera-specs", manju + "camera-specs-15rules.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/camera-specs-15rules.md", 900);
        add(rules, Namespace.DIRECTOR, "camera-transitions", manju + "camera-transitions-6types.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/camera-transitions-6types.md", 820);
        add(rules, Namespace.DIRECTOR, "segment-splicing", manju + "segment-splicing.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/segment-splicing.md", 860);
        add(rules, Namespace.PERFORMANCE, "dialogue-7d", manju + "dialogue-doctor-7d.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/dialogue-doctor-7d.md", 900);
        add(rules, Namespace.PERFORMANCE, "dialogue-speed", manju + "dialogue-speed-check.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/dialogue-speed-check.md", 890);
        add(rules, Namespace.PERFORMANCE, "micro-expression", manju + "wenxi-micro-expression.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/wenxi-micro-expression.md", 760);
        add(rules, Namespace.PERFORMANCE, "facs", manju + "★ facs-micro-expression.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/★ facs-micro-expression.md", 500);
        add(rules, Namespace.SPATIAL, "spatial-reference-system-v3", manju + "spatial-reference-system-V3.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/spatial-reference-system-V3.md", 980);
        add(rules, Namespace.SPATIAL, "spatial-topview-camera", manju + "spatial-topview-camera.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/spatial-topview-camera.md", 600);
        add(rules, Namespace.SPATIAL, "asset-spatial-ledger", manju + "asset-spatial-ledger.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/asset-spatial-ledger.md", 920);
        add(rules, Namespace.PRODUCTION_QA, "quality-gate-review", manju + "quality-gate-review.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/quality-gate-review.md", 990);
        add(rules, Namespace.PRODUCTION_QA, "asset-first-pipeline", manju + "asset-first-pipeline.md", "lixiaoxiao9888-create/manju-laoli-skill", MANJU_LAOLI_COMMIT, "short-drama-director/references/asset-first-pipeline.md", 880);

        String compact25 = "/provider-rules/seedance-2.5/";
        for (String id : List.of("material-authority", "parameter-separation", "spatial-keyframes", "storyboard-grid", "locked-routing", "unlocked-routing"))
            add(rules, Namespace.PROVIDER_SEEDANCE_25, id, compact25 + id + ".md", "oiuv/ai-short-drama", OIUV_COMMIT, "skills/sd25-pe/SKILL.md#" + id, 900);
        return Map.copyOf(rules);
    }

    private static void add(Map<String, RuleSource> rules, Namespace namespace, String id, String resource,
                            String repo, String commit, String sourcePath, int priority) {
        rules.put(key(namespace, id), new RuleSource(resource, repo, commit, sourcePath, priority));
    }

    private static String key(Namespace namespace, String id) { return namespace + ":" + id; }

    private String read(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("RULE_RESOURCE_MISSING: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("RULE_RESOURCE_READ_FAILED: " + path, error);
        }
    }

    private static String resourcePath(Namespace namespace, String id) {
        RuleSource source = SOURCES.get(key(namespace, id));
        return source == null ? "" : source.resource();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
