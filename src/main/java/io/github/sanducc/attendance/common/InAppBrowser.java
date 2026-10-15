package io.github.sanducc.attendance.common;

import java.util.regex.Pattern;

/**
 * Google blocks OAuth inside embedded webviews (Instagram, Facebook, ...), so the landing page
 * detects them and tells the student to open the link in Chrome or Safari instead.
 */
public enum InAppBrowser {
    NONE,
    ANDROID,
    IOS;

    private static final Pattern KNOWN_APPS = Pattern.compile(
            "FBAN|FBAV|FB_IAB|Instagram|Line/|Snapchat|TikTok|musical_ly|BytedanceWebview|MicroMessenger|LinkedInApp");

    public static InAppBrowser detect(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return NONE;
        }
        boolean android = userAgent.contains("Android");
        boolean ios = userAgent.contains("iPhone") || userAgent.contains("iPad") || userAgent.contains("iPod");
        boolean embedded = KNOWN_APPS.matcher(userAgent).find()
                || (android && userAgent.contains("; wv)"))
                // Every real iOS browser (Safari, Chrome, Firefox, Edge) includes "Safari/"; WKWebView doesn't.
                || (ios && !userAgent.contains("Safari/"));
        if (!embedded) {
            return NONE;
        }
        return android ? ANDROID : ios ? IOS : NONE;
    }

    public boolean embedded() {
        return this != NONE;
    }
}
