package lpg.runtime;

import java.util.Arrays;
import java.util.List;

/**
 * Smoke test for {@link ExpectedTokens} and {@link ParseIssue}.
 * Run: {@code java -cp ... lpg.runtime.ExpectedTokensTest}
 */
public final class ExpectedTokensTest
{
    private static final class MockTable implements ParseTable
    {
        public int getErrorAction() { return 0; }
        public int getNtOffset() { return 4; }
        public int tAction(int state, int sym)
        {
            if (state == 0 && (sym == 1 || sym == 2))
                return 1;
            return 0;
        }
        public int terminalIndex(int sym) { return sym; }
        public String name(int index)
        {
            if (index == 1) return "a";
            if (index == 2) return "b";
            return "";
        }

        public int baseCheck(int i) { return 0; }
        public int rhs(int i) { return 0; }
        public int baseAction(int i) { return 0; }
        public int lhs(int i) { return 0; }
        public int termCheck(int i) { return 0; }
        public int termAction(int i) { return 0; }
        public int asb(int i) { return 0; }
        public int asr(int i) { return 0; }
        public int nasb(int i) { return 0; }
        public int nasr(int i) { return 0; }
        public int nonterminalIndex(int i) { return 0; }
        public int scopePrefix(int i) { return 0; }
        public int scopeSuffix(int i) { return 0; }
        public int scopeLhs(int i) { return 0; }
        public int scopeLa(int i) { return 0; }
        public int scopeStateSet(int i) { return 0; }
        public int scopeRhs(int i) { return 0; }
        public int scopeState(int i) { return 0; }
        public int inSymb(int i) { return 0; }
        public int originalState(int s) { return 0; }
        public int asi(int s) { return 0; }
        public int nasi(int s) { return 0; }
        public int inSymbol(int s) { return 0; }
        public int ntAction(int s, int sym) { return 0; }
        public int lookAhead(int a, int sym) { return 0; }
        public int getErrorSymbol() { return 0; }
        public int getScopeUbound() { return 0; }
        public int getScopeSize() { return 0; }
        public int getMaxNameLength() { return 0; }
        public int getNumStates() { return 0; }
        public int getLaStateOffset() { return 0; }
        public int getMaxLa() { return 0; }
        public int getNumRules() { return 0; }
        public int getNumNonterminals() { return 0; }
        public int getNumSymbols() { return 0; }
        public int getSegmentSize() { return 0; }
        public int getStartState() { return 0; }
        public int getStartSymbol() { return 0; }
        public int getEoftSymbol() { return 0; }
        public int getEoltSymbol() { return 0; }
        public int getAcceptAction() { return 0; }
        public boolean isNullable(int s) { return false; }
        public boolean isValidForParser() { return true; }
        public boolean getBacktrack() { return false; }
    }

    public static void main(String[] args)
    {
        MockTable prs = new MockTable();
        List<String> names = ExpectedTokens.expectedTerminalNames(prs, 0);
        if (!names.equals(Arrays.asList("a", "b")))
            System.exit(1);

        // Use literal ERROR_CODE (=1) to keep this smoke test free of Messages.properties.
        final int errorCode = 1;
        ParseIssue issue = ParseIssue.mismatch(prs, 0, errorCode,
                new SourceSpan(1, 1), "x");
        if (issue.code != errorCode
                || issue.span.startOffset != 1
                || !issue.expected.equals(Arrays.asList("a", "b"))
                || !"x".equals(issue.got))
            System.exit(2);

        System.out.println("ExpectedTokensTest OK");
    }
}
