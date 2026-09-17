package com.yourapp.drama.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Identity is provider-bound; current wardrobe belongs to a separate character look. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CharacterAsset(String id, String projectId, String name, String provider,
                             String sourceType, String providerAssetId, String providerStatus,
                             boolean identityLocked, String baseLookId, String referenceImageUrl,
                             long revision) {}
