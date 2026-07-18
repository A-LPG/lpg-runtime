package lpg.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared packed parse forest node.
 * <p>
 * Symbol nodes are keyed by {@code (grammarSymbol, leftExtent, rightExtent)}
 * and hold one or more {@link Packed} alternatives. Terminal / epsilon leaves
 * may appear as symbol nodes with a single pack and no children.
 */
public final class SppfNode
{
    /** Packed alternative under a symbol node. */
    public static final class Packed
    {
        final int rule;
        final SppfNode[] children;
        final Object semantic;

        Packed(int rule, SppfNode[] children, Object semantic)
        {
            this.rule = rule;
            this.children = children == null ? new SppfNode[0] : children;
            this.semantic = semantic;
        }

        public int getRule() { return rule; }

        public List<SppfNode> getChildren()
        {
            List<SppfNode> out = new ArrayList<SppfNode>(children.length);
            for (SppfNode c : children)
                if (c != null)
                    out.add(c);
            return Collections.unmodifiableList(out);
        }

        public Object getSemantic() { return semantic; }
    }

    final int grammarSymbol;
    final int leftExtent;
    final int rightExtent;
    final ArrayList<Packed> packs = new ArrayList<Packed>();
    /** Canonical {@link IAst} forest projection ({@code nextAst} chain). */
    Object astForest;

    SppfNode(int grammarSymbol, int leftExtent, int rightExtent)
    {
        this.grammarSymbol = grammarSymbol;
        this.leftExtent = leftExtent;
        this.rightExtent = rightExtent;
    }

    public int getGrammarSymbol() { return grammarSymbol; }

    public int getLeftExtent() { return leftExtent; }

    public int getRightExtent() { return rightExtent; }

    public List<Packed> getPacks()
    {
        return Collections.unmodifiableList(packs);
    }

    public Object getAstForest() { return astForest; }
}
