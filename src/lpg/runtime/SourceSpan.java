package lpg.runtime;

/**
 * Source location span for structured parse diagnostics.
 */
public final class SourceSpan
{
    public final int startOffset;
    public final int endOffset;

    public SourceSpan(int startOffset, int endOffset)
    {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }
}
