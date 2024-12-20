package io.orkes.kiosk;

import java.io.IOException;

public class HttpUtils {
    public static boolean isCausedByGoAway(Throwable throwable) {
        while (throwable != null && throwable.getCause() != null && !(throwable instanceof IOException)) {
            throwable = throwable.getCause();
        }

        return throwable instanceof IOException ioException && ioException.getMessage().contains("GOAWAY");
    }
}
