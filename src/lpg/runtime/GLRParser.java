package lpg.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * Generalized LR driver over LPG backtrack/GLR conflict tables (GLR v2).
 * <p>
 * Conflict encoding matches {@link BacktrackingParser}: when
 * {@code tAction(state, kind) > ACCEPT_ACTION}, candidates are the
 * 0-terminated sequence {@code baseAction(act), baseAction(act+1), ...}.
 * Each candidate uses the same shift / reduce / shift-reduce classification
 * as {@link DeterministicParser}.
 * <p>
 * Stacks are maintained as a graph-structured stack ({@link GssNode}/{@link GssEdge})
 * with prefix sharing across forks; reductions populate a shared packed parse
 * forest ({@link SppfNode}). Compatible {@link IAst#getNextAst()} forests are
 * projected from SPPF symbol nodes for the same grammar symbol and token-index
 * span. Cyclic / ε-loop grammars are rejected via an iteration limit.
 * <p>
 * Error repair ({@code max_error_count &gt; 0}) falls back to
 * {@link BacktrackingParser#fuzzyParseEntry} so {@code %Recover} prosthetic
 * AST replay matches the backtracking driver. Repair returns a single tree;
 * ambiguity forests apply only on successful error-free GLR parses.
 */
public class GLRParser extends Stacks
{
    private static final Object NULL_RESULT = new Object();

    private Monitor monitor;
    private int START_STATE,
                NUM_RULES,
                NT_OFFSET,
                LA_STATE_OFFSET,
                ACCEPT_ACTION,
                ERROR_ACTION;

    private TokenStream tokStream;
    private ParseTable prs;
    private RuleAction ra;

    private boolean taking_actions = false;
    private int currentAction;
    private int lastToken;
    private int parseStackRoot;
    private int frameTop;
    private int[] frameLocation;
    private Object[] frameParse;
    private HashMap<ReductionKey, IAst> familyCache;
    private HashMap<ForestKey, IAst> forestCache;
    private HashMap<Long, GssNode> gssNodes;
    private HashMap<Long, SppfNode> sppfNodes;
    private SppfNode sppfRoot;
    private int sppfSymbolCount;

    private static final class AcceptCandidate
    {
        final Object ast;
        final int grammarSymbol;
        final SppfNode sppf;

        AcceptCandidate(Object ast, int grammarSymbol, SppfNode sppf)
        {
            this.ast = ast;
            this.grammarSymbol = grammarSymbol;
            this.sppf = sppf;
        }
    }

    private static final class Config
    {
        int[] stateStack;
        int[] symbolStack;
        Object[] parseStack;
        int[] locationStack;
        SppfNode[] sppfStack;
        GssNode gssTip;
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
            c.gssTip = gssTip; // GSS prefix sharing across forks
            if (stateStack != null)
            {
                c.stateStack = Arrays.copyOf(stateStack, stateStack.length);
                c.symbolStack = Arrays.copyOf(symbolStack, symbolStack.length);
                c.parseStack = Arrays.copyOf(parseStack, parseStack.length);
                c.locationStack = Arrays.copyOf(locationStack, locationStack.length);
                if (sppfStack != null)
                    c.sppfStack = Arrays.copyOf(sppfStack, sppfStack.length);
            }
            return c;
        }

        ConfigKey key()
        {
            return new ConfigKey(this);
        }
    }

    private static final class ConfigKey
    {
        final Config config;
        final int hash;

        ConfigKey(Config config)
        {
            this.config = config;
            int h = 31 * config.curtok + config.currentKind;
            h = 31 * h + config.lastToken;
            h = 31 * h + config.currentAction;
            for (int i = 0; i <= config.stateStackTop; i++)
            {
                h = 31 * h + config.stateStack[i];
                h = 31 * h + config.locationStack[i];
                h = 31 * h + config.symbolStack[i];
            }
            hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof ConfigKey))
                return false;
            Config a = config;
            Config b = ((ConfigKey) obj).config;
            if (a.curtok != b.curtok || a.currentKind != b.currentKind
                    || a.lastToken != b.lastToken
                    || a.currentAction != b.currentAction
                    || a.stateStackTop != b.stateStackTop)
                return false;
            for (int i = 0; i <= a.stateStackTop; i++)
            {
                if (a.stateStack[i] != b.stateStack[i]
                        || a.locationStack[i] != b.locationStack[i]
                        || a.symbolStack[i] != b.symbolStack[i])
                    return false;
            }
            return true;
        }
    }

    private static final class ReductionKey
    {
        final int rule;
        final int lastToken;
        final int[] locations;
        final int[] grammarSymbols;
        final Object[] semanticValues;
        final int hash;

        ReductionKey(int rule, int lastToken, int rhs, int frameTop,
                     int[] locationStack, int[] symbolStack, Object[] parseStack)
        {
            this.rule = rule;
            this.lastToken = lastToken;
            locations = new int[rhs];
            grammarSymbols = new int[rhs];
            semanticValues = new Object[rhs];
            int h = 31 * rule + lastToken;
            for (int i = 0; i < rhs; i++)
            {
                int index = frameTop + i;
                locations[i] = locationStack[index];
                grammarSymbols[i] = symbolStack[index];
                semanticValues[i] = parseStack[index];
                h = 31 * h + locations[i];
                h = 31 * h + grammarSymbols[i];
                h = 31 * h + System.identityHashCode(semanticValues[i]);
            }
            hash = h;
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof ReductionKey))
                return false;
            ReductionKey other = (ReductionKey) obj;
            if (rule != other.rule || lastToken != other.lastToken
                    || locations.length != other.locations.length)
                return false;
            for (int i = 0; i < locations.length; i++)
            {
                if (locations[i] != other.locations[i]
                        || grammarSymbols[i] != other.grammarSymbols[i]
                        || semanticValues[i] != other.semanticValues[i])
                    return false;
            }
            return true;
        }
    }

    private static final class ForestKey
    {
        final int grammarSymbol;
        final ILexStream lexStream;
        final int leftToken;
        final int rightToken;

        ForestKey(int grammarSymbol, IAst ast)
        {
            IToken left = ast.getLeftIToken();
            IToken right = ast.getRightIToken();
            this.grammarSymbol = grammarSymbol;
            lexStream = left == null ? null : left.getILexStream();
            leftToken = left == null ? -1 : left.getTokenIndex();
            rightToken = right == null ? -1 : right.getTokenIndex();
        }

        boolean isPackable()
        {
            return leftToken >= 0 && rightToken >= 0;
        }

        @Override public int hashCode()
        {
            int h = 31 * grammarSymbol + System.identityHashCode(lexStream);
            h = 31 * h + leftToken;
            return 31 * h + rightToken;
        }

        @Override public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!(obj instanceof ForestKey))
                return false;
            ForestKey other = (ForestKey) obj;
            return grammarSymbol == other.grammarSymbol
                && lexStream == other.lexStream
                && leftToken == other.leftToken
                && rightToken == other.rightToken;
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

    /** Root SPPF symbol node from the last successful error-free parse, or null. */
    public SppfNode getSppfRoot()
    {
        return sppfRoot;
    }

    /** Number of distinct SPPF symbol nodes created in the last parse. */
    public int getSppfSymbolCount()
    {
        return sppfSymbolCount;
    }

    public void setMonitor(Monitor monitor) { this.monitor = monitor; }

    public void reset(Monitor monitor, TokenStream tokStream)
    {
        this.monitor = monitor;
        this.tokStream = tokStream;
        taking_actions = false;
        sppfRoot = null;
        sppfSymbolCount = 0;
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
        NT_OFFSET = prs.getNtOffset();
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

    /**
     * Parse with optional error repair. When {@code max_error_count &gt; 0} and
     * the GLR drive fails, falls back to {@link BacktrackingParser#fuzzyParse}
     * (RecoveryParser + {@code %Recover} prosthesis).
     */
    public Object parse(int max_error_count) throws BadParseException
    {
        return parseEntry(0, max_error_count);
    }

    /** Error-free GLR parse (no repair). */
    public Object parseEntry(int marker_kind) throws BadParseException
    {
        return parseEntryNoRepair(marker_kind);
    }

    /**
     * GLR parse; on {@link BadParseException} with {@code max_error_count &gt; 0},
     * recover via backtracking {@code fuzzyParseEntry} (single tree).
     */
    public Object parseEntry(int marker_kind, int max_error_count) throws BadParseException
    {
        try
        {
            return parseEntryNoRepair(marker_kind);
        }
        catch (BadParseException e)
        {
            if (max_error_count <= 0)
                throw e;
            try
            {
                BacktrackingParser bt =
                    new BacktrackingParser(monitor, tokStream, prs, ra);
                ra.setRecoverParser(bt);
                try
                {
                    return bt.fuzzyParseEntry(marker_kind, max_error_count);
                }
                finally
                {
                    ra.setRecoverParser(null);
                }
            }
            catch (BadParseSymFileException | NotBacktrackParseTableException ex)
            {
                // GLR tables force backtrack encoding; these should not fire.
                throw new RuntimeException(ex);
            }
        }
    }

    private Object parseEntryNoRepair(int marker_kind) throws BadParseException
    {
        tokStream.reset();
        familyCache = new HashMap<ReductionKey, IAst>();
        forestCache = new HashMap<ForestKey, IAst>();
        gssNodes = new HashMap<Long, GssNode>();
        sppfNodes = new HashMap<Long, SppfNode>();
        sppfRoot = null;
        int firstTok = tokStream.getToken();
        int prev = tokStream.getPrevious(firstTok);
        int startTok = marker_kind == 0 ? firstTok : prev;
        int startKind = marker_kind == 0 ? tokStream.getKind(firstTok) : marker_kind;
        parseStackRoot = marker_kind == 0 ? 0 : 1;

        Config start = new Config();
        start.stateStackTop = -1;
        start.currentAction = START_STATE;
        start.curtok = startTok;
        start.lastToken = prev;
        start.currentKind = startKind;
        start.gssTip = null;
        ensureCapacity(start, 16);

        ArrayList<Config> live = new ArrayList<Config>();
        live.add(start);

        ArrayList<AcceptCandidate> accepts = new ArrayList<AcceptCandidate>();
        int errorTok = startTok;
        int outerGuard = prs.getNumStates() * 64 + tokStream.getStreamLength() * 8 + 256;

        while (!live.isEmpty())
        {
            if (monitor != null && monitor.isCancelled())
                return null;
            if (--outerGuard < 0)
                throw new RuntimeException(
                    "cyclic/ε-loop grammar not supported by GLR v2");

            ArrayList<Config> next = new ArrayList<Config>();
            HashMap<ConfigKey, ArrayList<Config>> packed =
                new HashMap<ConfigKey, ArrayList<Config>>();

            for (Config cfg : live)
            {
                if (cfg.curtok > errorTok)
                    errorTok = cfg.curtok;

                ArrayList<Config> stepResults = new ArrayList<Config>();
                ArrayList<AcceptCandidate> stepAccepts = new ArrayList<AcceptCandidate>();
                stepConfig(cfg, stepResults, stepAccepts);

                for (AcceptCandidate a : stepAccepts)
                    packAccept(accepts, a);

                for (Config r : stepResults)
                {
                    ConfigKey k = r.key();
                    ArrayList<Config> bucket = packed.get(k);
                    if (bucket == null)
                    {
                        bucket = new ArrayList<Config>();
                        bucket.add(r);
                        packed.put(k, bucket);
                        next.add(r);
                    }
                    else
                    {
                        boolean merged = false;
                        for (Config existing : bucket)
                        {
                            if (canPackParseStacks(existing, r))
                            {
                                packParseStacks(existing, r);
                                merged = true;
                                break;
                            }
                        }
                        if (!merged)
                        {
                            bucket.add(r);
                            next.add(r);
                        }
                    }
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

        Object root = accepts.get(0).ast;
        int rootSymbol = accepts.get(0).grammarSymbol;
        sppfRoot = accepts.get(0).sppf;
        for (int i = 1; i < accepts.size(); i++)
        {
            AcceptCandidate other = accepts.get(i);
            if (other.grammarSymbol != rootSymbol)
                throw new RuntimeException("GLR accepted distinct start symbols");
            if (sppfRoot == null)
                sppfRoot = other.sppf;
            if (!appendNextAst(root, other.ast))
                throw new RuntimeException("overlapping GLR accept forests");
        }
        sppfSymbolCount = sppfNodes.size();
        return root == NULL_RESULT ? null : root;
    }

    private void stepConfig(Config cfg, ArrayList<Config> out, ArrayList<AcceptCandidate> accepts)
    {
        ArrayList<Config> work = new ArrayList<Config>();
        work.add(cfg.copy());
        int guard = prs.getNumStates() * 4 + 8;

        while (!work.isEmpty())
        {
            if (--guard < 0)
                throw new RuntimeException(
                    "cyclic/ε-loop grammar not supported by GLR v2");

            Config c = work.remove(work.size() - 1);
            ensureCapacity(c, c.stateStackTop + 2);
            c.stateStack[++c.stateStackTop] = c.currentAction;
            c.locationStack[c.stateStackTop] = c.curtok;
            c.symbolStack[c.stateStackTop] = 0;
            c.sppfStack[c.stateStackTop] = null;
            if (c.stateStackTop != parseStackRoot)
                c.parseStack[c.stateStackTop] = null;
            c.gssTip = gssPush(c.gssTip, c.currentAction, c.curtok, 0, null, null);

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
                                     ArrayList<AcceptCandidate> accepts)
    {
        if (cand <= NUM_RULES)
        {
            fork.stateStackTop--;
            fork.gssTip = gssPop(fork.gssTip);
            applyReduceClosure(fork, cand, work);
        }
        else if (cand > ERROR_ACTION)
        {
            fork.symbolStack[fork.stateStackTop] = fork.currentKind;
            SppfNode term = terminalSppf(fork.currentKind, fork.curtok);
            fork.sppfStack[fork.stateStackTop] = term;
            fork.gssTip = gssRelabel(fork.gssTip, fork.currentKind, fork.curtok, null, term);
            fork.lastToken = fork.curtok;
            fork.curtok = tokStream.getNext(fork.curtok);
            fork.currentKind = tokStream.getKind(fork.curtok);
            applyReduceClosure(fork, cand - ERROR_ACTION, work);
        }
        else if (cand < ACCEPT_ACTION)
        {
            fork.symbolStack[fork.stateStackTop] = fork.currentKind;
            SppfNode term = terminalSppf(fork.currentKind, fork.curtok);
            fork.sppfStack[fork.stateStackTop] = term;
            fork.gssTip = gssRelabel(fork.gssTip, fork.currentKind, fork.curtok, null, term);
            fork.lastToken = fork.curtok;
            fork.curtok = tokStream.getNext(fork.curtok);
            fork.currentKind = tokStream.getKind(fork.curtok);
            fork.currentAction = cand;
            out.add(fork);
        }
        else if (cand == ACCEPT_ACTION)
        {
            Object root = null;
            int rootSymbol = 0;
            if (fork.parseStack != null && parseStackRoot < fork.parseStack.length)
                root = fork.parseStack[parseStackRoot];
            if (fork.symbolStack != null && parseStackRoot <= fork.stateStackTop)
                rootSymbol = fork.symbolStack[parseStackRoot];
            SppfNode rootSppf = null;
            if (fork.sppfStack != null && parseStackRoot < fork.sppfStack.length)
                rootSppf = fork.sppfStack[parseStackRoot];
            accepts.add(new AcceptCandidate(
                root == null ? NULL_RESULT : root, rootSymbol, rootSppf));
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
                throw new RuntimeException("GLR reduce stack underflow");

            SppfNode[] kids = new SppfNode[rhs];
            if (rhs > 0)
            {
                for (int i = 0; i < rhs; i++)
                    kids[i] = fork.sppfStack[fork.stateStackTop - rhs + 1 + i];
            }
            fork.stateStackTop -= (rhs - 1);
            if (rhs > 0)
            {
                for (int i = 0; i < rhs - 1; i++)
                    fork.gssTip = gssPop(fork.gssTip);
            }
            else
            {
                ensureCapacity(fork, fork.stateStackTop + 1);
                fork.gssTip = gssPush(fork.gssTip,
                    fork.stateStack[fork.stateStackTop],
                    fork.locationStack[fork.stateStackTop],
                    0, null, null);
            }

            ReductionKey reductionKey =
                new ReductionKey(action, fork.lastToken, rhs, fork.stateStackTop,
                                 fork.locationStack, fork.symbolStack, fork.parseStack);
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
            int lhsSymbol = NT_OFFSET + lhs;
            Object result = fork.parseStack[fork.stateStackTop];
            if (result instanceof IAst)
            {
                IAst canonical = familyCache.get(reductionKey);
                if (canonical == null)
                {
                    IAst ast = (IAst) result;
                    ForestKey forestKey = new ForestKey(lhsSymbol, ast);
                    canonical = forestKey.isPackable() ? forestCache.get(forestKey) : null;
                    if (canonical == null)
                    {
                        canonical = ast;
                        if (forestKey.isPackable())
                            forestCache.put(forestKey, canonical);
                    }
                    else if (canonical != ast && !appendNextAst(canonical, ast))
                        throw new RuntimeException("cannot merge GLR production family");
                    familyCache.put(reductionKey, canonical);
                }
                fork.parseStack[fork.stateStackTop] = canonical;
                result = canonical;
            }

            int leftExt = fork.locationStack[fork.stateStackTop];
            int rightExt = fork.lastToken;
            if (result instanceof IAst)
            {
                IToken lt = ((IAst) result).getLeftIToken();
                IToken rt = ((IAst) result).getRightIToken();
                if (lt != null && rt != null)
                {
                    leftExt = lt.getTokenIndex();
                    rightExt = rt.getTokenIndex();
                }
            }
            SppfNode symNode = sppfSymbol(lhsSymbol, leftExt, rightExt);
            addPacked(symNode, action, kids, result);
            // result is already the v1-canonical nextAst forest when packable.
            if (result instanceof IAst)
                symNode.astForest = result;
            fork.sppfStack[fork.stateStackTop] = symNode;
            fork.symbolStack[fork.stateStackTop] = lhsSymbol;
            fork.gssTip = gssRelabel(fork.gssTip, lhsSymbol, leftExt, result, symNode);
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
            c.symbolStack = new int[neu];
            c.parseStack = new Object[neu];
            c.locationStack = new int[neu];
            c.sppfStack = new SppfNode[neu];
        }
        else
        {
            c.stateStack = Arrays.copyOf(c.stateStack, neu);
            c.symbolStack = Arrays.copyOf(c.symbolStack, neu);
            c.parseStack = Arrays.copyOf(c.parseStack, neu);
            c.locationStack = Arrays.copyOf(c.locationStack, neu);
            c.sppfStack = Arrays.copyOf(c.sppfStack, neu);
        }
    }

    private static long sppfKey(int symbol, int left, int right)
    {
        long h = symbol;
        h = 31 * h + left;
        return 31 * h + right;
    }

    private SppfNode sppfSymbol(int grammarSymbol, int leftExtent, int rightExtent)
    {
        long key = sppfKey(grammarSymbol, leftExtent, rightExtent);
        SppfNode n = sppfNodes.get(key);
        if (n == null)
        {
            n = new SppfNode(grammarSymbol, leftExtent, rightExtent);
            sppfNodes.put(key, n);
        }
        return n;
    }

    private SppfNode terminalSppf(int kind, int tok)
    {
        SppfNode term = sppfSymbol(kind, tok, tok);
        if (term.packs.isEmpty())
            term.packs.add(new SppfNode.Packed(-kind, null, null));
        return term;
    }

    private void addPacked(SppfNode symNode, int rule, SppfNode[] children, Object semantic)
    {
        int n = children == null ? 0 : children.length;
        for (int i = 0; i < symNode.packs.size(); i++)
        {
            SppfNode.Packed p = symNode.packs.get(i);
            if (p.rule != rule || p.children.length != n)
                continue;
            boolean same = true;
            for (int c = 0; c < n; c++)
            {
                if (p.children[c] != children[c])
                {
                    same = false;
                    break;
                }
            }
            if (same)
                return;
        }
        symNode.packs.add(new SppfNode.Packed(rule, children, semantic));
    }

    private GssNode gssPush(GssNode tip, int state, int index,
                            int symbol, Object semantic, SppfNode sppf)
    {
        GssNode n = new GssNode(state, index);
        GssNode pred = tip == null ? new GssNode(Integer.MIN_VALUE, -1) : tip;
        n.edges.add(new GssEdge(pred, symbol, index, semantic, sppf));
        long key = (((long) state) << 32) ^ (index & 0xffffffffL);
        GssNode canon = gssNodes.get(key);
        if (canon == null)
        {
            canon = new GssNode(state, index);
            gssNodes.put(key, canon);
        }
        canon.edges.add(new GssEdge(pred, symbol, index, semantic, sppf));
        return n;
    }

    private static GssNode gssPop(GssNode tip)
    {
        if (tip == null || tip.edges.isEmpty())
            return null;
        GssNode pred = tip.edges.get(0).predecessor;
        return pred.state == Integer.MIN_VALUE ? null : pred;
    }

    private static GssNode gssRelabel(GssNode tip, int symbol, int location,
                                     Object semantic, SppfNode sppf)
    {
        if (tip == null || tip.edges.isEmpty())
            return tip;
        GssNode pred = tip.edges.get(0).predecessor;
        GssNode n = new GssNode(tip.state, tip.index);
        n.edges.add(new GssEdge(pred, symbol, location, semantic, sppf));
        return n;
    }

    private void packAccept(ArrayList<AcceptCandidate> accepts, AcceptCandidate cand)
    {
        Object ast = cand.ast;
        int grammarSymbol = cand.grammarSymbol;
        if (ast == NULL_RESULT)
        {
            for (int i = 0; i < accepts.size(); i++)
            {
                if (accepts.get(i).ast == NULL_RESULT)
                    return;
            }
            accepts.add(cand);
            return;
        }
        if (ast == null)
            return;
        for (int i = 0; i < accepts.size(); i++)
        {
            AcceptCandidate existing = accepts.get(i);
            Object a = existing.ast;
            if (a == NULL_RESULT)
                continue;
            if (existing.grammarSymbol == grammarSymbol
                && sameSpan(a, ast)
                && appendNextAst(a, ast))
            {
                return;
            }
        }
        accepts.add(cand);
    }

    private boolean canPackParseStacks(Config existing, Config incoming)
    {
        if (existing.stateStackTop != incoming.stateStackTop)
            return false;
        for (int i = 0; i <= existing.stateStackTop; i++)
        {
            Object a = existing.parseStack[i];
            Object b = incoming.parseStack[i];
            if (a == b)
                continue;
            if (!(a instanceof IAst) || !(b instanceof IAst))
                return false;
            if (!sameSpan(a, b))
                return false;
            if (!canAppendNextAst(a, b))
                return false;
        }
        return true;
    }

    /** Dry-run of {@link #appendNextAst}: true if forests can merge without cycle. */
    private static boolean canAppendNextAst(Object root, Object alt)
    {
        return appendNextAst(root, alt, false);
    }

    private void packParseStacks(Config existing, Config incoming)
    {
        // Re-check before mutating so a mid-pack failure cannot leave a partial forest.
        for (int i = 0; i <= existing.stateStackTop; i++)
        {
            Object a = existing.parseStack[i];
            Object b = incoming.parseStack[i];
            if (a == b || a == null || b == null)
                continue;
            if (!canAppendNextAst(a, b))
                throw new RuntimeException("overlapping GLR semantic forests");
        }
        for (int i = 0; i <= existing.stateStackTop; i++)
        {
            existing.parseStack[i] = packSym(existing.parseStack[i], incoming.parseStack[i]);
            if (existing.sppfStack[i] == null)
                existing.sppfStack[i] = incoming.sppfStack[i];
            else if (incoming.sppfStack[i] != null
                    && existing.sppfStack[i] != incoming.sppfStack[i]
                    && existing.sppfStack[i].grammarSymbol
                        == incoming.sppfStack[i].grammarSymbol
                    && existing.sppfStack[i].leftExtent
                        == incoming.sppfStack[i].leftExtent
                    && existing.sppfStack[i].rightExtent
                        == incoming.sppfStack[i].rightExtent)
            {
                SppfNode canon = existing.sppfStack[i];
                SppfNode other = incoming.sppfStack[i];
                for (int p = 0; p < other.packs.size(); p++)
                {
                    SppfNode.Packed pk = other.packs.get(p);
                    addPacked(canon, pk.rule, pk.children, pk.semantic);
                }
                if (existing.parseStack[i] instanceof IAst)
                    canon.astForest = existing.parseStack[i];
            }
        }
        // Prefer longer GSS path registration from incoming when tips differ.
        if (incoming.gssTip != null)
            existing.gssTip = incoming.gssTip;
    }

    private Object packSym(Object a, Object b)
    {
        if (a == null)
            return b;
        if (b == null || a == b)
            return a;
        if (!appendNextAst(a, b))
            throw new RuntimeException("overlapping GLR semantic forests");
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
        return la.getILexStream() == lb.getILexStream()
            && ra.getILexStream() == rb.getILexStream()
            && la.getTokenIndex() == lb.getTokenIndex()
            && ra.getTokenIndex() == rb.getTokenIndex();
    }

    private static boolean appendNextAst(Object root, Object alt)
    {
        return appendNextAst(root, alt, true);
    }

    /**
     * Link {@code alt}'s nextAst alternatives onto {@code root}.
     * Only mutates the existing chain's tail ({@code setNextAst} on a node
     * already in {@code root}); never rewrites nextAst links that belong to
     * the incoming alternative forest.
     */
    private static boolean appendNextAst(Object root, Object alt, boolean commit)
    {
        if (!(root instanceof IAst) || !(alt instanceof IAst))
            return false;
        IAst cur = (IAst) root;
        IAst neu = (IAst) alt;
        if (cur == neu)
            return true;

        IdentityHashMap<IAst, Boolean> seen = new IdentityHashMap<IAst, Boolean>();
        IAst tail = null;
        for (IAst p = cur; p != null; p = p.getNextAst())
        {
            if (seen.put(p, Boolean.TRUE) != null)
                return false;
            tail = p;
        }

        IdentityHashMap<IAst, Boolean> incoming = new IdentityHashMap<IAst, Boolean>();
        for (IAst p = neu; p != null; )
        {
            if (incoming.put(p, Boolean.TRUE) != null)
                return false;
            if (seen.containsKey(p))
            {
                p = p.getNextAst();
                continue;
            }
            // Refuse attachments whose existing nextAst would re-enter the
            // root forest (cycle). Never rewrite incoming links to work around it.
            for (IAst q = p.getNextAst(); q != null; q = q.getNextAst())
            {
                if (incoming.put(q, Boolean.TRUE) != null)
                    return false;
                if (seen.containsKey(q))
                    return false;
            }
            if (commit)
            {
                tail.setNextAst(p);
                for (IAst q = p; q != null; q = q.getNextAst())
                {
                    seen.put(q, Boolean.TRUE);
                    tail = q;
                }
            }
            return true;
        }
        return true;
    }
}
