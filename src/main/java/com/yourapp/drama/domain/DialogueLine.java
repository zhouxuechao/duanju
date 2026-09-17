package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DialogueLine(String id, String projectId, String shotId, String characterId,
                           String displayText, String dialectText, String speechText,
                           String dialect, double dialectStrength, String voiceId, long revision) {}
