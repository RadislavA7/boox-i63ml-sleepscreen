package ru.local.sleepscreen;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Выполнение команд от root через su (Superuser при первом запуске спросит разрешение). */
final class RootShell {
    static final class Result {
        int exit = -1;
        String out = "";
        String err = "";
        boolean timedOut;
        boolean failedToStart;
        String error;
    }

    private static final class Gobbler extends Thread {
        private final InputStream in;
        private final StringBuffer sb = new StringBuffer();

        Gobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
            } catch (IOException ignored) {
            }
        }

        String text() {
            return sb.toString();
        }
    }

    private RootShell() {
    }

    static Result run(String script, long timeoutMs) {
        final Result r = new Result();
        Process p;
        try {
            p = Runtime.getRuntime().exec("su");
        } catch (IOException e) {
            r.failedToStart = true;
            r.error = e.toString();
            return r;
        }
        try {
            OutputStream os = p.getOutputStream();
            String s = script.endsWith("\n") ? script : script + "\n";
            os.write((s + "exit\n").getBytes("UTF-8"));
            os.flush();
            Gobbler go = new Gobbler(p.getInputStream());
            Gobbler ge = new Gobbler(p.getErrorStream());
            go.start();
            ge.start();
            final Process fp = p;
            final int[] code = {-1};
            Thread waiter = new Thread() {
                @Override
                public void run() {
                    try {
                        code[0] = fp.waitFor();
                    } catch (InterruptedException ignored) {
                    }
                }
            };
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(timeoutMs);
            if (waiter.isAlive()) {
                r.timedOut = true;
                fp.destroy();
            }
            go.join(2000);
            ge.join(2000);
            r.exit = code[0];
            r.out = go.text();
            r.err = ge.text();
        } catch (Exception e) {
            r.error = e.toString();
        } finally {
            p.destroy();
        }
        return r;
    }
}
