package dev.ledgerline.reporting;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;

public record ReportOptions(DateRange period, ReportFormatter.Format format, Path output) {
    public static ReportOptions parse(String[] args) {
        var options = new HashMap<String, String>();
        var names = Set.of("from", "to", "format", "output");
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String[] parts = args[i].substring(2).split("=", 2);
            String name = parts[0];
            if (!names.contains(name)) continue; // Spring's own --property=value options remain supported.
            String value;
            if (parts.length == 2) value = parts[1];
            else if (i + 1 < args.length && !args[i + 1].startsWith("--")) value = args[++i];
            else throw new IllegalArgumentException("--" + name + " requires a value");
            if (value.isBlank()) throw new IllegalArgumentException("--" + name + " requires a nonempty value");
            if (options.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate --" + name + " option");
        }
        return new ReportOptions(DateRange.parse(options.get("from"), options.get("to")),
            ReportFormatter.Format.parse(options.getOrDefault("format", "json")),
            options.containsKey("output") ? Path.of(options.get("output")) : null);
    }
}
