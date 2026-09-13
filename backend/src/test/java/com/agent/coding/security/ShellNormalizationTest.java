package com.agent.coding.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for POSIX line-continuation normalization (QwenPaw #7472 port). */
class ShellNormalizationTest {

    @Test
    void passesThroughCommandsWithoutContinuations() {
        assertEquals("rm -rf /tmp/x",
                ShellNormalization.normalizePosixLineContinuations("rm -rf /tmp/x"));
        assertEquals("", ShellNormalization.normalizePosixLineContinuations(""));
    }

    @Test
    void joinsSimpleContinuations() {
        String cmd = "cat /etc/pa\\\nsswd";
        assertEquals("cat /etc/passwd",
                ShellNormalization.normalizePosixLineContinuations(cmd));
    }

    @Test
    void joinsCrlfContinuations() {
        assertEquals("cat /etc/passwd",
                ShellNormalization.normalizePosixLineContinuations("cat /etc/pa\\\r\nsswd"));
    }

    @Test
    void preservesSingleQuotedBackslashNewline() {
        String cmd = "echo 'a\\\nb'";
        assertEquals(cmd, ShellNormalization.normalizePosixLineContinuations(cmd));
    }

    @Test
    void removesContinuationsInsideDoubleQuotes() {
        assertEquals("echo \"ab\"",
                ShellNormalization.normalizePosixLineContinuations("echo \"a\\\nb\""));
    }

    @Test
    void preservesEscapedCharactersAndQuoteState() {
        // Escaped quote must not open a single-quote region; the pair is
        // preserved verbatim (same as the Python reference).
        assertEquals("echo \\'x\\' y",
                ShellNormalization.normalizePosixLineContinuations("echo \\'x\\' y"));
    }

    @Test
    void joinsMultipleContinuations() {
        // Only the backslash-newline pair is removed; surrounding spaces
        // remain (the shell collapses them at tokenization time).
        assertEquals("tar -xzvf archive.tar.gz   --directory   /opt",
                ShellNormalization.normalizePosixLineContinuations(
                        "tar -xzvf archive.tar.gz \\\n  --directory \\\n  /opt"));
    }
}
