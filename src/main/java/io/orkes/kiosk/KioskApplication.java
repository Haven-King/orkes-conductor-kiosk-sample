package io.orkes.kiosk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class KioskApplication {
    private record WorkflowSpecifier(String name, int version) {}

    private final ObjectMapper json = new ObjectMapper();
    private final Logger log = Logging.getLogger(KioskApplication.class);

    final ProgramArguments arguments;

    final ExecutorService executor;
    final HttpClient client;

    // URL of the Conductor server, e.g. `https://developer.orkescloud.com`.
    private final String endpoint = System.getenv("CONDUCTOR_SERVER_URL");

    // Token used to authenticate with the Conductor server, derived from the access key credentials.
    private final String token;

    private final Map<String, WorkflowSpecifier> workflows = new HashMap<>();

    // In a real-world application you would want to refresh the token periodically.
    private String getToken() throws IOException, InterruptedException {
        var accessKeyId = System.getenv("CONDUCTOR_ACCESS_KEY_ID");
        var accessKeySecret = System.getenv("CONDUCTOR_ACCESS_KEY_SECRET");

        if (accessKeyId != null && accessKeySecret != null) {
            var request = HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                       {
                            "keyId": "%s",
                            "keySecret": "%s"
                        }
                      """.formatted(accessKeyId, accessKeySecret)))
                .uri(URI.create(this.endpoint + "/api/token"))
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            var body = this.json.readTree(response.body());
            var token = body.get("token").asText();

            this.log.info("Got token using access key ID and secret.");

            return token;
        }

        var token = System.getenv("CONDUCTOR_SERVER_TOKEN");

        this.log.info("Using token from environment variables.");

        if (token == null) {
            throw new IllegalArgumentException("CONDUCTOR_SERVER_TOKEN environment variable must be specified if CONDUCTOR_ACCESS_KEY_ID and CONDUCTOR_ACCESS_KEY_SECRET are not");
        }

        return token;
    }

    private void publishWorkflows(String... names) throws IOException, InterruptedException {
        for (var name : names) {
            var workflow = this.json.readTree(KioskApplication.class.getResourceAsStream(STR."/workflows/\{name}.json5"));
            var workflowName = workflow.get("name").asText();
            var workflowVersion = workflow.get("version").asInt();
            var body = this.json.writeValueAsString(workflow);

            var request = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("X-Authorization", this.token)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    // A real-world application would probably want to check for an existing workflow and validate that it's
                    // functionally identical to the current workflow. This example just overwrites the existing workflow.
                    .uri(URI.create(STR."\{this.endpoint}/api/metadata/workflow?overwrite=true&newVersion=false"))
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                this.log.info(STR."Workflow '\{workflowName}' published successfully.");
            } else {
                throw new IllegalStateException(STR."Failed to publish workflow: \{response.body()}");
            }

            this.workflows.put(name, new WorkflowSpecifier(workflowName, workflowVersion));
        }
    }

    private KioskApplication(ProgramArguments args) throws IOException, InterruptedException {
        this.arguments = args;

        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (this.endpoint == null) {
            throw new IllegalArgumentException("CONDUCTOR_SERVER_URL environment variable is required");
        }

        this.token = getToken();

        this.publishWorkflows(
                Constants.Workflows.KIOSK_ORDER,
                Constants.Workflows.INITIALIZE_CART,
                Constants.Workflows.KIOSK_HANDLER
        );
    }

    CompletableFuture<HttpResponse<InputStream>> executeWorkflow() {
        var workflow = this.workflows.get(Constants.Workflows.KIOSK_ORDER);

        var request = HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .header("X-Authorization", this.token)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .uri(URI.create(
                        // String Templates are a preview feature. See https://openjdk.org/jeps/459
                        STR."\{this.endpoint}/api/workflow/execute/\{workflow.name}/\{workflow.version}?waitForSeconds=3&returnStrategy=BLOCKING_TASK_INPUT&consistency=SYNCHRONOUS"))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // Terminates existing running executions of the KioskWorkflow. Useful for cleaning up after a cancelled load test.
    // Will also clean up any workflows that are stuck in the RUNNING state due to throttling.
    private void cleanup() throws IOException, InterruptedException {
        long terminated;

        do {
            terminated = 0;

            var searchRequest = HttpRequest.newBuilder()
                    .header("Content-Type", "application/json")
                    .header("X-Authorization", this.token)
                    .GET()
                    .uri(URI.create(
                            // String Templates are a preview feature. See https://openjdk.org/jeps/459
                            STR."\{this.endpoint}/api/workflow/search?start=0&size=100&freeText=%2A&query=workflowType%20%3D%20%22KioskAction%22%20AND%20status%20%3D%20RUNNING&skipCache=true"))
                    .build();

            var response = this.client.send(searchRequest, HttpResponse.BodyHandlers.ofString());
            var result = ((ArrayNode)this.json.readTree(response.body()).get("results"));

            if (!result.isEmpty()) {
                var futures = new CompletableFuture[result.size()];

                for (int i = 0; i < result.size(); ++i) {
                    var workflowId = result.get(i).get("workflowId").asText();

                    var terminateRequest = HttpRequest.newBuilder()
                            .header("Content-Type", "application/json")
                            .header("X-Authorization", this.token)
                            .DELETE()
                            .uri(URI.create(
                                    // I tried using the bulk terminate endpoint, but it just timed out.
                                    // String Templates are a preview feature. See https://openjdk.org/jeps/459
                                    STR."\{this.endpoint}/api/workflow/\{workflowId}"))
                            .build();

                    futures[i] = this.client.sendAsync(terminateRequest, HttpResponse.BodyHandlers.ofString()).exceptionally(e -> {
                        if (HttpUtils.isCausedByGoAway(e)) {
                            this.log.config("HTTP/2 connection closed by server. Retrying...");
                        } else {
                            this.log.warning(STR."Failed to terminate workflow: \{e.getMessage()}");
                        }

                        return null;
                    });

                    ++terminated;
                }

                this.log.info(STR."Waiting to clean up \{terminated} workflows...");

                CompletableFuture.allOf(futures).join();

                this.log.info(STR."Cleaned up \{terminated} workflows.");
            }
        } while (terminated > 0);
    }

    private void start() throws IOException, InterruptedException {
        if (this.arguments.cleanup()) {
            this.cleanup();
        }

        var server = HttpServer.create(new InetSocketAddress(3000), 0);

        server.setExecutor(executor);

        server.createContext("/start-workflow/", exchange -> {
            this.executeWorkflow().thenAccept(response -> {
                try {
                    var workflow = this.json.readTree(response.body());

                    // String Templates are a preview feature. See https://openjdk.org/jeps/459
                    var body = STR."""
                        {
                            "workflowId": "\{workflow.get("workflowId").asText()}"
                        }
                    """;

                    exchange.getResponseHeaders().set("Content-Type", "application/json");

                    exchange.sendResponseHeaders(200, body.length());

                    try (var os = exchange.getResponseBody()) {
                        os.write(body.getBytes());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        // TODO: The UI portion of this demo application is not yet functional, but the frontend will eventually call this.
//        server.createContext("/resume-workflow/", exchange -> {
//            var workflow = this.json.readTree(exchange.getRequestBody());
//
//            this.resumeWorkflow(workflow.get("workflowId").asText()).thenRun(() -> {
//                try {
//                    exchange.sendResponseHeaders(200, -1);
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }
//            });
//        });

        // TODO: Make the frontend functional.
        server.createContext("/", exchange -> {
            try (var html = KioskApplication.class.getResourceAsStream("/index.html")) {
                if (html == null) {
                    throw new IllegalStateException("index.html not found");
                }

                var body = html.readAllBytes();

                exchange.sendResponseHeaders(200, body.length);

                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });

        server.start();

        if (this.arguments.performLoadTest()) {
            LoadTest.start(this).thenRun(() -> {
                server.stop(0);
            });
        }
    }

    CompletableFuture<HttpResponse<InputStream>> resumeWorkflow(String workflowId, String action) {
        var request = HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .header("X-Authorization", this.token)
                .header("X-Kiosk-Action", action) // This is just for debugging purposes.
                .POST(HttpRequest.BodyPublishers.ofString(STR."""
                        {
                            "action": "\{action}"
                        }
                        """))
                .uri(URI.create(
                        // In the future, specifying the task reference name will not be necessary.
                        // This may be done by making the parameter optional, or by using a different endpoint.
                        // String Templates are a preview feature. See https://openjdk.org/jeps/459
                        STR."\{this.endpoint}/api/tasks/\{workflowId}/COMPLETED/signal/sync?returnStrategy=BLOCKING_TASK_INPUT"))
                .build();

        return this.client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenApply(response -> {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                this.log.config("Workflow resumed successfully.");
            } else if (response.statusCode() != 304) {
                try (var body = response.body()) {
                    // String Templates are a preview feature. See https://openjdk.org/jeps/459
                    this.log.warning(STR."Failed to resume workflow: \{body}");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            return response;
        });
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var arguments = ProgramArguments.parse(args);
        var application = new KioskApplication(arguments);

        application.start();
    }
}