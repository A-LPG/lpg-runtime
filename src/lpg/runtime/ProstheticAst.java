package lpg.runtime;

//
// A ProstheticAst is a factory that synthesizes an AST node for a "recover"
// nonterminal. When the backtracking parser replays a nonterminal ErrorToken
// that was inserted by scope recovery, it asks the RuleAction for its
// prostheticAst array and invokes create(error_token) to build a placeholder
// (prosthetic) node in place of throwing a BadParseException.
//
public interface ProstheticAst
{
    IAst create(IToken error_token);
}
