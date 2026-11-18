package com.deepdim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Минимальная, но надёжная обёртка над su. Все вызовы — только из фоновых потоков. */
public final class RootShell {

    public static final class Result {
        public final int code;
        public final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    private static volatile Boolean sAvailable;
    private static volatile String sBinary = "su";

    private RootShell() {
    }

    public static synchronized boolean isAvailable() {
        Boolean known = sAvailable;
        if (known != null) return known;

        String[] candidates = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su"};
        for (String candidate : candidates) {
            Result r = runWith(candidate, "id", 9000);
            if (r.code == 0 && r.out.contains("uid=0")) {
                sBinary = candidate;
                sAvailable = true;
                return true;
            }
        }
        sAvailable = false;
        return false;
    }

    public static void resetCache() {
        sAvailable = null;
    }

    public static Result run(String command) {
        return run(command, 15000);
    }

    public static Result run(String command, int timeoutMs) {
        return runWith(sBinary, command, timeoutMs);
    }

    private static Result runWith(String binary, String command, int timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(binary, "-c", command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException first) {
            if (!"su".equals(binary)) {
                return new Result(127, first.toString());
            }
            for (String candidate : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su"}) {
                try {
                    process = new ProcessBuilder(candidate, "-c", command)
                            .redirectErrorStream(true)
                            .start();
                    sBinary = candidate;
                    break;
                } catch (IOException ignored) {
                }
            }
            if (process == null) return new Result(127, first.toString());
        }

        final StringBuilder sb = new StringBuilder();
        final Process finalProcess = process;
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(finalProcess.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
        }, "deepdim-su-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = false;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!finished) {
            process.destroy();
            try {
                if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                reader.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Result(124, sb.toString().trim());
        }

        try {
            reader.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int code;
        try {
            code = process.exitValue();
        } catch (IllegalThreadStateException e) {
            code = 125;
        }
        return new Result(code, sb.toString().trim());
    }
}
