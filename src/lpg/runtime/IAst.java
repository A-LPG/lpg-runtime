package lpg.runtime;

public interface IAst
{
    public IAst getNextAst();

    /**
     * Link an alternate parse of the same span (GLR local ambiguity packing).
     * Default is a no-op; grammars generated with {@code -glr} override this
     * on the AST root class.
     */
    default void setNextAst(IAst n) {}

    public IAst getParent();
    public IToken getLeftIToken();
    public IToken getRightIToken();
    public IToken[] getPrecedingAdjuncts();
    public IToken[] getFollowingAdjuncts();
    public java.util.ArrayList getChildren();
    public java.util.ArrayList getAllChildren();
    public void accept(IAstVisitor v);
}


