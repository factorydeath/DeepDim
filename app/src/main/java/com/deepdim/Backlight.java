package com.deepdim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Прямой доступ к узлам подсветки ядра через root. */
public final class Backlight {

    public static final class Node {
        public final String path;
        public final int max;
        public final int current;

        Node(String path, int max, int current) {
            this.path = path;
            this.max = max;
            this.current = current;
        }
    }

    private static volatile List<Node> sCache;

    private Backlight() {
    }

    /** Сбрасывает кэш узлов (например, после выдачи root). */
    public static void invalidate() {
        sCache = null;
    }

    public static List<Node> nodes(boolean refresh) {
        List<Node> cached = sCache;
        if (cached != null && !refresh) return cached;

        String script = "for d in /sys/class/backlight/* /sys/class/leds/lcd-backlight /sys/class/leds/backlight; do\n"
                + "  [ -e \"$d/brightness\" ] || continue\n"
                + "  m=$(cat \"$d/max_brightness\" 2>/dev/null)\n"
                + "  c=$(cat \"$d/brightness\" 2>/dev/null)\n"
                + "  echo \"NODE|$d/brightness|$m|$c\"\n"
                + "done";
        RootShell.Result result = RootShell.run(script, 12000);
        List<Node> out = new ArrayList<>();
        Map<String, Node> unique = new LinkedHashMap<>();
        for (String line : result.out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("NODE|")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 4) continue;
            String path = parts[1];
            try {
                int max = Integer.parseInt(parts[2].trim());
                int current = Integer.parseInt(parts[3].trim());
                if (max <= 0) continue;
                if (!unique.containsKey(path)) {
                    Node node = new Node(path, max, current);
                    unique.put(path, node);
                    out.add(node);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        sCache = out;
        return out;
    }

    public static int write(String path, int max, int percent) {
        int value = Math.round(max * (Math.max(0, Math.min(100, percent)) / 100f));
        return writeRaw(path, value);
    }

    public static int writeRaw(String path, int value) {
        String cmd = "echo " + value + " > '" + path + "' && cat '" + path + "'";
        RootShell.Result result = RootShell.run(cmd, 8000);
        String first = result.out.split("\n")[0].trim();
        try {
            return Integer.parseInt(first);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean available() {
        return !nodes(false).isEmpty();
    }

    public static String summary() {
        List<Node> list = nodes(false);
        if (list.isEmpty()) return "нет узлов";
        StringBuilder sb = new StringBuilder();
        for (Node n : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n.path).append(" [").append(n.current).append('/').append(n.max).append(']');
        }
        return sb.toString();
    }
}
