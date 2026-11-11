import java.awt.Canvas;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import org.pureapps.api.Api;
import org.pureapps.api.input.IdleInput;
import org.pureapps.api.mouse.MouseModel;
import org.pureapps.api.packets.MouseMovePackets;
import org.pureapps.api.util.Instances;

/**
 * One human-like mouse shared by every client of a pair (N members), adapted from the Soul Wars
 * Dolo Boost CursorFlow (which handled exactly two).
 *
 * Only one client "holds" the cursor at a time. The holder hands it over when another member asks
 * for it (after a short burst of its own clicks, or at once when idle): the cursor glides to the
 * canvas edge facing the window of the member that will take it and leaves the canvas; that member
 * waits the time a hand takes to cross the gap, enters its canvas on the edge facing the first
 * window and drifts in. Competing wants are ordered by (weight, earlier want, lower slot).
 * All moves are synthetic AWT mouse events stepped in real time from ClientTick.
 */
public final class CursorFlow {

    enum Phase { AWAY, WAITING_ENTRY, ENTERING, HELD, RELEASING }

    private static final long SOLO_STALE_MS = 5_000L;
    private static final long POLL_MS = 100L;

    private final Api api;
    private final Client client;
    private final HalloweenConfig config;
    private final Main main;
    private final IdleInput idleInput;
    private final boolean userIdleMouse;
    private final boolean userCameraTurns;
    private final Random rng = new Random();

    private Phase phase = Phase.AWAY;

    // Requests from the brain
    private int lastRequestTick = -100;
    private int reqWeight = 0;
    private int heldWeight = 0;
    private long wantSinceMs = 0L;
    private boolean wantPublished = false;

    // Holding stats
    private int takenTick = 0;
    private int actionsSinceTake = 0;
    private int burstTarget = 1;

    // Release info (published)
    private long releasedAtMs = 0L;
    private boolean hasExit = false;
    private int exitSX, exitSY;

    // Entry planning
    private long entryDueMs = 0L;
    private boolean entryFromKnown = false;
    private int entryFromSX, entryFromSY;
    private long plannedCrossMs = 0L;

    // Animation
    private List<MouseModel.Sample> path;
    private long pathStartMs;
    private int pathIdx;
    private Runnable onPathDone;

    private int tick = 0;
    private int completedTick = -1;
    private long lastPollMs = 0L;
    private boolean soloLogged = false;
    private Boolean idleInputMouseOn = null;
    private Map<Integer, IpcStore.MemberState> members = java.util.Collections.emptyMap();
    private IpcStore.MemberState handTarget;   // member the cursor leaves towards

    // Coasting: the walk click sent just before the cursor left, still being run out
    private WorldPoint coastTarget;
    private WorldPoint coastLastPos;
    private int coastStillTicks = 0;

    public CursorFlow(Api api, Client client, HalloweenConfig config, Main main, IdleInput idleInput) {
        this.api = api;
        this.client = client;
        this.config = config;
        this.main = main;
        this.idleInput = idleInput;
        this.userIdleMouse = readBool("idleMouse", true);
        this.userCameraTurns = readBool("cameraTurns", true);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public boolean enabled() { return config.cursorFlowEnabled(); }

    /**
     * weight 1-10: how much the action matters. True while this client may click; otherwise records
     * the want and returns false. Waiting wants gain +1 per 5 ticks so nothing starves.
     */
    public boolean request(int weight) {
        if (!enabled()) return true;
        weight = Math.max(1, Math.min(10, weight));
        if (lastRequestTick != tick) reqWeight = 0;
        reqWeight = Math.max(reqWeight, weight);
        lastRequestTick = tick;
        if (phase == Phase.HELD) {
            actionsSinceTake++;
            heldWeight = reqWeight;
            return true;
        }
        if (wantSinceMs == 0L) {
            wantSinceMs = System.currentTimeMillis();
            debug("cursor wanted (phase=%s w=%d)", phase, weight);
        }
        return false;
    }

    public boolean isHeld() { return !enabled() || phase == Phase.HELD; }

    public boolean isClaimed() {
        return phase == Phase.WAITING_ENTRY || phase == Phase.ENTERING
            || phase == Phase.HELD || phase == Phase.RELEASING;
    }

    public String describe() {
        if (!enabled()) return "off";
        String w = wanting() && phase != Phase.HELD ? " (wants w=" + effectiveWeight() + ")" : "";
        return phase.name() + w + (coastTarget != null ? " coasting" : "");
    }

    public boolean coasting() { return enabled() && coastTarget != null; }

    public int liveMembers() {
        int n = 0;
        for (IpcStore.MemberState m : members.values()) if (alive(m)) n++;
        return n;
    }

    /** Called from Main.onLoop before the brain runs. */
    public void onLoop(int tickCounter, Map<Integer, IpcStore.MemberState> m) {
        tick = tickCounter;
        members = m == null ? java.util.Collections.emptyMap() : m;
        if (!enabled()) {
            restoreIdleInput();
            phase = Phase.AWAY;
            return;
        }
        applyIdleInput();
        updateCoast();
        evaluate(System.currentTimeMillis());
    }

    private void updateCoast() {
        if (coastTarget == null) return;
        if (api.walker.isWalking()) { coastTarget = null; return; }
        net.runelite.api.Player me = client.getLocalPlayer();
        WorldPoint pos = me == null ? null : me.getWorldLocation();
        if (pos == null) return;
        if (pos.getPlane() != coastTarget.getPlane() || pos.distanceTo(coastTarget) <= 2) {
            debug("coast done at %s", pos);
            coastTarget = null;
            return;
        }
        if (phase != Phase.HELD && pos.distanceTo(coastTarget) <= 5) request(9);
        if (pos.equals(coastLastPos)) {
            if (++coastStillTicks >= 2) {
                debug("coast stopped short at %s (target %s)", pos, coastTarget);
                coastTarget = null;
            }
        } else {
            coastStillTicks = 0;
            coastLastPos = pos;
        }
    }

    /** Called every client frame: steps the cursor animation and polls the members between ticks. */
    public void onClientTick() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        stepPath(now);

        // The walker clicks on its own thread; never let it run without the cursor.
        if (!isClaimed() && api.walker.isWalking()) {
            debug("walker running without cursor -> cancel");
            api.walker.cancel();
        }

        boolean poll = phase == Phase.HELD || phase == Phase.WAITING_ENTRY
            || (phase == Phase.AWAY && wanting());
        if (poll && now - lastPollMs >= POLL_MS) {
            lastPollMs = now;
            members = main.refreshMembers();
            evaluate(now);
        }
    }

    public void fill(IpcStore.Payload p) {
        p.cursorFlow = enabled();
        p.cursorHeld = enabled() && isClaimed();
        p.wantsCursor = enabled() && !isClaimed() && wanting();
        boolean claiming = phase == Phase.WAITING_ENTRY || phase == Phase.ENTERING;
        p.wantSinceMs = (p.wantsCursor || claiming) ? wantSinceMs : 0L;
        p.wantWeight = (p.wantsCursor || claiming) ? effectiveWeight() : 0;
        p.idle = idleNow();
        p.releasedAtMs = releasedAtMs;
        p.hasExit = hasExit;
        p.exitSX = exitSX;
        p.exitSY = exitSY;
        int[] r = canvasScreenRect();
        if (r != null) {
            p.canvasX = r[0]; p.canvasY = r[1]; p.canvasW = r[2]; p.canvasH = r[3];
        }
    }

    public void onPublished(IpcStore.Payload p) {
        wantPublished = p.wantsCursor;
    }

    public void onStop() {
        if (isClaimed()) {
            Canvas c = client.getCanvas();
            if (c != null) {
                int[] out = exitPoints(c);
                dispatch(MouseEvent.MOUSE_EXITED, out[2], out[3]);
            }
        }
        phase = Phase.AWAY;
        path = null;
        restoreIdleInput();
    }

    public void afterBrain() {
        completedTick = tick;
    }

    // ── Decisions ────────────────────────────────────────────────────────────

    private boolean wanting() { return recent(lastRequestTick); }

    private int effectiveWeight() {
        int aged = wantSinceMs == 0L ? 0 : (int) ((System.currentTimeMillis() - wantSinceMs) / 3000L);
        return Math.min(10, reqWeight + aged);
    }

    private boolean recent(int requestTick) {
        if (completedTick == tick) return requestTick == tick;
        return requestTick >= tick - 1;
    }

    private boolean idleNow() {
        return phase == Phase.HELD && completedTick > takenTick && lastRequestTick < completedTick;
    }

    private static boolean alive(IpcStore.MemberState m) {
        return m != null && m.cursorFlow && !m.isStale(SOLO_STALE_MS);
    }

    /** Whether a competing member's want/claim comes before `other` (weight, then earlier, then lower slot). */
    private static boolean before(IpcStore.MemberState a, IpcStore.MemberState b) {
        if (a.wantWeight != b.wantWeight) return a.wantWeight > b.wantWeight;
        long ta = a.wantSinceMs == 0L ? Long.MAX_VALUE : a.wantSinceMs;
        long tb = b.wantSinceMs == 0L ? Long.MAX_VALUE : b.wantSinceMs;
        if (ta != tb) return ta < tb;
        return a.slot < b.slot;
    }

    /** The live member whose want comes first, or null when nobody else wants the cursor. */
    private IpcStore.MemberState bestWant() {
        IpcStore.MemberState best = null;
        for (IpcStore.MemberState m : members.values()) {
            if (!alive(m) || !m.wantsCursor) continue;
            if (best == null || before(m, best)) best = m;
        }
        return best;
    }

    private IpcStore.MemberState holder() {
        for (IpcStore.MemberState m : members.values()) {
            if (alive(m) && m.cursorHeld) return m;
        }
        return null;
    }

    /** The live member that released most recently (where the hand comes from). */
    private IpcStore.MemberState lastReleaser() {
        IpcStore.MemberState best = null;
        for (IpcStore.MemberState m : members.values()) {
            if (!alive(m) || m.releasedAtMs <= 0) continue;
            if (best == null || m.releasedAtMs > best.releasedAtMs) best = m;
        }
        return best;
    }

    private boolean anyAlive() {
        for (IpcStore.MemberState m : members.values()) if (alive(m)) return true;
        return false;
    }

    private void evaluate(long now) {
        if (!wanting() && !isClaimed()) wantSinceMs = 0L;
        boolean alive = anyAlive();
        if (alive) soloLogged = false;

        switch (phase) {
            case HELD: {
                IpcStore.MemberState w = alive ? bestWant() : null;
                if (w == null) return;
                int heldTicks = tick - takenTick;
                boolean idle = idleNow();
                int theirs = w.wantWeight, mine = heldWeight;
                boolean release;
                if (idle) {
                    release = true;
                } else if (theirs > mine) {
                    release = actionsSinceTake >= 1 || heldTicks >= 2;
                } else if (theirs == mine) {
                    release = actionsSinceTake >= burstTarget
                        || heldTicks >= Math.max(2, config.cursorMaxHoldTicks());
                } else {
                    release = false;
                }
                if (release) {
                    debug("release to slot %d: w=%d vs mine w=%d (idle=%s burst=%d/%d held=%dt)",
                        w.slot, theirs, mine, idle, actionsSinceTake, burstTarget, heldTicks);
                    handTarget = w;
                    startRelease();
                }
                return;
            }
            case AWAY: {
                if (!wanting()) return;
                if (!alive) {
                    if (!soloLogged) {
                        soloLogged = true;
                        main.info("Cursor flow: no live pair members -> taking cursor alone");
                    }
                    startEntry(false, 0, 0, 0L);
                    return;
                }
                if (!wantPublished) return;          // two-phase: our want must be visible first
                if (holder() != null) return;        // someone has it; they release when they see our want
                IpcStore.MemberState w = bestWant();
                if (w != null && before(w, mine())) return;
                IpcStore.MemberState from = lastReleaser();
                long cross = crossDelay(from);
                long since = from != null ? from.releasedAtMs : now;
                entryDueMs = Math.max(now, since + cross);
                plannedCrossMs = cross;
                entryFromKnown = from != null && from.hasExit;
                entryFromSX = from == null ? 0 : from.exitSX;
                entryFromSY = from == null ? 0 : from.exitSY;
                phase = Phase.WAITING_ENTRY;
                main.publishNow();
                debug("claim: entering in %dms (cross=%dms)", entryDueMs - now, cross);
                return;
            }
            case WAITING_ENTRY: {
                IpcStore.MemberState h = alive ? holder() : null;
                if (h != null && lostClaim(h)) {
                    debug("claim lost to slot %d -> AWAY", h.slot);
                    phase = Phase.AWAY;
                    main.publishNow();
                    return;
                }
                if (now >= entryDueMs) startEntry(entryFromKnown, entryFromSX, entryFromSY, plannedCrossMs);
                return;
            }
            default:
                return;
        }
    }

    /** Our own want as a MemberState, for ordering against the others. */
    private IpcStore.MemberState mine() {
        IpcStore.MemberState s = new IpcStore.MemberState();
        s.slot = main.slot;
        s.wantWeight = effectiveWeight();
        s.wantSinceMs = wantSinceMs;
        return s;
    }

    /** Two of us claimed at once: the other keeps it when it holds outright or its claim comes first. */
    private boolean lostClaim(IpcStore.MemberState h) {
        if (h.wantSinceMs == 0L) return true;
        if (wantSinceMs == 0L) return true;
        return before(h, mine());
    }

    private long crossDelay(IpcStore.MemberState from) {
        int min = Math.max(0, config.cursorCrossMinMs());
        int max = Math.max(min + 1, config.cursorCrossMaxMs());
        double frac;
        int[] me = canvasScreenRect();
        if (from != null && from.hasExit && me != null) {
            int[] entry = nearestBorder(me, from.exitSX, from.exitSY);
            double dist = Math.hypot(entry[0] - from.exitSX, entry[1] - from.exitSY);
            frac = Math.min(1.0, dist / 1400.0);
            if (dist < 4) frac = 0.35 + rng.nextDouble() * 0.4;   // overlapping windows: alt-tab pause
        } else {
            frac = rng.nextDouble();
        }
        double jitter = rng.nextGaussian() * (max - min) * 0.12;
        long d = Math.round(min + (max - min) * frac + jitter);
        return Math.max(min, Math.min(max, d));
    }

    // ── Release / entry ──────────────────────────────────────────────────────

    private void startRelease() {
        Canvas c = client.getCanvas();
        phase = Phase.RELEASING;
        if (api.walker.isWalking()) coastClick();
        try { api.walker.cancel(); } catch (Throwable ignored) {}
        IdleInput.noteClick();
        configureIdle(false);
        if (c == null) { finishRelease(null); return; }

        int[] pts = exitPoints(c);
        Point from = currentCursor(c);
        Point inside = new Point(pts[0], pts[1]);
        final int outX = pts[2], outY = pts[3];
        startPath(from, inside, () -> {
            dispatch(MouseEvent.MOUSE_EXITED, outX, outY);
            finishRelease(c);
        });
    }

    /** A walk click 15-17 tiles ahead on the walker's path, so the character keeps running while away. */
    private void coastClick() {
        try {
            List<WorldPoint> route = api.walker.getActivePath();
            net.runelite.api.Player lp = client.getLocalPlayer();
            WorldPoint me = lp == null ? null : lp.getWorldLocation();
            api.walker.cancel();
            if (route == null || route.isEmpty() || me == null) return;

            List<WorldPoint> scene = toSceneRoute(route, Instances.inInstance(client));
            int near = nearestIndex(scene, me);
            if (near < 0 || scene.get(near).distanceTo(me) > 5) {
                scene = toSceneRoute(route, false);
                near = nearestIndex(scene, me);
            }
            if (near < 0 || scene.get(near).distanceTo(me) > 5) return;

            int reach = 15 + rng.nextInt(3);
            int idx = near;
            for (int i = near + 1; i < scene.size(); i++) {
                WorldPoint t = scene.get(i);
                if (t == null || t.getPlane() != me.getPlane()) break;
                WorldPoint prev = scene.get(i - 1);
                if (prev == null || t.distanceTo(prev) > 3) break;   // door / transport hop
                if (t.distanceTo(me) > reach) break;
                idx = i;
            }
            WorldPoint target = scene.get(idx);
            int dist = target.distanceTo(me);
            if (dist < 3) return;
            api.movement.walk(target);
            coastTarget = target;
            coastLastPos = null;
            coastStillTicks = 0;
            debug("coast click to %s (%d tiles ahead)", target, dist);
        } catch (Throwable t) {
            debug("coast click failed: %s", t);
        }
    }

    private List<WorldPoint> toSceneRoute(List<WorldPoint> route, boolean convert) {
        List<WorldPoint> out = new java.util.ArrayList<>(route.size());
        for (WorldPoint t : route) {
            WorldPoint c = t;
            if (convert) {
                try { c = Instances.toScene(client, t); } catch (Throwable ignored) { c = null; }
            }
            out.add(c);
        }
        return out;
    }

    private static int nearestIndex(List<WorldPoint> tiles, WorldPoint me) {
        int near = -1, best = Integer.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            WorldPoint t = tiles.get(i);
            if (t == null || t.getPlane() != me.getPlane()) continue;
            int d = t.distanceTo(me);
            if (d < best) { best = d; near = i; }
        }
        return near;
    }

    private void finishRelease(Canvas c) {
        int[] r = canvasScreenRect();
        if (r != null && c != null) {
            int[] pts = exitPoints(c);
            exitSX = r[0] + pts[2];
            exitSY = r[1] + pts[3];
            hasExit = true;
        } else {
            hasExit = false;
        }
        releasedAtMs = System.currentTimeMillis();
        phase = Phase.AWAY;
        main.publishNow();
        main.info("Cursor flow: HANDOFF slot {} -> slot {} after {} action(s) / {} tick(s)",
            main.slot, handTarget == null ? "?" : handTarget.slot, actionsSinceTake, tick - takenTick);
    }

    private void startEntry(boolean fromKnown, int fromSX, int fromSY, long crossMs) {
        Canvas c = client.getCanvas();
        phase = Phase.ENTERING;
        main.publishNow();
        if (c == null) { finishEntry(null, crossMs); return; }

        int w = c.getWidth(), h = c.getHeight();
        int[] r = canvasScreenRect();
        Point entry;
        if (fromKnown && r != null) {
            int[] b = nearestBorder(r, fromSX, fromSY);
            entry = new Point(clamp(b[0] - r[0] + jitterAlongEdge(), 0, w - 1),
                              clamp(b[1] - r[1] + jitterAlongEdge(), 0, h - 1));
            entry = snapToBorder(entry, w, h);
        } else {
            Point ep = null;
            try { ep = MouseMovePackets.entryPoint(c); } catch (Throwable ignored) {}
            entry = ep != null ? new Point(clamp(ep.getX(), 0, w - 1), clamp(ep.getY(), 0, h - 1))
                               : new Point(0, h / 2);
        }
        dispatch(MouseEvent.MOUSE_ENTERED, entry.getX(), entry.getY());
        dispatch(MouseEvent.MOUSE_MOVED, entry.getX(), entry.getY());

        double dx = w / 2.0 - entry.getX(), dy = h / 2.0 - entry.getY();
        double len = Math.max(1, Math.hypot(dx, dy));
        double step = 25 + rng.nextInt(70);
        Point landing = new Point(
            clamp((int) Math.round(entry.getX() + dx / len * step + rng.nextGaussian() * 12), 2, w - 3),
            clamp((int) Math.round(entry.getY() + dy / len * step + rng.nextGaussian() * 12), 2, h - 3));
        final long cross = crossMs;
        startPath(entry, landing, () -> finishEntry(landing, cross));
    }

    private void finishEntry(Point landing, long crossMs) {
        if (landing != null) {
            try { MouseMovePackets.noteCursor(landing.getX(), landing.getY()); } catch (Throwable ignored) {}
        }
        phase = Phase.HELD;
        takenTick = tick;
        actionsSinceTake = 0;
        burstTarget = 1 + rng.nextInt(3);
        heldWeight = reqWeight;
        wantSinceMs = 0L;
        configureIdle(true);
        main.publishNow();
        main.info("Cursor flow: TAKEN by slot {} cross={}ms burst={}", main.slot, crossMs, burstTarget);
    }

    // ── Animation ────────────────────────────────────────────────────────────

    private void startPath(Point from, Point to, Runnable done) {
        List<MouseModel.Sample> p = null;
        try { p = api.mouse.path(from, to); } catch (Throwable t) { debug("path failed: %s", t); }
        if (p == null || p.isEmpty()) {
            dispatch(MouseEvent.MOUSE_MOVED, to.getX(), to.getY());
            done.run();
            return;
        }
        path = p;
        pathIdx = 0;
        pathStartMs = System.currentTimeMillis();
        onPathDone = done;
    }

    private void stepPath(long now) {
        if (path == null) return;
        long elapsed = now - pathStartMs;
        int last = -1;
        while (pathIdx < path.size() && path.get(pathIdx).t <= elapsed) last = pathIdx++;
        if (last >= 0) {
            MouseModel.Sample s = path.get(last);
            dispatch(MouseEvent.MOUSE_MOVED, s.x, s.y);
        }
        if (pathIdx >= path.size()) {
            Runnable done = onPathDone;
            path = null;
            onPathDone = null;
            if (done != null) done.run();
        }
    }

    private void dispatch(int id, int x, int y) {
        Canvas c = client.getCanvas();
        if (c == null) return;
        try {
            c.dispatchEvent(new MouseEvent(c, id, System.currentTimeMillis(), 0, x, y, 0, false));
        } catch (Throwable t) {
            debug("dispatch %d failed: %s", id, t);
        }
    }

    // ── Geometry ─────────────────────────────────────────────────────────────

    /** {insideX, insideY, outsideX, outsideY} in canvas coords: where to leave toward the next holder. */
    private int[] exitPoints(Canvas c) {
        int w = c.getWidth(), h = c.getHeight();
        double cx = w / 2.0, cy = h / 2.0;
        double dx, dy;
        IpcStore.MemberState p = handTarget != null ? handTarget : bestWant();
        int[] me = canvasScreenRect();
        if (p != null && p.canvasW > 0 && me != null) {
            dx = (p.canvasX + p.canvasW / 2.0) - (me[0] + cx);
            dy = (p.canvasY + p.canvasH / 2.0) - (me[1] + cy);
            if (Math.abs(dx) < 1 && Math.abs(dy) < 1) { dx = rng.nextBoolean() ? 1 : -1; dy = 0; }
        } else {
            Point cur = currentCursor(c);
            dx = cur.getX() - cx;
            dy = cur.getY() - cy;
            if (Math.abs(dx) < 1 && Math.abs(dy) < 1) { dx = 1; dy = 0; }
        }
        double t = Math.min(Math.abs(cx / (dx == 0 ? 1e-9 : dx)), Math.abs(cy / (dy == 0 ? 1e-9 : dy)));
        int bx = (int) Math.round(cx + dx * t), by = (int) Math.round(cy + dy * t);
        Point border = snapToBorder(new Point(clamp(bx + jitterAlongEdge(), 0, w - 1),
                                              clamp(by + jitterAlongEdge(), 0, h - 1)), w, h);
        int ix = clamp(border.getX(), 1, w - 2), iy = clamp(border.getY(), 1, h - 2);
        int ox = border.getX() <= 0 ? -2 : border.getX() >= w - 1 ? w + 1 : border.getX();
        int oy = border.getY() <= 0 ? -2 : border.getY() >= h - 1 ? h + 1 : border.getY();
        return new int[]{ ix, iy, ox, oy };
    }

    private static int[] nearestBorder(int[] r, int sx, int sy) {
        int x = clamp(sx, r[0], r[0] + r[2] - 1);
        int y = clamp(sy, r[1], r[1] + r[3] - 1);
        if (sx == x && sy == y) {
            int dl = x - r[0], dr = r[0] + r[2] - 1 - x, dt = y - r[1], db = r[1] + r[3] - 1 - y;
            int m = Math.min(Math.min(dl, dr), Math.min(dt, db));
            if (m == dl) x = r[0]; else if (m == dr) x = r[0] + r[2] - 1;
            else if (m == dt) y = r[1]; else y = r[1] + r[3] - 1;
        }
        return new int[]{ x, y };
    }

    private static Point snapToBorder(Point p, int w, int h) {
        int x = p.getX(), y = p.getY();
        int dl = x, dr = w - 1 - x, dt = y, db = h - 1 - y;
        int m = Math.min(Math.min(dl, dr), Math.min(dt, db));
        if (m == dl) x = 0; else if (m == dr) x = w - 1; else if (m == dt) y = 0; else y = h - 1;
        return new Point(x, y);
    }

    private int jitterAlongEdge() { return (int) Math.round(rng.nextGaussian() * 25); }

    private Point currentCursor(Canvas c) {
        try {
            Point m = client.getMouseCanvasPosition();
            if (m != null && m.getX() >= 0 && m.getY() >= 0) return m;
        } catch (Throwable ignored) {}
        try {
            Point last = MouseMovePackets.cursor();
            if (last != null && last.getX() >= 0 && last.getY() >= 0) return last;
        } catch (Throwable ignored) {}
        return new Point(c.getWidth() / 2, c.getHeight() / 2);
    }

    private int[] canvasScreenRect() {
        Canvas c = client.getCanvas();
        if (c == null || !c.isShowing()) return null;
        try {
            java.awt.Point o = c.getLocationOnScreen();
            return new int[]{ o.x, o.y, c.getWidth(), c.getHeight() };
        } catch (Throwable t) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ── IdleInput (PureApi's idle mouse drift / camera turns): only the holder runs it ──

    private void applyIdleInput() { configureIdle(phase == Phase.HELD); }

    private void configureIdle(boolean held) {
        boolean mouseOn = held && userIdleMouse;
        if (idleInputMouseOn != null && idleInputMouseOn == mouseOn) return;
        idleInputMouseOn = mouseOn;
        try {
            idleInput.configure(mouseOn, held && userCameraTurns);
        } catch (Throwable t) {
            debug("IdleInput.configure failed: %s", t);
        }
    }

    private void restoreIdleInput() {
        if (idleInputMouseOn == null) return;
        idleInputMouseOn = null;
        try { idleInput.configure(userIdleMouse, userCameraTurns); } catch (Throwable ignored) {}
    }

    private boolean readBool(String key, boolean def) {
        try {
            String v = api.config.get("pureapi", key);
            return v == null ? def : Boolean.parseBoolean(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private void debug(String fmt, Object... args) {
        if (!config.cursorFlowDebug()) return;
        try {
            main.info("[cursor][{}] {}", phase, String.format(fmt, args));
        } catch (Throwable ignored) {}
    }
}
