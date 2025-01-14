package io.orkes.kiosk;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoadTest {
    private final Logger log = Logging.getLogger(LoadTest.class);

    private final AtomicLong workflowsStarted = new AtomicLong();

    private final KioskApplication application;

    private LoadTest(KioskApplication application) {
        this.application = application;
    }

    private CompletableFuture<HttpResponse<InputStream>> resumeWorkflow(String workflowId, String action) {
        return this.application.resumeWorkflow(workflowId, action).exceptionally(e -> {
            if (HttpUtils.isCausedByGoAway(e)) {
                return this.resumeWorkflow(workflowId, action).join();
            } else {
                this.log.log(Level.WARNING, "Failed to execute workflow", e);
            }

            return null;
        });
    }

    private CompletableFuture<?> simulateUserAction() {
        try {
            // Start the workflow that yields execution when it reaches the wait task.
            var response = this.application.executeWorkflow().join();
            var workflowId = response.headers().firstValue("workflowId")
                    .orElseThrow();

            for (int i = 0; i < 3 && response.statusCode() != 204; ++i) {
                // Waits for three seconds, simulating the time a human would take to make a decision and click a button.
                // Thread#sleep plays well with Project Loom's Virtual Threads, so this doesn't actually block a system thread.
                Thread.sleep(Duration.ofSeconds(3));

                // Advance to the next step in the workflow.
                response = this.resumeWorkflow(workflowId, "AddItem").join();
            }

            if (response.statusCode() != 204) {
                this.resumeWorkflow(workflowId, "Checkout");
            }

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public static CompletableFuture<LoadTest> start(KioskApplication application) {
        return CompletableFuture.supplyAsync(() -> {
            final var loadTest = new LoadTest(application);

            final var interval = 1000 / application.arguments.workflowsPerSecond();
            final var count = application.arguments.durationInSeconds() * application.arguments.workflowsPerSecond();

            loadTest.log.info(String.format("Starting load test with %d workflows per second for %d seconds.", application.arguments.workflowsPerSecond(), application.arguments.durationInSeconds()));
            loadTest.log.info(String.format("Total workflow count: %d.", count));

            final var runningFutures = new AtomicLong();

            for (int i = 0; i < count; ++i) {
                application.executor.submit(() -> {
                    runningFutures.getAndIncrement();
                    loadTest.workflowsStarted.getAndIncrement();

                    loadTest.simulateUserAction().thenRun(runningFutures::getAndDecrement);
                });

                try {
                    // Thread#sleep plays well with Project Loom's Virtual Threads, so this doesn't actually block a system thread.
                    Thread.sleep(Duration.ofMillis(interval));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

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
        }, application.executor);
    }
}
