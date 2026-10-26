package io.droyagent.androiduse;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** An explicitly requested, read-only Root authorization check with no command parameters. */
public final class RootAuthorizationProbe {
    static final long TIMEOUT_MS = 45000;
    static final int OUTPUT_LIMIT = 64;
    private static final List<String> COMMAND = List.of("su", "-c", "id -u");

    private RootAuthorizationProbe() {}

    public static boolean verify(Cancellation cancellation) throws Exception {
        return verify(cancellation, CommandRunner::run);
    }

    interface Runner {
        CommandRunner.Result run(List<String> command, int outputLimit, long timeoutMs, Cancellation cancellation) throws Exception;
    }

    static boolean verify(Cancellation cancellation, Runner runner) throws Exception {
        if (cancellation == null || runner == null) throw new IllegalArgumentException("Root verification requires cancellation");
        cancellation.usingRoot(true);
        CommandRunner.Result result = runner.run(COMMAND, OUTPUT_LIMIT, TIMEOUT_MS, cancellation);
        cancellation.check();
        return result.exitCode == 0 && new String(result.stdout, StandardCharsets.US_ASCII).matches("0(?:\\r?\\n)?");
    }
}
