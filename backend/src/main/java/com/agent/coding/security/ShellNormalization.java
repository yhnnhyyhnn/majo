package com.agent.coding.security;

/**
 * Normalization shared by shell security checks and shell execution.
 * Ported from QwenPaw {@code utils/shell_normalization.py}.
 *
 * <p>POSIX shells remove an unquoted backslash followed by a newline before
 * tokenization. A security check that examines the pre-removal spelling can
 * therefore see a different path or command from the one the shell executes
 * (e.g. {@code /etc/pa\<newline>sswd} bypasses a sensitive-path check).
 *
 * <p>Backslash-newline pairs inside single quotes are literal and must
 * remain. The pairs are continuations both outside quotes and inside double
 * quotes. CRLF is accepted as a continuation as well so callers cannot
 * create the same parser differential with JSON/text produced on Windows.
 */
public final class ShellNormalization {

    private ShellNormalization() {}

    /**
     * Remove POSIX {@code \} + newline continuations from {@code command}.
     *
     * @return the command with continuations joined, or the original string
     *         when it contains no continuation
     */
    public static String normalizePosixLineContinuations(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        if (!command.contains("\\\n") && !command.contains("\\\r\n")) {
            return command;
        }

        StringBuilder result = new StringBuilder(command.length());
        char quote = 0; // 0 = outside quotes, '\'' = single, '"' = double
        int index = 0;
        int length = command.length();

        while (index < length) {
            char c = command.charAt(index);

            if (quote == '\'') {
                result.append(c);
                if (c == '\'') {
                    quote = 0;
                }
                index++;
                continue;
            }

            if (c == '\\') {
                if (index + 1 < length && command.charAt(index + 1) == '\n') {
                    index += 2;
                    continue;
                }
                if (index + 2 < length
                        && command.charAt(index + 1) == '\r'
                        && command.charAt(index + 2) == '\n') {
                    index += 3;
                    continue;
                }
                // Preserve the escaped character and prevent an escaped quote
                // from changing the quote state tracked below.
                result.append(c);
                if (index + 1 < length) {
                    result.append(command.charAt(index + 1));
                    index += 2;
                } else {
                    index++;
                }
                continue;
            }

            result.append(c);
            if (c == '"') {
                quote = (quote == '"') ? 0 : '"';
            } else if (c == '\'' && quote == 0) {
                quote = '\'';
            }
            index++;
        }

        return result.toString();
    }

    /** Whether the current host executes shell commands through a POSIX shell. */
    public static boolean isPosixHost() {
        return !System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
