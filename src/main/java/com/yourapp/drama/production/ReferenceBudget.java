package com.yourapp.drama.production;

/** Auditable hard/recommended reference budget captured on every provider request. */
public record ReferenceBudget(VideoModelProfile.ReferenceLimits hardLimits,
                              VideoModelProfile.ReferenceLimits recommendedLimits,
                              int selectedCount,int excludedCount) {}
