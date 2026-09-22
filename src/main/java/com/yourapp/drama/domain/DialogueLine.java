package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DialogueLine(String id, String projectId, String shotId, String characterId,
                           String semanticText, String spokenText, String subtitleText,
                           String dialect, double dialectStrength, String voiceId, long revision) {}
