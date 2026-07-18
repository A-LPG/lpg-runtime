package lpg.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Expected-terminals helper for editor completion (antlr4-c3 style).
 *
 * For parser state S, return sorted distinct terminal names where
 * tAction(S, sym) is not ERROR_ACTION. Names come from ParseTable.name
 * via terminalIndex(sym). Terminal symbol ids are 1 .. getNtOffset()-1
 * (0 is unused / error slot in LPG tables).
 */
public final class ExpectedTokens
{
    private ExpectedTokens() {}

    public static List<String> expectedTerminalNames(ParseTable prs, int state)
    {
        if (prs == null)
            return Collections.emptyList();

        final int errorAction = prs.getErrorAction();
        final int ntOffset = prs.getNtOffset();
        TreeSet<String> unique = new TreeSet<String>();
        for (int sym = 1; sym < ntOffset; sym++) {
            int act = prs.tAction(state, sym);
            if (act == errorAction)
                continue;
            String n = prs.name(prs.terminalIndex(sym));
            if (n != null && n.length() > 0)
                unique.add(n);
        }
        return new ArrayList<String>(unique);
    }
}
