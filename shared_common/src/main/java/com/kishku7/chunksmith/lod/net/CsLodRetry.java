package com.kishku7.chunksmith.lod.net;

/**
 * A backed-off retry clock. The client's answer to "the server had nothing when I asked".
 *
 * <p>The old client asked the server ONCE, at join, and if the store was empty it stood down for the whole
 * session -- no matter how long the player stayed or how far they travelled. That is exactly backwards for
 * how servers are actually run: an operator starts a hours-long pregen with players already on, the store
 * fills up behind them, and every one of them keeps staring at an empty horizon until they think to relog.
 *
 * <p>So the client keeps asking. But "keeps asking" has to be cheap, or we have traded a silent failure for
 * a packet storm: the interval starts short enough that a player who joins seconds before the pregen sees
 * their terrain almost at once, and doubles up to a ceiling so that a player parked on a server which will
 * NEVER have LOD data costs it one tiny packet every couple of minutes and nothing else.
 *
 * <p>This is a safety net, not the mechanism: a Chunksmith server NOTIFIES its waiting players the moment
 * the store becomes servable, so the usual path is instant. The clock is what covers an older server that
 * cannot notify, and a store that is filled by something other than a pregen.
 *
 * <p>Deliberately MC-free and free of any clock of its own -- the caller passes the time in, so the whole
 * policy is unit-testable without waiting for it.
 */
public final class CsLodRetry {

    /** First retry: short, so a player who joined moments before the pregen barely notices. */
    public static final long FIRST_DELAY_MILLIS = 15_000L;

    /** Ceiling: a server that will never have LOD data must never cost more than this. */
    public static final long MAX_DELAY_MILLIS = 120_000L;

    private final long firstDelayMillis;
    private final long maxDelayMillis;

    private long delayMillis;
    private long lastAttemptMillis;
    private int attempts;

    /** The shipped policy: 15s, 30s, 60s, then 120s forever. */
    public CsLodRetry() {
        this(FIRST_DELAY_MILLIS, MAX_DELAY_MILLIS);
    }

    public CsLodRetry(final long firstDelayMillis, final long maxDelayMillis) {
        if (firstDelayMillis <= 0L || maxDelayMillis < firstDelayMillis) {
            throw new IllegalArgumentException("a retry delay must be positive and may not exceed its own ceiling");
        }
        this.firstDelayMillis = firstDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.delayMillis = firstDelayMillis;
    }

    /**
     * Start the clock, without counting an attempt.
     *
     * <p>Called when the FIRST ask goes out -- the join handshake. That ask is not a retry, but it is the
     * moment we last spoke to the server, so it is what the first delay is measured from.
     */
    public synchronized void started(final long nowMillis) {
        lastAttemptMillis = nowMillis;
    }

    /** Is another ask due? */
    public synchronized boolean due(final long nowMillis) {
        return nowMillis - lastAttemptMillis >= delayMillis;
    }

    /** Record an ask, and back off before the next one. */
    public synchronized void attempted(final long nowMillis) {
        lastAttemptMillis = nowMillis;
        attempts++;
        delayMillis = Math.min(maxDelayMillis, delayMillis * 2L);
    }

    /** Back to square one -- the store turned up, or we disconnected. */
    public synchronized void reset() {
        delayMillis = firstDelayMillis;
        lastAttemptMillis = 0L;
        attempts = 0;
    }

    /** How many times we have re-asked. Purely so the log can say so in plain words. */
    public synchronized int attempts() {
        return attempts;
    }

    /** The interval that will be waited before the next ask. */
    public synchronized long delayMillis() {
        return delayMillis;
    }
}
