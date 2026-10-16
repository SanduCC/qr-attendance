package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

/** Issue #39: the client IP is the X-Forwarded-For entry a trusted proxy appended, never one the client wrote. */
class ForwardedClientIpTest {

    /** What the container sees as the TCP peer on Cloud Run: Google's proxy, the same for every client. */
    private static final String PROXY_PEER = "169.254.169.126";
    private static final String CLIENT = "198.51.100.7";

    // ---------------------------------------------------------------- one trusted proxy (Cloud Run run.app)

    @Test
    void aSpoofedEntryInFrontOfTheProxysEntryIsIgnored() {
        assertThat(ForwardedClientIp.resolve(List.of("1.2.3.4, " + CLIENT), PROXY_PEER, 1)).isEqualTo(CLIENT);
    }

    @Test
    void manySpoofedEntriesAreIgnoredToo() {
        assertThat(ForwardedClientIp.resolve(List.of("10.0.0.1, 1.2.3.4,203.0.113.9 ," + CLIENT), PROXY_PEER, 1))
                .isEqualTo(CLIENT);
    }

    @Test
    void aClientThatSendsNoHeaderGetsTheProxysEntry() {
        assertThat(ForwardedClientIp.resolve(List.of(CLIENT), PROXY_PEER, 1)).isEqualTo(CLIENT);
    }

    @Test
    void noHeaderMeansTheTcpPeer() {
        assertThat(ForwardedClientIp.resolve(List.of(), PROXY_PEER, 1)).isEqualTo(PROXY_PEER);
        assertThat(ForwardedClientIp.resolve(List.of(" ", ","), PROXY_PEER, 1)).isEqualTo(PROXY_PEER);
    }

    @Test
    void theClientsOwnHeaderLineAndTheProxysLineCountAsOneList() {
        assertThat(ForwardedClientIp.resolve(List.of("1.2.3.4", CLIENT), PROXY_PEER, 1)).isEqualTo(CLIENT);
    }

    @Test
    void anUnusableRightMostEntryFallsBackToTheTcpPeerNotToAClientEntry() {
        assertThat(ForwardedClientIp.resolve(List.of("1.2.3.4, unknown"), PROXY_PEER, 1)).isEqualTo(PROXY_PEER);
    }

    @Test
    void aTrailingCommaFromTheClientDoesNotShiftTheProxysEntry() {
        assertThat(ForwardedClientIp.resolve(List.of("1.2.3.4, , " + CLIENT), PROXY_PEER, 1)).isEqualTo(CLIENT);
    }

    // ---------------------------------------------------------------- several hops

    @Test
    void twoHopsTakeTheSecondEntryFromTheRight() {
        // An external Application Load Balancer appends "<client-ip>,<load-balancer-ip>".
        String header = "1.2.3.4, " + CLIENT + ", 34.111.22.33";
        assertThat(ForwardedClientIp.resolve(List.of(header), PROXY_PEER, 2)).isEqualTo(CLIENT);
        assertThat(ForwardedClientIp.resolve(List.of(header), PROXY_PEER, 1)).isEqualTo("34.111.22.33");
    }

    @Test
    void threeHops() {
        String header = "1.2.3.4, " + CLIENT + ", 10.1.2.3, 34.111.22.33";
        assertThat(ForwardedClientIp.resolve(List.of(header), PROXY_PEER, 3)).isEqualTo(CLIENT);
    }

    @Test
    void fewerEntriesThanHopsMeansAllCameFromTrustedProxiesSoTheLeftMostIsTheClient() {
        assertThat(ForwardedClientIp.resolve(List.of(CLIENT), PROXY_PEER, 2)).isEqualTo(CLIENT);
        assertThat(ForwardedClientIp.resolve(List.of(CLIENT + ", 34.111.22.33"), PROXY_PEER, 5)).isEqualTo(CLIENT);
    }

    @Test
    void zeroHopsTrustsNoHeader() {
        assertThat(ForwardedClientIp.resolve(List.of("1.2.3.4, " + CLIENT), PROXY_PEER, 0)).isEqualTo(PROXY_PEER);
    }

    // ---------------------------------------------------------------- entry formats

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "198.51.100.7          | 198.51.100.7",
            "' 198.51.100.7 '      | 198.51.100.7",
            "198.51.100.7:51234    | 198.51.100.7",
            "'\"198.51.100.7\"'    | 198.51.100.7",
            "2001:DB8::1           | 2001:db8:0:0:0:0:0:1",
            "[2001:db8::1]         | 2001:db8:0:0:0:0:0:1",
            "[2001:db8::1]:443     | 2001:db8:0:0:0:0:0:1",
            "'\"[2001:db8::1]\"'   | 2001:db8:0:0:0:0:0:1",
            "::ffff:198.51.100.7   | 198.51.100.7"})
    void entriesAreNormalizedSoOneClientIsOneRateLimitKey(String entry, String expected) {
        assertThat(ForwardedClientIp.normalize(entry)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "evil.example", "localhost", "_hidden", "198.51.100.7; for=1.2.3.4",
            "[2001:db8::1", "fe80::1%eth0", "1.2.3.4.5", "", "\"\"",
            "1111:2222:3333:4444:5555:6666:7777:8888:9999:aaaa:bbbb:cccc:dddd:eeee:ffff"})
    void anythingButAnIpAddressIsRejectedWithoutADnsLookup(String entry) {
        assertThat(ForwardedClientIp.normalize(entry)).isNull();
    }

    // ---------------------------------------------------------------- from a request

    @Test
    void readsEveryHeaderLineOfTheRawRequestAndIgnoresTheForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x");
        request.setRemoteAddr(PROXY_PEER);
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Forwarded-For", CLIENT);
        request.addHeader("Forwarded", "for=203.0.113.50");
        request.addHeader("Forwarded", "for=\"" + CLIENT + "\";proto=https");

        assertThat(ForwardedClientIp.resolve(request, 1)).isEqualTo(CLIENT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 5})
    void theDebugSelfCheckGivesTheSameAnswer(int hops) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.github.sanducc.attendance.clientip");
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x");
            request.setRemoteAddr(PROXY_PEER);
            request.addHeader("X-Forwarded-For", "1.2.3.4, " + CLIENT);
            request.addHeader("Forwarded", "for=203.0.113.50, for=\"" + CLIENT + "\";proto=https");

            assertThat(ForwardedClientIp.resolve(request, hops))
                    .isEqualTo(ForwardedClientIp.resolve(List.of("1.2.3.4, " + CLIENT), PROXY_PEER, hops));
        } finally {
            logger.setLevel(before);
        }
    }

    @Test
    void aRequestWithoutTheHeaderIsItsTcpPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x");
        request.setRemoteAddr(PROXY_PEER);
        request.addHeader("Forwarded", "for=203.0.113.50");

        assertThat(ForwardedClientIp.resolve(request, 1)).isEqualTo(PROXY_PEER);
    }
}
