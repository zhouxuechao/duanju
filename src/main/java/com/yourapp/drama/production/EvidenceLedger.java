package com.yourapp.drama.production;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cross-checks evidence against story facts, character knowledge and physical world state. */
public final class EvidenceLedger {
    public List<ProductionModels.Risk> validate(JsonNode entries, JsonNode facts, JsonNode knowledge, JsonNode world, long storyTime) {
        List<ProductionModels.Risk> risks = new ArrayList<>();
        Set<String> validHolders = new HashSet<>();
        addAll(validHolders, world.path("characterIds")); addAll(validHolders, world.path("locationIds"));
        Set<String> ids = new HashSet<>();
        int index = 0;
        for (JsonNode evidence : entries) {
            String path = "evidenceLedger[" + index++ + "]", id = text(evidence, "evidenceId");
            if (id.isBlank() || !ids.add(id)) error(risks, "DUPLICATE_EVIDENCE_ID", path + ".evidenceId", "证据 ID 为空或重复");
            long formedAt = evidence.path("formedAt").asLong(Long.MIN_VALUE);
            if (formedAt == Long.MIN_VALUE) error(risks, "EVIDENCE_TIME_MISSING", path + ".formedAt", "证据缺少形成时间");
            else if (formedAt > storyTime) error(risks, "EVIDENCE_FROM_FUTURE", path + ".formedAt", "证据在当前故事时间之后才形成");
            String holder = text(evidence, "holder");
            if (!holder.isBlank() && !validHolders.contains(holder)) error(risks, "INVALID_EVIDENCE_HOLDER", path + ".holder", "证据持有人或地点不存在");
            long destroyedAt = evidence.path("destroyedAt").asLong(Long.MAX_VALUE), presentAt = evidence.path("presentAt").asLong(Long.MIN_VALUE);
            if (destroyedAt != Long.MAX_VALUE && presentAt > destroyedAt) error(risks, "DESTROYED_EVIDENCE_REAPPEARED", path, "已销毁证据在后续时间重新出现");
            for (JsonNode factId : evidence.path("supportsFacts")) {
                JsonNode fact = facts.path(factId.asText());
                if (fact.isMissingNode()) error(risks, "UNKNOWN_SUPPORTED_FACT", path + ".supportsFacts", "证据引用了不存在的故事事实");
                else if ("ESTABLISHED".equals(text(fact, "status")) && !evidence.path("verified").asBoolean(false))
                    error(risks, "UNVERIFIED_EVIDENCE_USED_AS_FACT", path + ".verified", "未经验证的证据被当作已成立事实");
            }
            for (JsonNode character : evidence.path("knownBy")) {
                String characterId = character.asText();
                if (!validHolders.contains(characterId)) error(risks, "UNKNOWN_KNOWLEDGE_OWNER", path + ".knownBy", "知情角色不存在");
                long knownAt = knowledge.path(characterId).path(id).asLong(Long.MIN_VALUE);
                if (knownAt != Long.MIN_VALUE && formedAt != Long.MIN_VALUE && knownAt < formedAt)
                    error(risks, "KNOWLEDGE_BEFORE_OBTAINED", path + ".knownBy", "角色在证据形成前已经知情");
            }
        }
        return List.copyOf(risks);
    }

    private static void addAll(Set<String> target, JsonNode source) { if (source.isArray()) source.forEach(v -> target.add(v.asText())); }
    private static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }
    private static void error(List<ProductionModels.Risk> risks, String code, String path, String message) { risks.add(new ProductionModels.Risk(code, "ERROR", path, message)); }
}
