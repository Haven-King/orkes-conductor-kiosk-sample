package io.orkes.kiosk;

import java.util.Optional;

public record ProgramArguments(
        boolean auth,
        boolean cleanup,
        boolean performLoadTest,
        int workflowsPerSecond,
        Optional<Integer> durationInSeconds,
        String apiTestUri
) {
    public static ProgramArguments parse(String[] args) {
        boolean auth = false;
        boolean cleanup = false;
        boolean performLoadTest = false;

        // With each workflow representing the actions being taken on a single kiosk, the number of workflows per second
        // represents the number of kiosks being simulated.
        int workflowsPerSecond = 10;
        var durationInSeconds = Optional.<Integer>empty();

        String apiTestUri = Constants.Arguments.DEFAULT_API_TEST_URI;

        for (String arg : args) {
            if (arg.equals(Constants.Arguments.AUTH)) {
                auth = true;
            } else if (arg.equals(Constants.Arguments.CLEANUP)) {
                cleanup = true;
            } else if (arg.equals(Constants.Arguments.LOAD_TEST)) {
                performLoadTest = true;
            } else if (arg.startsWith(Constants.Arguments.WORKFLOWS_PER_SECOND)) {
                workflowsPerSecond = Integer.parseInt(arg.split("=", 2)[1]);
            } else if (arg.startsWith(Constants.Arguments.DURATION_IN_SECONDS)) {
                durationInSeconds = Optional.of(Integer.parseInt(arg.split("=", 2)[1]));
            } else if (arg.startsWith(Constants.Arguments.API_TEST_URI)) {
                apiTestUri = arg.split("=", 2)[1];
            }
        }

        return new ProgramArguments(auth, cleanup, performLoadTest, workflowsPerSecond, durationInSeconds, apiTestUri);
    }
}
