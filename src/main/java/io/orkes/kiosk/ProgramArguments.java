package io.orkes.kiosk;

public record ProgramArguments(
        boolean cleanup,
        boolean performLoadTest,
        int workflowsPerSecond,
        int durationInSeconds
) {
    public static ProgramArguments parse(String[] args) {
        boolean cleanup = false;
        boolean performLoadTest = false;
        // With each workflow representing the actions being taken on a single kiosk, the number of workflows per second
        // represents the number of kiosks being simulated.
        int workflowsPerSecond = 10;
        int durationInSeconds = 60;

        for (String arg : args) {
            if (arg.equals(Constants.Arguments.CLEANUP)) {
                cleanup = true;
            } else if (arg.equals(Constants.Arguments.LOAD_TEST)) {
                performLoadTest = true;
            } else if (arg.startsWith(Constants.Arguments.WORKFLOWS_PER_SECOND)) {
                workflowsPerSecond = Integer.parseInt(arg.split("=", 2)[1]);
            } else if (arg.startsWith(Constants.Arguments.DURATION_IN_SECONDS)) {
                durationInSeconds = Integer.parseInt(arg.split("=", 2)[1]);
            }
        }

        return new ProgramArguments(cleanup, performLoadTest, workflowsPerSecond, durationInSeconds);
    }
}
