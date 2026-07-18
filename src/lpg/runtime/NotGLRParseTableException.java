package lpg.runtime;

public class NotGLRParseTableException extends Exception
{
    private static final long serialVersionUID = 1L;
    private String str;

    public NotGLRParseTableException()
    {
        str = "NotGLRParseTableException";
    }
    public NotGLRParseTableException(String str)
    {
        this.str = str;
    }
    public String toString() { return str; }
}
