package lpg.runtime;

import java.util.Collections;
import java.util.List;

/**
 * Unified parse-error shape for agents and editor integrations:
 * {@code code} / {@code span} / {@code expected} / {@code got}.
 */
public final class ParseIssue
{
    public final int code;
    public final SourceSpan span;
    public final List<String> expected;
    public final String got;

    public ParseIssue(int code, SourceSpan span, List<String> expected, String got)
    {
        this.code = code;
        this.span = span;
        this.expected = expected == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(expected);
        this.got = got == null ? "" : got;
    }

    /**
     * Build a mismatch issue with {@code expected} filled from parser state.
     */
    public static ParseIssue mismatch(ParseTable prs, int state, int code,
            SourceSpan span, String got)
    {
        return new ParseIssue(code, span,
                ExpectedTokens.expectedTerminalNames(prs, state), got);
    }
}
