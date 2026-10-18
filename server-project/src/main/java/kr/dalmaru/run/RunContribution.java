package kr.dalmaru.run;

/** Server-thread evidence; taking damage alone never qualifies an idle participant. */
public final class RunContribution {
    public enum Action { EFFECT, RECEIVED_HIT, MECHANIC }
    private long lastInput = Long.MIN_VALUE;
    private Action firstAction;
    public void input(long tick) { lastInput = tick; }
    public void disconnect() { lastInput = Long.MIN_VALUE; }
    public boolean recentInput(long tick) { return lastInput != Long.MIN_VALUE && tick >= lastInput && tick - lastInput <= 200; }
    public boolean record(Action action, long tick) {
        if (firstAction != null || action != Action.EFFECT && !recentInput(tick)) return false;
        firstAction = java.util.Objects.requireNonNull(action);
        return true;
    }
    public Action action() { return firstAction; }
}
