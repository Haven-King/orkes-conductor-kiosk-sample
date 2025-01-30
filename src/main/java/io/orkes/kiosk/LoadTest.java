package io.orkes.kiosk;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoadTest {
    private final Logger log = Logging.getLogger(LoadTest.class);

    private final AtomicLong workflowsStarted = new AtomicLong();

    private final KioskApplication application;

    private LoadTest(KioskApplication application) {
        this.application = application;
    }

    private CompletableFuture<?> simulateKioskOrder(long sequence) {
        try {
            // Start the workflow that yields execution when it reaches the wait task.
            var response = this.application.executeWorkflow(Map.of(
                    "sequence", '"' + Long.toString(sequence) + '"'
            ));

            if (response.statusCode() != 200) {
                return CompletableFuture.failedFuture(new RuntimeException(STR."Failed to start workflow #\{sequence}: \{response.statusCode()}."));
            }

            var workflowId = response.headers().firstValue("workflowId")
                    .orElseThrow();

//            for (int i = 0; i < 3 && response.statusCode() != 204; ++i) {
//                // Waits for three seconds, simulating the time a human would take to make a decision and click a button.
//                // Thread#sleep plays well with Project Loom's Virtual Threads, so this doesn't actually block a system thread.
//                Thread.sleep(Duration.ofSeconds(3));
//
//                // Advance to the next step in the workflow.
//                response = this.application.resumeWorkflow(workflowId, "AddItem");
//            }

            if (response.statusCode() == 204) {
                return CompletableFuture.failedFuture(new RuntimeException(STR."Workflow \{workflowId} returned before reaching a yield task."));
            }

            response = this.application.resumeWorkflow(workflowId, "Checkout");

            if (response.statusCode() == 304) {
                return CompletableFuture.failedFuture(new RuntimeException(STR."Workflow \{workflowId} thinks that it is already completed (Received 304 Not Modified)."));
            }

            if (response.statusCode() != 204) {
                return CompletableFuture.failedFuture(new RuntimeException(STR."Failed to resume workflow \{workflowId}: \{response.statusCode()}"));
            }

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public static CompletableFuture<LoadTest> start(KioskApplication application) {
        return CompletableFuture.supplyAsync(() -> {
            final var loadTest = new LoadTest(application);

            if (application.arguments.workflowsPerSecond() <= 0 || application.arguments.durationInSeconds().orElse(1) <= 0) {
                return loadTest;
            }

            final var interval = 1000 / application.arguments.workflowsPerSecond();

            Function<Long, Boolean> shouldContinue = _ -> true;

            if (application.arguments.durationInSeconds().isPresent()) {
                final var duration = application.arguments.durationInSeconds().get();
                final var count = application.arguments.workflowsPerSecond() * duration;

                shouldContinue = i -> i < count;

                loadTest.log.info(String.format("Starting load test with %d workflows per second for %d seconds.", application.arguments.workflowsPerSecond(), duration));
                loadTest.log.info(String.format("Total workflow count: %d.", count));
            } else {
                loadTest.log.info(String.format("Starting load test with %d workflows per second.", application.arguments.workflowsPerSecond()));
            }

            final var runningFutures = new AtomicLong();

            long startTime = System.currentTimeMillis();

            for (long i = 0; shouldContinue.apply(i); ++i) {
                var sequence = i;

                application.executor.submit(() -> {
                    runningFutures.getAndIncrement();
                    loadTest.workflowsStarted.getAndIncrement();

                    loadTest.simulateKioskOrder(sequence).exceptionally(e -> {
                                loadTest.log.log(Level.SEVERE, STR."Error in load test: \{e.getMessage()}", e);
                                runningFutures.getAndDecrement();

                                return null;
                            })
                            .thenRun(runningFutures::getAndDecrement);
                });

                try {
                    // Thread#sleep plays well with Project Loom's Virtual Threads, so this doesn't actually block a system thread.
                    Thread.sleep(Duration.ofMillis(interval));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            loadTest.log.info(String.format("Actual rate: %f executions per second.", (double) loadTest.workflowsStarted.get() / (System.currentTimeMillis() - startTime) * 1000));

            while (runningFutures.get() > 0) {
                try {
                    loadTest.log.info(String.format("Waiting for %d workflows to complete...", runningFutures.get()));

                    // Thread#sleep plays well with Project Loom's Virtual Threads, so this doesn't actually block a system thread.
                    Thread.sleep(Duration.ofMillis(500));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            loadTest.log.info(String.format("Load test complete. Started %d workflows.",
                    loadTest.workflowsStarted.get()));

            return loadTest;
        });
    }
}
