package kr.dalmaru.run;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Moves a run roster to ACTIVE off the server thread. Retried until it succeeds; repeat requests are ignored. */
public final class PartyActivator {
    @FunctionalInterface public interface Action { void run(UUID runId) throws Exception; }
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PartyActivator.class);
    private final Action action;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "dalmaru-party-activation");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<UUID> done = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inflight = ConcurrentHashMap.newKeySet();

    public PartyActivator() { this(runId -> new RunRepository().activateParty(runId)); }
    public PartyActivator(Action action) { this.action = java.util.Objects.requireNonNull(action); }

    public boolean done(UUID runId) { return done.contains(runId); }

    public void request(UUID runId) {
        if (done.contains(runId) || !inflight.add(runId)) return;
        executor.execute(() -> {
            try {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try { action.run(runId); done.add(runId); return; }
                    catch (Exception error) {
                        if (attempt == 3) { LOGGER.warn("Run party activation failed for {}; it will be retried.", runId, error); return; }
                        Thread.sleep(250L * attempt);
                    }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { inflight.remove(runId); }
        });
    }

    public void forget(UUID runId) { done.remove(runId); inflight.remove(runId); }
}
