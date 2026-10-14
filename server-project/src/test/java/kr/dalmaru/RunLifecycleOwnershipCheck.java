package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import kr.dalmaru.run.RunLifecycle;

/** Exercises the lifecycle transition table and guards the N1-N3 production call paths. */
public final class RunLifecycleOwnershipCheck {
    private RunLifecycleOwnershipCheck() { }

    public static void main(String[] args) throws Exception {
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        transitionTable();
        n1PreparingJoin();
        n2FailureClose();
        n3LateJoin();
        require(runtime.contains("lifecycles.putIfAbsent(runId,created)"), "run UUID needs one lifecycle owner");
        require(runtime.contains("for(UUID id:new ArrayList<>(lifecycles.keySet()))try{close(server,id);}catch(RuntimeException error)"), "shutdown must continue closing runs after one failure");
        require(network.contains("try{RUN_RUNTIME.closeAll(server);}catch(Exception error)") && network.indexOf("try{RUN_RUNTIME.closeAll(server);}") < network.indexOf("try{RUNS.cancelUnfinished();}"), "shutdown must still attempt database cancellation after runtime cleanup fails");
        require(!network.contains("if(result.run()!=null)try{RUN_RUNTIME.close(server,result.run().runId())"), "single JOIN restoration failure must not close the shared run");
        System.out.println("Run lifecycle table, N1-N5 JOIN and shutdown guards PASS");
    }

    private static void transitionTable() {
        RunLifecycle.Owner owner = new RunLifecycle.Owner(UUID.randomUUID(), 1, 1);
        RunLifecycle lifecycle = new RunLifecycle(owner);
        require(lifecycle.phase() == RunLifecycle.Phase.PREPARING && !lifecycle.joinable(), "PREPARING must reject JOIN");
        require(!lifecycle.activate(new RunLifecycle.Owner(owner.accountId(), 2, 1)), "wrong tuple activated run");
        require(lifecycle.activate(owner) && lifecycle.joinable(), "PREPARING did not transition to ACTIVE");
        require(lifecycle.beginClose() && lifecycle.phase() == RunLifecycle.Phase.CLOSING, "ACTIVE did not transition to CLOSING");
        require(!lifecycle.joinable() && !lifecycle.activate(owner), "CLOSING accepted JOIN or activation");
        lifecycle.closed();
        require(lifecycle.phase() == RunLifecycle.Phase.CLOSED && !lifecycle.beginClose(), "CLOSED was not terminal");

        RunLifecycle failedPreparation = new RunLifecycle(owner);
        require(failedPreparation.beginClose(), "PREPARING failure could not claim close");
        failedPreparation.closed();
        require(!failedPreparation.joinable() && !failedPreparation.activate(owner), "failed preparation was resurrected");
    }

    private static void n1PreparingJoin() throws Exception {
        String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        require(network.contains("if(!RUN_RUNTIME.joinable(run.runId()))return;"), "JOIN must not resume an unconfirmed reservation");
        require(runtime.contains("if(lifecycle==null||!lifecycle.owns(owner))throw new IllegalStateException"), "entry callback must carry its owner tuple");
        require(runtime.contains("if(!lifecycle.preparing(owner))throw new IllegalStateException"), "only PREPARING owner may create the runtime");
    }

    private static void n2FailureClose() throws Exception {
        String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String fail = method(network, "private static void failPreparedRun(");
        require(fail.contains(".whenComplete((ignored,failure)->server.execute"), "failure cleanup must run after either DB cancellation result");
        require(fail.contains("RUN_RUNTIME.close(server,runId,owner)"), "failed preparation must close through the owner");
        RunLifecycle lifecycle = new RunLifecycle(new RunLifecycle.Owner(UUID.randomUUID(), 1, 1));
        require(lifecycle.beginClose(), "frozen-loadout failure could not enter close");
        lifecycle.closed();
        require(lifecycle.phase() == RunLifecycle.Phase.CLOSED, "failure did not reach terminal close");
    }

    private static void n3LateJoin() throws Exception {
        String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        require(network.contains("RUN_RUNTIME.reconnect(player,run.runId())"), "JOIN must use reconnect, not create");
        require(runtime.contains("if(lifecycle.phase()==RunLifecycle.Phase.CLOSING||lifecycle.phase()==RunLifecycle.Phase.CLOSED)throw new IllegalStateException"), "closed run UUID must reject creation");
        RunLifecycle lifecycle = new RunLifecycle(new RunLifecycle.Owner(UUID.randomUUID(), 1, 1));
        require(lifecycle.beginClose(), "test close claim failed");
        lifecycle.closed();
        require(!lifecycle.joinable() && !lifecycle.activate(new RunLifecycle.Owner(UUID.randomUUID(), 1, 2)), "late JOIN recreated CLOSED run");
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature), open = source.indexOf('{', start), depth = 1, end = open + 1;
        for (; depth > 0; end++) { if (source.charAt(end) == '{') depth++; else if (source.charAt(end) == '}') depth--; }
        return source.substring(start, end);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
