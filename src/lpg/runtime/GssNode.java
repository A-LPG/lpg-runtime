package lpg.runtime;

import java.util.ArrayList;

/**
 * Graph-structured stack node: LR state at an input index.
 * Predecessor links are {@link GssEdge}s carrying recognized symbols / SPPF labels.
 */
public final class GssNode
{
    final int state;
    final int index;
    final ArrayList<GssEdge> edges = new ArrayList<GssEdge>();

    GssNode(int state, int index)
    {
        this.state = state;
        this.index = index;
    }

    public int getState() { return state; }

    public int getIndex() { return index; }

    public java.util.List<GssEdge> getEdges()
    {
        return java.util.Collections.unmodifiableList(edges);
    }
}
