import java.util.Collections;
import java.util.Map;

import javax.inject.Inject;

import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;
import org.pureapps.api.AutoRun;
import org.pureapps.api.input.IdleInput;
import org.pureapps.api.scripts.Script;
import org.pureapps.api.scripts.ScriptManifest;

@ScriptManifest(name = "Halloween Event 2026", version = "1.0",
    description = "Completes the 2026 Halloween event (All trick, no treats). Optional pairing: several "
        + "clients share one human-like cursor over file IPC. Needs Trusted.")
public class Main extends Script {

    @Inject private HalloweenConfig config;
    @Inject private IdleInput idleInput;
    @Inject private ItemManager itemManager;

    private IpcStore ipc;                 // null when solo (blank Pair ID)
    CursorFlow cursorFlow;                // null when solo
    private EventBrain brain;
    private AutoRun autoRun;
    private Map<Integer, IpcStore.MemberState> members = Collections.emptyMap();

    int slot;
    long scriptStartTime;
    private int tickCounter = 0;
    private int writeFailures = 0;
    private boolean finished = false;

    @Override
    public void onStart() {
        scriptStartTime = System.currentTimeMillis();
        String pairId = config.pairId() == null ? "" : config.pairId().trim();
        slot = Math.max(1, Math.min(8, config.memberSlot()));
        brain = new EventBrain(this, api, client, config, itemManager);
        autoRun = new AutoRun("Halloween2026").range(20, 50);

        if (!pairId.isEmpty()) {
            ipc = new IpcStore(pairId, slot);
            try {
                ipc.ensureAvailable();
                String taken = ipc.slotTakenBy(playerName(), 5_000L);
                if (taken != null) {
                    log("Member slot {} of pair '{}' is already used by {}: give each client its own slot", slot, pairId, taken);
                    stop();
                    return;
                }
            } catch (Throwable t) {
                log("Pairing needs file access: mark this script Trusted on its Scripter page ({})", t.toString());
                stop();
                return;
            }
            cursorFlow = new CursorFlow(api, client, config, this, idleInput);
            log("Paired: pair='{}' slot={} ipc={} cursorFlow={}", pairId, slot, ipc.getBaseDir(), config.cursorFlowEnabled());
        } else {
            log("Solo run (no Pair ID)");
        }

        addOverlay(new ScriptPaint(this, config));
        log("Halloween Event 2026 started at stage {}", brain.stage());
    }

    @Override
    public void onLoop() {
        if (finished) return;
        tickCounter++;
        if (ipc != null) members = ipc.readMembers();
        if (client.getGameState() == GameState.LOGGED_IN) {
            runTick();
        }
        if (cursorFlow != null) cursorFlow.afterBrain();
        publishNow();
    }

    private void runTick() {
        if (cursorFlow != null) cursorFlow.onLoop(tickCounter, members);
        boolean held = cursorFlow == null || cursorFlow.isHeld();
        if (held && autoRun != null) autoRun.tick(api);   // the run orb is a click too
        brain.tick(tickCounter);
        String s = brain.step();
        String prog = brain.progress();
        if (!prog.isEmpty()) s = s + " [" + prog + "]";
        if (cursorFlow != null && cursorFlow.enabled()) s = s + " {" + cursorFlow.describe() + "}";
        status(s);
    }

    // ── Cursor helpers used by the brain ─────────────────────────────────────

    /** True when this client may click now; otherwise asks the pair for the cursor. Always true solo. */
    public boolean cursor(int weight) {
        return cursorFlow == null || cursorFlow.request(weight);
    }

    /** Our walk (or the walk click run out after a cursor hand-off) is still going. */
    public boolean walking() {
        return api.walker.isWalking() || (cursorFlow != null && cursorFlow.coasting());
    }

    // ── IPC ──────────────────────────────────────────────────────────────────

    void publishNow() {
        if (ipc == null) return;
        IpcStore.Payload p = new IpcStore.Payload();
        p.slot = slot;
        p.player = playerName();
        p.stage = brain == null ? 0 : brain.stage();
        p.tick = tickCounter;
        p.lastUpdatedEpochMs = System.currentTimeMillis();
        if (cursorFlow != null) cursorFlow.fill(p);
        try {
            ipc.writeOwn(p);
            if (cursorFlow != null) cursorFlow.onPublished(p);
        } catch (Throwable t) {
            if (writeFailures++ % 20 == 0) log("IPC write failed: {}", t.toString());
        }
    }

    Map<Integer, IpcStore.MemberState> refreshMembers() {
        if (ipc != null) members = ipc.readMembers();
        return members;
    }

    public Map<Integer, IpcStore.MemberState> members() { return members; }
    public EventBrain brain() { return brain; }

    public void info(String format, Object... args) {
        log(format, args);
    }

    // ── Ending ───────────────────────────────────────────────────────────────

    void complete() {
        if (finished) return;
        finished = true;
        long secs = (System.currentTimeMillis() - scriptStartTime) / 1000;
        log("Halloween event complete (varbit 16579 = 45) in {}m {}s", secs / 60, secs % 60);
        status("Complete");
        stop();
    }

    void fail(String reason) {
        if (finished) return;
        finished = true;
        log("Stopping: {}", reason);
        status("Stopped: " + reason);
        stop();
    }

    @Override
    public void onStop() {
        if (cursorFlow != null) cursorFlow.onStop();
        try { if (api.walker.isWalking()) api.walker.cancel(); } catch (Throwable ignored) {}
        if (ipc != null) ipc.deleteOwn();
        log("Stopped at stage {}", brain == null ? -1 : brain.stage());
    }

    // ── Events ───────────────────────────────────────────────────────────────

    @Subscribe
    public void onClientTick(ClientTick e) {
        if (cursorFlow != null && !finished) cursorFlow.onClientTick();
    }

    @Subscribe
    public void onChatMessage(ChatMessage e) {
        if (brain == null) return;
        ChatMessageType t = e.getType();
        if (t != ChatMessageType.GAMEMESSAGE && t != ChatMessageType.DIALOG && t != ChatMessageType.MESBOX
            && t != ChatMessageType.SPAM) return;
        brain.onChat(Text.removeTags(e.getMessage()));
    }

    private String playerName() {
        Player p = client.getLocalPlayer();
        String n = p == null ? null : p.getName();
        return n == null ? "" : n.replace(' ', ' ');
    }
}
