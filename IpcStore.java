import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * File IPC between the clients of one pair (any number of members), adapted from the Soul Wars
 * Dolo Boost script. Every client writes ~/.runelite/halloween2026-ipc/pair-&lt;id&gt;/member-&lt;slot&gt;.json
 * once per tick (and on every cursor hand-off step) and reads every other member file.
 * One snapshot per member, newest wins; a member whose snapshot stops updating goes stale.
 * Needs the script to be Trusted (java.nio.file, System.getProperty).
 */
public final class IpcStore {

    private static final String SUBPATH = ".runelite/halloween2026-ipc";

    private final int slot;
    private final Path baseDir;
    private final Path ownFile;
    private final Map<Integer, MemberState> lastGood = new HashMap<>();

    public IpcStore(String pairId, int slot) {
        this.slot = slot;
        this.baseDir = Paths.get(System.getProperty("user.home"), SUBPATH, "pair-" + pairId);
        this.ownFile = baseDir.resolve("member-" + slot + ".json");
    }

    public Path getBaseDir() { return baseDir; }

    public void ensureAvailable() throws IOException {
        Files.createDirectories(baseDir);
    }

    /**
     * Another live client already writing our slot file (same slot set on two clients).
     * Returns that client's player name, or null when the slot is free.
     */
    public String slotTakenBy(String myName, long freshMs) {
        if (!Files.exists(ownFile)) return null;
        try {
            String c = Files.readString(ownFile, StandardCharsets.UTF_8).trim();
            if (!c.startsWith("{") || !c.endsWith("}")) return null;
            MemberState s = parse(c);
            if (s.isStale(freshMs)) return null;
            if (s.player == null || s.player.equalsIgnoreCase(myName)) return null;
            return s.player;
        } catch (IOException e) {
            return null;
        }
    }

    public void writeOwn(Payload p) throws IOException {
        // Temp file + move, so members polling every 100 ms never read a half-written file.
        Path tmp = baseDir.resolve(ownFile.getFileName().toString() + ".tmp");
        Files.writeString(tmp, toJson(p), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, ownFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, ownFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Every other member's latest snapshot (stale ones included; callers check isStale). */
    public Map<Integer, MemberState> readMembers() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "member-*.json")) {
            for (Path f : ds) {
                String n = f.getFileName().toString();
                int s;
                try {
                    s = Integer.parseInt(n.substring("member-".length(), n.length() - ".json".length()));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (s == slot) continue;
                try {
                    String c = Files.readString(f, StandardCharsets.UTF_8).trim();
                    if (!c.startsWith("{") || !c.endsWith("}")) continue;   // being replaced
                    MemberState st = parse(c);
                    st.slot = s;
                    lastGood.put(s, st);
                } catch (IOException busy) {
                    // being replaced: keep the last good read
                }
            }
        } catch (IOException e) {
            // folder busy/missing: keep last good reads, they age out via isStale()
        }
        return new HashMap<>(lastGood);
    }

    public void deleteOwn() {
        try { Files.deleteIfExists(ownFile); } catch (IOException ignored) {}
    }

    // ── Payload (what we write) ──────────────────────────────────────────────
    public static final class Payload {
        public int slot;
        public String player;
        public int stage;
        public int tick;
        public long lastUpdatedEpochMs;
        public boolean idle;
        // Cursor flow (see CursorFlow)
        public boolean cursorFlow;
        public boolean cursorHeld;
        public boolean wantsCursor;
        public int wantWeight;
        public long wantSinceMs;
        public long releasedAtMs;
        public boolean hasExit;
        public int exitSX, exitSY;
        public int canvasX, canvasY, canvasW, canvasH;
    }

    // ── MemberState (what we read) ───────────────────────────────────────────
    public static final class MemberState {
        public int slot;
        public String player;
        public int stage;
        public int tick;
        public long lastUpdatedEpochMs;
        public boolean idle;
        public boolean cursorFlow;
        public boolean cursorHeld;
        public boolean wantsCursor;
        public int wantWeight;
        public long wantSinceMs;
        public long releasedAtMs;
        public boolean hasExit;
        public int exitSX, exitSY;
        public int canvasX, canvasY, canvasW, canvasH;

        public boolean isStale(long maxAgeMs) {
            return System.currentTimeMillis() - lastUpdatedEpochMs > maxAgeMs;
        }

        @Override
        public String toString() {
            return "Member{slot=" + slot + " player=" + player + " stage=" + stage
                + " held=" + cursorHeld + " wants=" + wantsCursor + "/" + wantWeight
                + " ageMs=" + (System.currentTimeMillis() - lastUpdatedEpochMs) + "}";
        }
    }

    // ── Hand-rolled flat JSON (keys must stay unique) ────────────────────────
    private static String toJson(Payload p) {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        num(sb, "slot", p.slot); sb.append(',');
        str(sb, "player", p.player); sb.append(',');
        num(sb, "stage", p.stage); sb.append(',');
        num(sb, "tick", p.tick); sb.append(',');
        num(sb, "lastUpdatedEpochMs", p.lastUpdatedEpochMs); sb.append(',');
        bool(sb, "idle", p.idle); sb.append(',');
        bool(sb, "cursorFlow", p.cursorFlow); sb.append(',');
        bool(sb, "cursorHeld", p.cursorHeld); sb.append(',');
        bool(sb, "wantsCursor", p.wantsCursor); sb.append(',');
        num(sb, "wantWeight", p.wantWeight); sb.append(',');
        num(sb, "wantSinceMs", p.wantSinceMs); sb.append(',');
        num(sb, "releasedAtMs", p.releasedAtMs); sb.append(',');
        bool(sb, "hasExit", p.hasExit); sb.append(',');
        num(sb, "exitSX", p.exitSX); sb.append(',');
        num(sb, "exitSY", p.exitSY); sb.append(',');
        num(sb, "canvasX", p.canvasX); sb.append(',');
        num(sb, "canvasY", p.canvasY); sb.append(',');
        num(sb, "canvasW", p.canvasW); sb.append(',');
        num(sb, "canvasH", p.canvasH);
        sb.append('}');
        return sb.toString();
    }

    private static void str(StringBuilder sb, String k, String v) {
        sb.append('"').append(k).append("\":");
        if (v == null) { sb.append("null"); return; }
        sb.append('"');
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            // Names never need quotes/backslashes; drop them so the regex parser stays simple.
            if (c == '"' || c == '\\' || c < 0x20) continue;
            sb.append(c);
        }
        sb.append('"');
    }
    private static void num(StringBuilder sb, String k, long v) { sb.append('"').append(k).append("\":").append(v); }
    private static void bool(StringBuilder sb, String k, boolean v) { sb.append('"').append(k).append("\":").append(v); }

    private static final Pattern STR_PAT  = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUM_PAT  = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern BOOL_PAT = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(true|false)");

    private static MemberState parse(String json) {
        MemberState s = new MemberState();
        Matcher m = STR_PAT.matcher(json);
        while (m.find()) {
            if ("player".equals(m.group(1))) s.player = m.group(2);
        }
        m = NUM_PAT.matcher(json);
        while (m.find()) {
            long v;
            try { v = Long.parseLong(m.group(2)); } catch (NumberFormatException e) { continue; }
            switch (m.group(1)) {
                case "slot": s.slot = (int) v; break;
                case "stage": s.stage = (int) v; break;
                case "tick": s.tick = (int) v; break;
                case "lastUpdatedEpochMs": s.lastUpdatedEpochMs = v; break;
                case "wantWeight": s.wantWeight = (int) v; break;
                case "wantSinceMs": s.wantSinceMs = v; break;
                case "releasedAtMs": s.releasedAtMs = v; break;
                case "exitSX": s.exitSX = (int) v; break;
                case "exitSY": s.exitSY = (int) v; break;
                case "canvasX": s.canvasX = (int) v; break;
                case "canvasY": s.canvasY = (int) v; break;
                case "canvasW": s.canvasW = (int) v; break;
                case "canvasH": s.canvasH = (int) v; break;
                default: break;
            }
        }
        m = BOOL_PAT.matcher(json);
        while (m.find()) {
            boolean v = "true".equals(m.group(2));
            switch (m.group(1)) {
                case "idle": s.idle = v; break;
                case "cursorFlow": s.cursorFlow = v; break;
                case "cursorHeld": s.cursorHeld = v; break;
                case "wantsCursor": s.wantsCursor = v; break;
                case "hasExit": s.hasExit = v; break;
                default: break;
            }
        }
        return s;
    }
}
