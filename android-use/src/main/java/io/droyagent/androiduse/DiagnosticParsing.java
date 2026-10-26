package io.droyagent.androiduse;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The exported AOSP fields used here are shared by API 30 and 36. */
final class DiagnosticParsing {
    private static final Pattern UID = Pattern.compile("^  Uid ([^:]+):$");
    private static final Pattern PACKAGE = Pattern.compile("^    Package ([^:]+):$");
    private static final Pattern OP = Pattern.compile("^      (CAMERA|RECORD_AUDIO) \\(([^)]*)\\):\\s*$");
    private static final Pattern EVENT = Pattern.compile("^(Access|Reject):\\s*(\\[[^]]+])\\s*(.+)$");
    private static final Pattern DATE = Pattern.compile("^(.{23}) \\(([^)]*)\\)(.*)$");
    private static final Pattern DURATION = Pattern.compile("(?:^|\\s)duration=([^\\s]+)");
    private static final Pattern PROXY = Pattern.compile("proxy\\[uid=([0-9]+), pkg=([^,\\]]+)");
    private static final Pattern PART = Pattern.compile("([0-9]+)(ms|d|h|m|s)");

    static final class Privacy {
        final List<Map<String, Object>> items = new ArrayList<>();
        boolean truncated;
    }

    private DiagnosticParsing() {}

    static Privacy privacy(String output, String requestedPackage, int user, int limit,
                           Locale locale, TimeZone zone, Cancellation cancellation) throws Exception {
        serviceOutput(output);
        if (!output.contains("Current AppOps Service state:")) throw unsupported("AppOps output format is not supported");
        Privacy parsed = new Privacy();
        int uid = -1;
        String pkg = null, resource = null, mode = null, tag = null;
        SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", locale);
        dates.setTimeZone(zone);
        dates.setLenient(false);
        int count = 0;
        for (String line : output.split("\\r?\\n")) {
            if ((count++ & 127) == 0) cancellation.check();
            Matcher match = UID.matcher(line);
            if (match.matches()) {
                uid = uid(match.group(1)); pkg = null; resource = null; tag = null;
                continue;
            }
            String text = line.trim();
            if (text.startsWith("Uid ")) throw unsupported("AppOps UID context format is not supported");
            match = PACKAGE.matcher(line);
            if (match.matches()) { pkg = match.group(1); resource = null; tag = null; continue; }
            if (text.startsWith("Package ")) throw unsupported("AppOps package context format is not supported");
            match = OP.matcher(line);
            if (match.matches()) {
                resource = match.group(1).equals("CAMERA") ? "camera" : "microphone";
                mode = match.group(2); tag = null; continue;
            }
            if (pkg != null && line.matches("^      [A-Z][A-Z0-9_]*.*")) {
                resource = null; tag = null;
                if (text.startsWith("CAMERA") || text.startsWith("RECORD_AUDIO"))
                    throw unsupported("AppOps operation context format is not supported");
            }
            boolean event = text.startsWith("Access:") || text.startsWith("Reject:") || text.startsWith("Running start at:");
            if (event && (uid < 0 || uid / 100000 == user)
                    && (requestedPackage == null || pkg == null || requestedPackage.equals(pkg))
                    && (uid < 0 || pkg == null || resource == null))
                throw unsupported("AppOps record context format is not supported");
            if (uid < 0 || uid / 100000 != user || pkg == null || resource == null
                    || requestedPackage != null && !requestedPackage.equals(pkg)) continue;
            if (text.endsWith("=[")) { tag = text.substring(0, text.length() - 2); continue; }
            if (text.equals("]")) { tag = null; continue; }
            match = EVENT.matcher(text);
            Map<String, Object> item;
            if (match.matches()) {
                item = record(uid, pkg, resource, mode, tag);
                item.put("kind", match.group(1).equals("Access") ? "latest_access" : "latest_rejection");
                item.put("uidStateAndFlags", match.group(2));
                Matcher date = DATE.matcher(match.group(3));
                if (!date.matches()) throw unsupported("AppOps access timestamp format is not supported");
                String timeText = date.group(1);
                ParsePosition position = new ParsePosition(0);
                Date time = dates.parse(timeText, position);
                if (time == null || position.getIndex() != timeText.length())
                    throw unsupported("AppOps access timestamp could not be parsed in the device locale");
                item.put("timestampMs", time.getTime());
                Matcher duration = DURATION.matcher(date.group(3));
                item.put("durationMs", duration.find() ? duration(duration.group(1)) : null);
                Matcher proxy = PROXY.matcher(date.group(3));
                if (proxy.find()) {
                    item.put("proxyUid", integer(proxy.group(1)));
                    item.put("proxyPackageName", proxy.group(2));
                }
            } else if (text.startsWith("Running start at: ")) {
                item = record(uid, pkg, resource, mode, tag);
                item.put("kind", "running_at_capture");
                item.put("runningForMs", duration(text.substring("Running start at: ".length())));
            } else {
                if (text.startsWith("Access:") || text.startsWith("Reject:"))
                    throw unsupported("AppOps access record format is not supported");
                continue;
            }
            parsed.items.add(item);
            parsed.items.sort(PRIVACY_ORDER);
            if (parsed.items.size() > limit) {
                parsed.items.remove(parsed.items.size() - 1);
                parsed.truncated = true;
            }
        }
        cancellation.check();
        return parsed;
    }

    static final Comparator<Map<String, Object>> PRIVACY_ORDER = Comparator.comparingLong(
            item -> item.get("kind").equals("running_at_capture") ? Long.MIN_VALUE
                    : -((Number) item.get("timestampMs")).longValue());

    private static Map<String, Object> record(int uid, String pkg, String resource, String mode, String tag) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("uid", uid); item.put("packageName", pkg); item.put("resource", resource);
        item.put("modeAtCapture", mode);
        item.put("attributionTag", tag == null || tag.equals("null") ? null : tag);
        return item;
    }

    static Map<String, Object> battery(String output, int targetUid, Cancellation cancellation) throws Exception {
        serviceOutput(output);
        if (!output.contains("Statistics since last charge:"))
            throw unsupported("Battery statistics text format is not supported");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("estimatedPowerMah", "userCpuMs", "systemCpuMs", "partialWakeLockMs",
                "backgroundPartialWakeLockMs", "statisticsStartMs", "totalRealtimeMs", "onBatteryRealtimeMs")) result.put(field, null);
        Pattern energyRow = Pattern.compile("^\\s+(?:Uid|UID) ([^: ]+): ([^ ]+).*$");
        Pattern uidRow = Pattern.compile("^  ([^ :]+):$");
        Pattern cpuRow = Pattern.compile("^    Total cpu time: u=(.*?)s=(.*?)\\s*$");
        Pattern wake = Pattern.compile("([0-9dhms ]+) actual partial(?:,|$)");
        Pattern backgroundWake = Pattern.compile("([0-9dhms ]+) actual background partial(?:,|$)");
        boolean energy = false, details = false, target = false;
        SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault());
        dates.setLenient(false);
        int count = 0;
        for (String line : output.split("\\r?\\n")) {
            if ((count++ & 127) == 0) cancellation.check();
            if (line.trim().isEmpty()) { energy = false; continue; }
            if (line.startsWith("  Start clock time: ")) {
                String value = line.substring("  Start clock time: ".length());
                ParsePosition position = new ParsePosition(0);
                Date time = dates.parse(value, position);
                if (time == null || position.getIndex() != value.length())
                    throw unsupported("Battery statistics start time format is not supported");
                result.put("statisticsStartMs", time.getTime());
                continue;
            }
            if (line.startsWith("  Time on battery: ")) {
                int end = line.indexOf('(', "  Time on battery: ".length());
                if (end < 0) throw unsupported("Battery realtime format is not supported");
                result.put("onBatteryRealtimeMs", textDuration(line.substring("  Time on battery: ".length(), end)));
                continue;
            }
            if (line.startsWith("  Total run time: ")) {
                int end = line.indexOf("realtime", "  Total run time: ".length());
                if (end < 0) throw unsupported("Battery total runtime format is not supported");
                result.put("totalRealtimeMs", textDuration(line.substring("  Total run time: ".length(), end)));
                continue;
            }
            if (line.equals("  Estimated power use (mAh):")) { energy = true; details = false; continue; }
            Matcher match = energyRow.matcher(line);
            if (energy && match.matches()) {
                if (uid(match.group(1)) == targetUid) {
                    result.put("estimatedPowerMah", decimal(match.group(2))); target = true;
                }
                continue;
            }
            match = uidRow.matcher(line);
            if (match.matches() && (match.group(1).startsWith("u") || match.group(1).matches("[0-9]+"))) {
                details = uid(match.group(1)) == targetUid; target |= details; energy = false; continue;
            }
            if (!details) continue;
            match = cpuRow.matcher(line);
            if (match.matches()) {
                result.put("userCpuMs", textDuration(match.group(1)));
                result.put("systemCpuMs", textDuration(match.group(2)));
            } else if (line.startsWith("    Total cpu time:")) {
                throw unsupported("Battery CPU time format is not supported");
            } else if (line.startsWith("    TOTAL wake: ")) {
                Matcher partial = wake.matcher(line.substring("    TOTAL wake: ".length()));
                if (partial.find()) result.put("partialWakeLockMs", textDuration(partial.group(1)));
                Matcher background = backgroundWake.matcher(line.substring("    TOTAL wake: ".length()));
                if (background.find()) result.put("backgroundPartialWakeLockMs", textDuration(background.group(1)));
            }
        }
        if (result.get("statisticsStartMs") == null)
            throw unsupported("Battery statistics did not include a usable period start");
        result.put("uidRecordsPresent", target);
        cancellation.check();
        return result;
    }

    private static long textDuration(String text) throws ActionException {
        return duration(text.replaceAll("\\s+", ""));
    }

    static void serviceOutput(String text) throws ActionException {
        if (text.contains("DROY_DIAGNOSTIC_CONTEXT_UNSUPPORTED"))
            throw unsupported("AppOps context format is not supported by the system filter");
        if (text.contains("Permission Denial") || text.contains("Permission denial") || text.contains("requires android.permission"))
            throw new ActionException("diagnostics_permission_required", "The system rejected diagnostic access; verify DUMP, the explicit PC PACKAGE_USAGE_STATS grant and usage access");
        if (text.contains("DUMP TIMEOUT") || text.contains("dump timed out"))
            throw new ActionException("diagnostic_timeout", "The system diagnostic query timed out");
        if (text.contains("Unknown option") || text.contains("Can't find service") || text.contains("No service found"))
            throw unsupported("The system does not support this diagnostic query");
    }

    static long duration(String value) throws ActionException {
        if (value.equals("0")) return 0;
        int start = value.startsWith("+") ? 1 : 0;
        long total = 0;
        Matcher part = PART.matcher(value);
        try {
            while (part.find()) {
                if (part.start() != start) throw unsupported("System duration format is not supported");
                long multiplier;
                switch (part.group(2)) {
                    case "d": multiplier = 86400000; break;
                    case "h": multiplier = 3600000; break;
                    case "m": multiplier = 60000; break;
                    case "s": multiplier = 1000; break;
                    default: multiplier = 1;
                }
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(part.group(1)), multiplier));
                start = part.end();
            }
        } catch (ArithmeticException | NumberFormatException invalid) { throw unsupported("System duration is outside the supported range"); }
        if (start != value.length() || start == 0) throw unsupported("System duration format is not supported");
        return total;
    }

    static int uid(String value) throws ActionException {
        try {
            if (value.matches("[0-9]+")) return Integer.parseInt(value);
            Matcher match = Pattern.compile("u([0-9]+)(ai|[ais])([0-9]+)").matcher(value);
            if (!match.matches()) throw unsupported("AppOps UID format is not supported");
            int app = Integer.parseInt(match.group(3));
            if (match.group(2).equals("a")) app += 10000;
            else if (match.group(2).equals("i")) app += 99000;
            else if (match.group(2).equals("ai")) app += 90000;
            return Math.addExact(Math.multiplyExact(Integer.parseInt(match.group(1)), 100000), app);
        } catch (ArithmeticException | NumberFormatException invalid) { throw unsupported("AppOps UID is outside the supported range"); }
    }

    private static long integer(String text) throws ActionException {
        try {
            long value = Long.parseLong(text);
            if (value < 0) throw unsupported("System statistics contain a negative value");
            return value;
        } catch (NumberFormatException invalid) { throw unsupported("System statistics contain an unsupported numeric value"); }
    }

    private static double decimal(String text) throws ActionException {
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value) || value < 0) throw unsupported("System energy estimate is not usable");
            return value;
        } catch (NumberFormatException invalid) { throw unsupported("System energy estimate format is not supported"); }
    }

    private static ActionException unsupported(String message) { return new ActionException("diagnostic_format_unsupported", message); }
}
