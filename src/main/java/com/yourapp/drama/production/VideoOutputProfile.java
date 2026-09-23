package com.yourapp.drama.production;

/** Exact provider output geometry for one model/resolution/aspect-ratio combination. */
public record VideoOutputProfile(
        String resolution,String aspectRatioIntent,int expectedWidth,int expectedHeight,
        int tolerancePixels,String verificationStatus) {
    public VideoOutputProfile {
        if(resolution==null||resolution.isBlank()||aspectRatioIntent==null||aspectRatioIntent.isBlank())
            throw new IllegalArgumentException("Video output profile identity is required");
        if(expectedWidth<=0||expectedHeight<=0||tolerancePixels<0)
            throw new IllegalArgumentException("Video output profile geometry is invalid");
    }

    public boolean matches(String requestedResolution,String requestedRatio,int actualWidth,int actualHeight){
        if(!resolution.equals(requestedResolution)||!aspectRatioIntent.equals(requestedRatio))return false;
        boolean expectedPortrait=expectedHeight>expectedWidth,actualPortrait=actualHeight>actualWidth;
        boolean expectedLandscape=expectedWidth>expectedHeight,actualLandscape=actualWidth>actualHeight;
        if(expectedPortrait!=actualPortrait||expectedLandscape!=actualLandscape)return false;
        return Math.abs(actualWidth-expectedWidth)<=tolerancePixels&&Math.abs(actualHeight-expectedHeight)<=tolerancePixels;
    }
}
