package io.orkes.kiosk;

public class Constants {
    public static class Workflows {
        public static final String KIOSK_ORDER = "kiosk_order";
        public static final String INITIALIZE_CART = "initialize_cart";
        public static final String KIOSK_HANDLER = "kiosk_handler";
    }

    public static class Arguments {
        public static final String CLEANUP = "-cleanup";
        public static final String LOAD_TEST = "-load-test";
        public static final String WORKFLOWS_PER_SECOND = "-workflows-per-second";
        public static final String DURATION_IN_SECONDS = "-duration";
    }
}
