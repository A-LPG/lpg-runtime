package lpg.runtime;

/**
 * GSS edge from a successor node down to a {@link #predecessor}, labeled with
 * the grammar symbol, left extent, semantic value, and optional SPPF node
 * recognized along that step.
 */
public final class GssEdge
{
    final GssNode predecessor;
    final int symbol;
    final int location;
    final Object semantic;
    final SppfNode sppf;

    GssEdge(GssNode predecessor, int symbol, int location, Object semantic, SppfNode sppf)
    {
        this.predecessor = predecessor;
        this.symbol = symbol;
        this.location = location;
        this.semantic = semantic;
        this.sppf = sppf;
    }

    public GssNode getPredecessor() { return predecessor; }

    public int getSymbol() { return symbol; }

    public int getLocation() { return location; }

    public Object getSemantic() { return semantic; }

    public SppfNode getSppf() { return sppf; }
}
