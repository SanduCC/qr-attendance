package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Google blocks OAuth in embedded webviews; real browsers must not be flagged. */
class InAppBrowserTest {

    @ParameterizedTest
    @ValueSource(strings = {
            // Instagram on Android
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/127.0.6533.103 Mobile Safari/537.36 Instagram 343.0.0.33.101 Android "
                    + "(34/14; 420dpi; 1080x2205; Google/google; Pixel 8; shiba; shiba; en_US; 628472912)",
            // Facebook on Android
            "Mozilla/5.0 (Linux; Android 13; SM-A536B Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/126.0.6478.134 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/473.0.0.47.109;]",
            // WhatsApp / any generic Android WebView
            "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/127.0.6533.103 Mobile Safari/537.36",
            // TikTok on Android
            "Mozilla/5.0 (Linux; Android 12; Redmi Note 11) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36 musical_ly_2023405030 BytedanceWebview/d8a21c6",
    })
    void detectsAndroidInAppBrowsers(String userAgent) {
        InAppBrowser result = InAppBrowser.detect(userAgent);

        assertThat(result).isEqualTo(InAppBrowser.ANDROID);
        assertThat(result.embedded()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Instagram on iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Mobile/15E148 Instagram 337.0.3.23.54 (iPhone14,5; iOS 17_5_1; en_US; en; scale=3.00; 1170x2532)",
            // Facebook on iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Mobile/15E148 [FBAN/FBIOS;FBAV/470.0.0.40.97;FBBV/621001592;FBDV/iPhone14,5;FBMD/iPhone;"
                    + "FBSN/iOS;FBSV/17.5;FBSS/3;FBID/phone;FBLC/en_US;FBOP/5]",
            // Generic WKWebView (e.g. WhatsApp on iOS): no "Safari/" token
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
    })
    void detectsIosInAppBrowsers(String userAgent) {
        InAppBrowser result = InAppBrowser.detect(userAgent);

        assertThat(result).isEqualTo(InAppBrowser.IOS);
        assertThat(result.embedded()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            // Chrome on Android
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 "
                    + "Mobile Safari/537.36",
            // Samsung Internet
            "Mozilla/5.0 (Linux; Android 14; SAMSUNG SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36",
            // Firefox on Android
            "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0",
            // Safari on iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.5 Mobile/15E148 Safari/604.1",
            // Chrome on iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "CriOS/127.0.6533.77 Mobile/15E148 Safari/604.1",
            // Firefox on iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "FxiOS/128.0 Mobile/15E148 Safari/605.1.15",
            // Safari on iPad (iPadOS 13+ reports a desktop UA)
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.5 Safari/605.1.15",
            // Desktop Chrome
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 "
                    + "Safari/537.36",
    })
    void doesNotFlagRealBrowsers(String userAgent) {
        InAppBrowser result = InAppBrowser.detect(userAgent);

        assertThat(result).isEqualTo(InAppBrowser.NONE);
        assertThat(result.embedded()).isFalse();
    }
}
