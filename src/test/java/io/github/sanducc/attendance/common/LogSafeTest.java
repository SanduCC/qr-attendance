package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Client-controlled values in log lines: no forged lines, no hidden text, bounded length. */
class LogSafeTest {

    @Test
    void plainValuesAreUnchanged() {
        String path = "/a/7c1e0f6a-1b2c-4d5e-8f90-123456789abc";
        assertThat(LogSafe.of(path)).isEqualTo(path);
        assertThat(LogSafe.of("/teacher/courses?name=Matematică")).isEqualTo("/teacher/courses?name=Matematică");
        assertThat(LogSafe.of(42)).isEqualTo("42");
        assertThat(LogSafe.of(null)).isEqualTo("null");
    }

    @Test
    void lineBreaksCannotForgeALogLine() {
        String forged = "/x\r\n2026-10-30 INFO Admin signed in\n\r";
        assertThat(LogSafe.of(forged)).isEqualTo("/x__2026-10-30 INFO Admin signed in__")
                .doesNotContain("\r").doesNotContain("\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t", "\u001b", "\u0000", "\u007f", "\u0085", " ", " ", "‮", "​",
            "﻿"})
    void otherControlAndInvisibleCharactersAreReplaced(String c) {
        assertThat(LogSafe.of("a" + c + "b")).isEqualTo("a_b");
    }

    @Test
    void longValuesAreCut() {
        String exact = "a".repeat(LogSafe.MAX_LENGTH);
        assertThat(LogSafe.of(exact)).isEqualTo(exact);

        assertThat(LogSafe.of("b".repeat(10_000))).isEqualTo("b".repeat(LogSafe.MAX_LENGTH) + "...");
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String emoji = "😀";
        String value = "a".repeat(LogSafe.MAX_LENGTH - 1) + emoji + "tail";
        assertThat(LogSafe.of(value)).isEqualTo("a".repeat(LogSafe.MAX_LENGTH - 1) + "...");
    }
}
