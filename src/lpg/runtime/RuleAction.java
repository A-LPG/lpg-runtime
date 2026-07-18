package lpg.runtime;

public interface RuleAction
{
    void ruleAction(int ruleNumber);

    //
    // Parsers generated with automatic_ast and %Recover symbols override this
    // to return an array of prosthetic-AST factories indexed by the value of
    // ParseTable.getProsthesisIndex(kind). The default (no recover symbols)
    // returns null, in which case the backtracking parser keeps its historical
    // behavior of throwing a BadParseException on a replayed nonterminal token.
    //
    default ProstheticAst[] getProstheticAst() { return null; }

    //
    // GLR parsers that fall back to BacktrackingParser for %Recover replay
    // override these so rule-action span/sym accessors hit the BT stacks.
    //
    default void setRecoverParser(BacktrackingParser parser) {}
    default BacktrackingParser getRecoverParser() { return null; }
}
