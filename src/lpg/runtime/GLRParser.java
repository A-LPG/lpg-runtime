package lpg.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Tomita-style GLR driver over LPG backtrack/GLR conflict tables.
 * <p>
 * Conflict encoding matches {@link BacktrackingParser}: when
 * {@code tAction(state, kind) > ACCEPT_ACTION}, candidates are the
 * 0-terminated sequence {@code baseAction(act), baseAction(act+1), ...}.
 * Each candidate uses the same shift / reduce / shift-reduce classification
 * as {@link DeterministicParser}.
 * <p>
 * Token positions are absolute indices ({@link TokenStream#getNext}); the
 * shared stream cursor is not advanced during forking. Ambiguous results for
 * the same span are packed with {@link IAst#setNextAst}. Cyclic / ε-loop
 * grammars are rejected via an iteration limit (GLR v1).
 */
public class GLRParser extends Stacks
{
    private Monitor monitor;
    private int START_STATE,
                NUM_RULES,
                LA_STATE_OFFSET,
                ACCEPT_ACTION,
                ERROR_ACTION;

    private TokenStream tokStream;
    private ParseTable prs;
    private RuleAction ra;

    private boolean taking_actions = false;
    private int currentAction;
    private int lastToken;
    private int frameTop;
    private int[] frameLocation;
    private Object[] frameParse;

    private static final class Config
    {
        int[] stateStack;
        Object[] parseStack;
        int[] locationStack;
        int stateStackTop;
        int currentAction;
        int curtok;
        int lastToken;
        int currentKind;

        Config copy()
        {
            Config c = new Config();
            c.stateStackTop = stateStackTop;
            c.currentAction = currentAction;
            c.curtok = curtok;
            c.lastToken = lastToken;
            c.currentKind = currentKind;
            if (stateStack != null)
            {
                c.stateStack = Arrays.copyOf(stateStack, stateStack.length);
                c.parseStack = Arrays.copyOf(parseStack, parseStack.length);
                c.locationStack = Arrays.copyOf(locationStack, locationStack.length);
            }
            return c;
        }

        String key()
        {
            StringBuilder b = new StringBuilder();
            b.append(curtok).append('|').append(currentAction).append('|');
            for (int i = 0; i <= stateStackTop; i++)
                b.append(stateStack[i]).append(',');
            return b.toString();
        }
    }

    private int lookahead(int act, int token)
    {
        act = prs.lookAhead(act - LA_STATE_OFFSET, tokStream.getKind(token));
        return (act > LA_STATE_OFFSET
                    ? lookahead(act, tokStream.getNext(token))
                    : act);
    }

    /** Act on {@code sym} in {@code state}, with lookahead past {@code curtok}. */
    private int tAction(int state, int sym, int curtok)
    {
        int act = prs.tAction(state, sym);
        return (act > LA_STATE_OFFSET
                    ? lookahead(act, tokStream.getNext(curtok))
                    : act);
    }

    /**
     * Expand a conflict entry ({@code ACCEPT_ACTION < act < ERROR_ACTION}) into
     * concrete candidates. Same walk as {@link BacktrackingParser}.
     */
    private void expandConflict(int act, ArrayList<Integer> out)
    {
        for (int i = act; ; i++)
        {
            int cand = prs.baseAction(i);
            if (cand == 0)
                break;
            out.add(Integer.valueOf(cand));
        }
    }

    public final int getCurrentRule()
    {
        if (taking_actions)
            return currentAction;
        throw new UnavailableParserInformationException();
    }

    public final int getToken(int i)
    {
        if (taking_actions)
            return frameLocation[frameTop + (i - 1)];
        return super.getToken(i);
    }

    public final Object getSym(int i)
    {
        if (taking_actions)
            return frameParse[frameTop + (i - 1)];
        return super.getSym(i);
    }

    public final void setSym1(Object ast)
    {
        if (taking_actions)
            frameParse[frameTop] = ast;
        else
            super.setSym1(ast);
    }

    public final int getFirstToken()
    {
        if (taking_actions)
            return getToken(1);
        throw new UnavailableParserInformationException();
    }

    public final int getFirstToken(int i)
    {
        if (taking_actions)
            return getToken(i);
        throw new UnavailableParserInformationException();
    }

    public final int getLastToken()
    {
        if (taking_actions)
            return lastToken;
        throw new UnavailableParserInformationException();
    }

    public final int getLastToken(int i)
    {
        if (taking_actions)
            return (i >= prs.rhs(currentAction)
                       ? lastToken
                       : tokStream.getPrevious(getToken(i + 1)));
        throw new UnavailableParserInformationException();
    }

    public void setMonitor(Monitor monitor) { this.monitor = monitor; }

    public void reset(Monitor monitor, TokenStream tokStream)
    {
        this.monitor = monitor;
        this.tokStream = tokStream;
        taking_actions = false;
    }

    public void reset(TokenStream tokStream)
    {
        reset(null, tokStream);
    }

    public void reset(Monitor monitor, TokenStream tokStream, ParseTable prs, RuleAction ra)
        throws BadParseSymFileException, NotGLRParseTableException
    {
        reset(monitor, tokStream);
        this.prs = prs;
        this.ra = ra;

        START_STATE = prs.getStartState();
        NUM_RULES = prs.getNumRules();
        LA_STATE_OFFSET = prs.getLaStateOffset();
        ACCEPT_ACTION = prs.getAcceptAction();
        ERROR_ACTION = prs.getErrorAction();

        if (!prs.isValidForParser())
            throw new BadParseSymFileException();
        if (!prs.isGLR())
            throw new NotGLRParseTableException();
    }

    public void reset(TokenStream tokStream, ParseTable prs, RuleAction ra)
        throws BadParseSymFileException, NotGLRParseTableException
    {
        reset(null, tokStream, prs, ra);
    }

    public GLRParser() {}

    public GLRParser(TokenStream tokStream, ParseTable prs, RuleAction ra)
        throws BadParseSymFileException, NotGLRParseTableException
    {
        reset(null, tokStream, prs, ra);
    }

    public GLRParser(Monitor monitor, TokenStream tokStream, ParseTable prs, RuleAction ra)
        throws BadParseSymFileException, NotGLRParseTableException
    {
        reset(monitor, tokStream, prs, ra);
    }

    public Object parse() throws BadParseException
    {
        return parseEntry(0);
    }

    public Object parseEntry(int marker_kind) throws BadParseException
    {
        if (marker_kind != 0)
            throw new BadParseException(0);

        tokStream.reset();
        int firstTok = tokStream.getToken();
        int firstKind = tokStream.getKind(firstTok);
        int prev = tokStream.getPrevious(firstTok);

        Config start = new Config();
        start.stateStackTop = -1;
        start.currentAction = START_STATE;
        start.curtok = firstTok;
        start.lastToken = prev;
        start.currentKind = firstKind;
        ensureCapacity(start, 16);

        ArrayList<Config> live = new ArrayList<Config>();
        live.add(start);

        ArrayList<Object> accepts = new ArrayList<Object>();
        int errorTok = firstTok;
        int outerGuard = prs.getNumStates() * 64 + tokStream.getStreamLength() * 8 + 256;

        while (!live.isEmpty())
        {
            if (monitor != null && monitor.isCancelled())
                return null;
            if (--outerGuard < 0)
                throw new RuntimeException(
                    "cyclic/ε-loop grammar not supported by GLR v1");

            ArrayList<Config> next = new ArrayList<Config>();
            HashMap<String, Config> packed = new HashMap<String, Config>();

            for (Config cfg : live)
            {
                if (cfg.curtok > errorTok)
                    errorTok = cfg.curtok;

                ArrayList<Config> stepResults = new ArrayList<Config>();
                ArrayList<Object> stepAccepts = new ArrayList<Object>();
                stepConfig(cfg, stepResults, stepAccepts);

                for (Object a : stepAccepts)
                    packAccept(accepts, a);

                for (Config r : stepResults)
                {
                    String k = r.key();
                    Config existing = packed.get(k);
                    if (existing == null)
                    {
                        packed.put(k, r);
                        next.add(r);
                    }
                    else
                        packParseStacks(existing, r);
                }
            }

            if (!accepts.isEmpty() && next.isEmpty())
                break;

            live = next;
            if (live.isEmpty() && accepts.isEmpty())
                throw new BadParseException(errorTok);
        }

        if (accepts.isEmpty())
            throw new BadParseException(errorTok);

        Object root = accepts.get(0);
        for (int i = 1; i < accepts.size(); i++)
            appendNextAst(root, accepts.get(i));
        return root;
    }

    private void stepConfig(Config cfg, ArrayList<Config> out, ArrayList<Object> accepts)
    {
        ArrayList<Config> work = new ArrayList<Config>();
        work.add(cfg.copy());
        int guard = prs.getNumStates() * 4 + 8;

        while (!work.isEmpty())
        {
            if (--guard < 0)
                throw new RuntimeException(
                    "cyclic/ε-loop grammar not supported by GLR v1");

            Config c = work.remove(work.size() - 1);
            ensureCapacity(c, c.stateStackTop + 2);
            c.stateStack[++c.stateStackTop] = c.currentAction;
            c.locationStack[c.stateStackTop] = c.curtok;

            // Classification order matches BacktrackingParser / DeterministicParser:
            // reduce, shift-reduce, shift, ERROR, conflict (ACCEPT < act < ERROR), ACCEPT.
            int act = tAction(c.currentAction, c.currentKind, c.curtok);
            ArrayList<Integer> candidates = new ArrayList<Integer>();
            if (act > ACCEPT_ACTION && act < ERROR_ACTION)
                expandConflict(act, candidates);
            else
                candidates.add(Integer.valueOf(act));

            for (int ci = 0; ci < candidates.size(); ci++)
            {
                int cand = candidates.get(ci).intValue();
                Config fork = (candidates.size() == 1) ? c : c.copy();
                applyConcreteAction(fork, cand, work, out, accepts);
            }
        }
    }

    /** Apply one concrete (non-conflict-index) action to a forked config. */
    private void applyConcreteAction(Config fork, int cand,
                                     ArrayList<Config> work,
                                     ArrayList<Config> out,
                                     ArrayList<Object> accepts)
    {
        if (cand <= NUM_RULES)
        {
            fork.stateStackTop--;
            applyReduceClosure(fork, cand, work);
        }
        else if (cand > ERROR_ACTION)
        {
            fork.lastToken = fork.curtok;
            fork.curtok = tokStream.getNext(fork.curtok);
            fork.currentKind = tokStream.getKind(fork.curtok);
            applyReduceClosure(fork, cand - ERROR_ACTION, work);
        }
        else if (cand < ACCEPT_ACTION)
        {
            fork.lastToken = fork.curtok;
            fork.curtok = tokStream.getNext(fork.curtok);
            fork.currentKind = tokStream.getKind(fork.curtok);
            fork.currentAction = cand;
            out.add(fork);
        }
        else if (cand == ACCEPT_ACTION)
        {
            Object root = null;
            if (fork.parseStack != null)
            {
                if (fork.parseStack.length > 0 && fork.parseStack[0] != null)
                    root = fork.parseStack[0];
                else if (fork.stateStackTop >= 0)
                    root = fork.parseStack[fork.stateStackTop];
            }
            if (root != null)
                accepts.add(root);
        }
        // cand == ERROR_ACTION: drop fork
    }

    private void applyReduceClosure(Config fork, int rule, ArrayList<Config> work)
    {
        int action = rule;
        do
        {
            int rhs = prs.rhs(action);
            if (fork.stateStackTop - (rhs - 1) < 0)
                return;

            fork.stateStackTop -= (rhs - 1);
            this.currentAction = action;
            this.lastToken = fork.lastToken;
            this.frameTop = fork.stateStackTop;
            this.frameLocation = fork.locationStack;
            this.frameParse = fork.parseStack;

            taking_actions = true;
            try
            {
                ra.ruleAction(action);
            }
            finally
            {
                taking_actions = false;
            }

            int lhs = prs.lhs(action);
            action = prs.ntAction(fork.stateStack[fork.stateStackTop], lhs);
        } while (action <= NUM_RULES);

        fork.currentAction = action;
        work.add(fork);
    }

    private void ensureCapacity(Config c, int need)
    {
        int len = (c.stateStack == null) ? 0 : c.stateStack.length;
        if (need < len)
            return;
        int neu = Math.max(need + 8, len + STACK_INCREMENT);
        if (c.stateStack == null)
        {
            c.stateStack = new int[neu];
            c.parseStack = new Object[neu];
            c.locationStack = new int[neu];
        }
        else
        {
            c.stateStack = Arrays.copyOf(c.stateStack, neu);
            c.parseStack = Arrays.copyOf(c.parseStack, neu);
            c.locationStack = Arrays.copyOf(c.locationStack, neu);
        }
    }

    private void packAccept(ArrayList<Object> accepts, Object ast)
    {
        if (ast == null)
            return;
        for (Object a : accepts)
        {
            if (sameSpan(a, ast))
            {
                appendNextAst(a, ast);
                return;
            }
        }
        accepts.add(ast);
    }

    private void packParseStacks(Config existing, Config incoming)
    {
        int i = existing.stateStackTop;
        if (i >= 0 && i == incoming.stateStackTop)
            existing.parseStack[i] = packSym(existing.parseStack[i], incoming.parseStack[i]);
    }

    private Object packSym(Object a, Object b)
    {
        if (a == null)
            return b;
        if (b == null || a == b)
            return a;
        appendNextAst(a, b);
        return a;
    }

    private static boolean sameSpan(Object a, Object b)
    {
        if (!(a instanceof IAst) || !(b instanceof IAst))
            return false;
        IAst ia = (IAst) a, ib = (IAst) b;
        IToken la = ia.getLeftIToken(), ra = ia.getRightIToken();
        IToken lb = ib.getLeftIToken(), rb = ib.getRightIToken();
        if (la == null || ra == null || lb == null || rb == null)
            return false;
        return la.getStartOffset() == lb.getStartOffset()
            && ra.getEndOffset() == rb.getEndOffset();
    }

    private static void appendNextAst(Object root, Object alt)
    {
        if (!(root instanceof IAst) || !(alt instanceof IAst))
            return;
        IAst cur = (IAst) root;
        IAst neu = (IAst) alt;
        for (IAst p = cur; p != null; p = p.getNextAst())
        {
            if (p == neu)
                return;
            if (p.getNextAst() == null)
            {
                p.setNextAst(neu);
                return;
            }
        }
    }
}
