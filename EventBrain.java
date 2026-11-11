import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import org.pureapps.api.Api;
import org.pureapps.api.util.Reachability;

/**
 * The event as a stage machine keyed on varbit 16579. Looks at the game, does at most one click,
 * returns. Every click first asks Main.cursor(weight) (always true when running solo).
 */
public final class EventBrain {

    private static final int W_DIALOGUE = 9;
    private static final int W_ACTION = 8;
    private static final int W_WALK = 6;

    private final Main main;
    private final Api api;
    private final Client client;
    private final HalloweenConfig config;

    private int tick;
    private int lastStage = -1;
    private String step = "";

    // Pacing: after a click wait for the player to get there / finish, not re-click every tick.
    private int actionTick = -100;
    private int waitUntilTick = 0;

    // Costume
    private int piecesNeeded;
    private int storeVisits = 0;
    private final Set<Integer> takenFromStore = new HashSet<>();
    private final Set<Integer> notWearable = new HashSet<>();

    // Riddle
    private int riddleAttempt = 0;
    private int roamIdx = 0;
    private boolean rewardClosed = false;

    public EventBrain(Main main, Api api, Client client, HalloweenConfig config) {
        this.main = main;
        this.api = api;
        this.client = client;
        this.config = config;
        this.piecesNeeded = Math.max(1, config.costumePieces());
    }

    public int stage() { return client.getVarbitValue(EventData.VARBIT_PROGRESS); }
    public String step() { return step; }

    public String progress() {
        switch (stage()) {
            case EventData.STAGE_SMASH: return "lanterns " + countDone(lanternVarbits()) + "/8";
            case EventData.STAGE_SPOOK: return "children " + countDone(EventData.CHILD_VARBITS) + "/5";
            case EventData.STAGE_ADULTS: return "adults " + countDone(EventData.ADULT_VARBITS) + "/5";
            default: return "";
        }
    }

    // ── Events ───────────────────────────────────────────────────────────────

    public void onChat(String text) {
        if (text == null) return;
        if (text.contains(EventData.NOT_SCARY)) {
            int worn = wornPieces();
            piecesNeeded = Math.min(8, Math.max(piecesNeeded, worn + 1));
            main.info("Child wasn't scared (worn={}): now wearing at least {} pieces", worn, piecesNeeded);
        } else if (text.contains(EventData.RIDDLE_WRONG)) {
            riddleAttempt++;
            main.info("Riddle answer rejected (attempt {})", riddleAttempt);
        }
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(int tickCounter) {
        tick = tickCounter;
        int stage = stage();
        if (stage != lastStage) {
            main.info("Stage {} -> {}", lastStage, stage);
            lastStage = stage;
        }

        if (stage >= EventData.STAGE_DONE) { finish(); return; }

        if (api.player.inCutscene()) { step = "Cutscene"; return; }   // no clicks: cursor goes idle
        if (handleDialogue(stage)) return;
        if (busy()) return;

        // A Diango store left open outside the costume step: close it.
        if (stage != EventData.STAGE_SPOOK && storeOpen()) { closeStore(); return; }

        switch (stage) {
            case EventData.STAGE_NOT_STARTED:
            case EventData.STAGE_REPORT_LANTERNS:
            case EventData.STAGE_REPORT_CHILDREN:
            case EventData.STAGE_REPORT_ADULTS:
                step = "Talk to Henja/Angel";
                talkToChildren();
                return;
            case EventData.STAGE_SMASH:
                smashLanterns();
                return;
            case EventData.STAGE_SPOOK:
                if (wornPieces() < piecesNeeded) costume();
                else spookChildren();
                return;
            case EventData.STAGE_CHEMIST:
                if (api.inventory.emptySlots() < 1) { fail("Inventory full: the Chemist needs 1 free slot for the sugar"); return; }
                step = "Chemist riddle";
                talkToChemist();
                return;
            case EventData.STAGE_ADULTS:
                candyOnions();
                return;
            case EventData.STAGE_REWARD:
                if (api.inventory.emptySlots() < 8) { fail("Need 8 free inventory slots for the rewards"); return; }
                step = "Claim reward";
                talkToChildren();
                return;
            default:
                step = "Unknown stage " + stage;
                return;
        }
    }

    // ── Dialogue ─────────────────────────────────────────────────────────────

    private boolean handleDialogue(int stage) {
        if (api.dialogue.isInputOpen()) {
            String answer = riddleAttempt == 0 ? config.riddleAnswer().trim() : EventData.FALLBACK_RIDDLE_ANSWER;
            if (riddleAttempt > 1) { fail("Riddle answers " + config.riddleAnswer() + " and 105 both rejected"); return true; }
            step = "Riddle answer " + answer;
            if (recentlyActed(2)) return true;
            if (!main.cursor(W_DIALOGUE)) return true;
            main.info("Entering riddle answer {}", answer);
            if (api.dialogue.enterText(answer)) acted(2);
            return true;
        }
        if (!api.dialogue.isOpen()) return false;
        step = "Dialogue";
        if (recentlyActed(1)) return true;

        if (api.dialogue.hasOptions()) {
            if (!main.cursor(W_DIALOGUE)) return true;
            List<String> opts = api.dialogue.getOptions();
            for (String pref : EventData.CHEMIST_OPTIONS) {
                for (String o : opts) {
                    if (o != null && o.toLowerCase().contains(pref.toLowerCase())) {
                        main.info("Dialogue options {} -> '{}'", opts, o);
                        if (api.dialogue.chooseOption(pref)) acted(1);
                        return true;
                    }
                }
            }
            main.info("Dialogue options {} (stage {}) -> no known option, picking 1", opts, stage);
            if (api.dialogue.chooseOption(1)) acted(1);
            return true;
        }
        if (api.dialogue.canContinue()) {
            if (!main.cursor(W_DIALOGUE)) return true;
            if (api.dialogue.continueDialogue()) acted(1);
            return true;
        }
        return false;
    }

    // ── Stages ───────────────────────────────────────────────────────────────

    private void talkToChildren() {
        int first = stage() == EventData.STAGE_NOT_STARTED || stage() >= EventData.STAGE_REWARD
            ? EventData.HENJA : EventData.ANGEL;
        Optional<NPC> npc = api.npcs.search().withId(first).nearestReachable();
        if (!npc.isPresent()) npc = api.npcs.search().withId(EventData.HENJA, EventData.ANGEL).nearestReachable();
        npcAction(npc, "Talk-to", EventData.HOUSE, W_ACTION);
    }

    private void smashLanterns() {
        // The crate is by the back door: take the 5 old onions now and save a trip at stage 30.
        if (api.inventory.count(EventData.OLD_ONION) + api.inventory.count(EventData.CANDY_ONION) < 5
            && api.inventory.emptySlots() > 0) {
            step = "Take old onions";
            takeOnion();
            return;
        }
        WorldPoint me = api.player.getWorldLocation();
        EventData.Lantern next = null;
        int best = Integer.MAX_VALUE;
        for (EventData.Lantern l : EventData.LANTERNS) {
            if (client.getVarbitValue(l.varbit) != 0) continue;
            int d = me == null ? 0 : me.distanceTo(l.tile);
            if (d < best) { best = d; next = l; }
        }
        if (next == null) { step = "Lanterns done, waiting"; return; }
        step = "Smash lantern at " + next.tile.getX() + "," + next.tile.getY();
        final EventData.Lantern target = next;
        Optional<GameObject> obj = api.objects.search().atLocation(target.tile)
            .filter(o -> o.getId() == target.baseId || o.getId() == EventData.LANTERN_INTACT).first();
        if (obj.isPresent() && Reachability.canReach(obj.get())) {
            if (!main.cursor(W_ACTION)) return;
            if (api.objects.interact(obj.get(), "Smash")) {
                main.info("Smash lantern {} at {}", target.baseId, target.tile);
                acted(2);
            }
            return;
        }
        walk(target.tile);
    }

    private void spookChildren() {
        int[] ids = remaining(EventData.CHILDREN, EventData.CHILD_VARBITS);
        if (ids.length == 0) { step = "Children done, waiting"; return; }
        step = "Spook children (" + (5 - ids.length) + "/5)";
        Optional<NPC> npc = api.npcs.search().withId(ids).nearestReachable();
        if (!npc.isPresent()) { roam(); return; }
        npcAction(npc, "Spook", null, W_ACTION);
    }

    private void talkToChemist() {
        Optional<NPC> npc = api.npcs.search().withId(EventData.CHEMIST).nearestReachable();
        npcAction(npc, "Talk-to", EventData.CHEMIST_TILE, W_ACTION);
    }

    private void candyOnions() {
        int[] ids = remaining(EventData.ADULTS, EventData.ADULT_VARBITS);
        if (ids.length == 0) { step = "Adults done, waiting"; return; }
        int candy = api.inventory.count(EventData.CANDY_ONION);
        int old = api.inventory.count(EventData.OLD_ONION);

        if (candy >= ids.length || (candy > 0 && old == 0 && !api.inventory.contains(EventData.MOLTEN_SUGAR)
                && !api.inventory.contains(EventData.SUGAR))) {
            step = "Give candy onions (" + (5 - ids.length) + "/5)";
            Optional<NPC> npc = api.npcs.search().withId(ids).nearestReachable();
            if (!npc.isPresent()) { roam(); return; }
            NPC n = npc.get();
            if (!Reachability.canReach(n)) { walk(n.getWorldLocation()); return; }
            if (!main.cursor(W_ACTION)) return;
            if (api.inventory.useOnNpc(EventData.CANDY_ONION, n)) {
                main.info("Candy onion -> adult {} at {}", n.getId(), n.getWorldLocation());
                acted(2);
            }
            return;
        }
        if (candy + old < ids.length && api.inventory.emptySlots() > 0) {
            step = "Take old onions";
            takeOnion();
            return;
        }
        if (!api.inventory.contains(EventData.MOLTEN_SUGAR)) {
            if (api.inventory.contains(EventData.SUGAR)) {
                step = "Melt sugar on the range";
                Optional<GameObject> range = api.objects.search().withId(EventData.RANGE).nearestReachable();
                if (range.isPresent() && Reachability.canReach(range.get())) {
                    if (!main.cursor(W_ACTION)) return;
                    if (api.inventory.useOnObject(EventData.SUGAR, range.get())) {
                        main.info("Sugar -> range");
                        acted(2);
                    }
                    return;
                }
                walk(EventData.RANGE_TILE);
                return;
            }
            step = "Sugar lost: back to the Chemist";
            talkToChemist();
            return;
        }
        if (old > 0) {
            step = "Dip onions (" + candy + " candy)";
            if (!main.cursor(W_ACTION)) return;
            if (api.inventory.useOnItem(EventData.MOLTEN_SUGAR, EventData.OLD_ONION)) acted(1);
            return;
        }
        step = "Need onions but inventory is full";
        fail("Inventory full: can't take more old onions");
    }

    private void takeOnion() {
        Optional<GameObject> crate = api.objects.search().withId(EventData.CRATE_OF_ONIONS).nearestReachable();
        if (crate.isPresent() && Reachability.canReach(crate.get())) {
            if (!main.cursor(W_ACTION)) return;
            if (api.objects.interact(crate.get(), "Take-from")) acted(1);
            return;
        }
        walk(EventData.CRATE_TILE);
    }

    // ── Costume (Diango's Holiday-items) ─────────────────────────────────────

    private void costume() {
        // 1. Wear any Halloween piece already in the inventory.
        Item wear = inventoryWearable();
        if (wear != null) {
            if (storeOpen()) { closeStore(); return; }
            step = "Wear " + itemName(wear.getId());
            if (!main.cursor(W_ACTION)) return;
            if (api.inventory.equip(wear.getId())) {
                main.info("Wear {} ({}/{} pieces)", itemName(wear.getId()), wornPieces() + 1, piecesNeeded);
            } else {
                notWearable.add(wear.getId());
            }
            acted(1);
            return;
        }
        // 2. Store open: take pieces of the chosen set first, then the fallback sets.
        if (storeOpen()) {
            Widget pick = storePick();
            if (pick != null) {
                step = "Take " + itemName(pick.getItemId());
                if (!main.cursor(W_ACTION)) return;
                if (api.widgets.interact(pick, "Take")) {
                    takenFromStore.add(pick.getItemId());
                    main.info("Diango: take {}", itemName(pick.getItemId()));
                    acted(1);
                }
                return;
            }
            closeStore();
            return;
        }
        if (storeVisits >= 2) {
            fail("Diango has no more Halloween pieces to give: wear " + piecesNeeded + " pieces by hand");
            return;
        }
        // 3. Go to Diango.
        step = "Costume from Diango";
        Optional<NPC> diango = api.npcs.search().withId(EventData.DIANGO).nearestReachable();
        if (diango.isPresent() && Reachability.canReach(diango.get())
                && diango.get().getWorldLocation().distanceTo(EventData.DIANGO_TILE) < 15) {
            if (!main.cursor(W_ACTION)) return;
            if (api.npcs.interact(diango.get(), "Holiday-items")) {
                storeVisits++;
                main.info("Diango: Holiday-items (visit {})", storeVisits);
                acted(2);
            }
            return;
        }
        walk(EventData.DIANGO_TILE);
    }

    private Widget storePick() {
        int have = wornPieces() + inventoryPieces();
        CostumeSet preferred = config.costumeSet();
        List<Widget> items = api.widgets.query().group(EventData.DIANGO_STORE_GROUP).list();
        // Preferred set: take every piece (a full set reads as a costume).
        for (Widget w : items) {
            int id = w.getItemId();
            if (id <= 0 || takenFromStore.contains(id)) continue;
            if (preferred.matches(itemName(id))) return w;
        }
        if (have >= piecesNeeded) return null;
        // Fallback sets for slots the preferred set couldn't fill.
        for (CostumeSet s : CostumeSet.values()) {
            if (s == preferred) continue;
            for (Widget w : items) {
                int id = w.getItemId();
                if (id <= 0 || takenFromStore.contains(id)) continue;
                if (s.matches(itemName(id))) return w;
            }
        }
        return null;
    }

    private boolean storeOpen() {
        try {
            return api.widgets.query().group(EventData.DIANGO_STORE_GROUP).visible().exists();
        } catch (Throwable t) {
            return false;
        }
    }

    private void closeStore() {
        step = "Close Diango's store";
        List<Widget> close = api.widgets.query().group(EventData.DIANGO_STORE_GROUP).hasAction("Close").list();
        if (close.isEmpty()) { main.info("Diango store: no Close button found"); return; }
        if (!main.cursor(W_ACTION)) return;
        if (api.widgets.interact(close.get(0), "Close")) acted(1);
    }

    private int wornPieces() {
        return countHalloween(client.getItemContainer(InventoryID.WORN));
    }

    private int inventoryPieces() {
        return countHalloween(client.getItemContainer(InventoryID.INV));
    }

    private int countHalloween(ItemContainer c) {
        if (c == null) return 0;
        int n = 0;
        for (Item i : c.getItems()) {
            if (i == null || i.getId() <= 0 || notWearable.contains(i.getId())) continue;
            if (CostumeSet.isHalloweenWearable(itemName(i.getId()))) n++;
        }
        return n;
    }

    private Item inventoryWearable() {
        ItemContainer c = client.getItemContainer(InventoryID.INV);
        if (c == null) return null;
        for (Item i : c.getItems()) {
            if (i == null || i.getId() <= 0 || notWearable.contains(i.getId())) continue;
            if (CostumeSet.isHalloweenWearable(itemName(i.getId()))) return i;
        }
        return null;
    }

    private String itemName(int id) {
        try { return client.getItemDefinition(id).getName(); } catch (Throwable t) { return "item " + id; }
    }

    // ── Finish ───────────────────────────────────────────────────────────────

    private void finish() {
        step = "Complete";
        if (!rewardClosed) {
            List<Widget> close = api.widgets.query().group(EventData.REWARD_GROUP).hasAction("Close").list();
            if (!close.isEmpty() && main.cursor(W_ACTION)) {
                api.widgets.interact(close.get(0), "Close");
            }
            rewardClosed = true;
            return;
        }
        main.complete();
    }

    private void fail(String reason) {
        step = reason;
        main.fail(reason);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void npcAction(Optional<NPC> npc, String action, WorldPoint fallback, int weight) {
        if (npc.isPresent()) {
            NPC n = npc.get();
            if (Reachability.canReach(n)) {
                if (!main.cursor(weight)) return;
                if (api.npcs.interact(n, action)) {
                    main.info("{} {} ({}) at {}", action, n.getName(), n.getId(), n.getWorldLocation());
                    acted(2);
                }
                return;
            }
            walk(n.getWorldLocation());
            return;
        }
        if (fallback != null) walk(fallback);
    }

    private void walk(WorldPoint tile) {
        if (main.walking()) return;
        WorldPoint me = api.player.getWorldLocation();
        if (me != null && me.distanceTo(tile) <= 1) return;
        if (!main.cursor(W_WALK)) return;
        main.info("Walk to {}", tile);
        api.walker.walkTo(tile);
        acted(1);
    }

    private void roam() {
        WorldPoint me = api.player.getWorldLocation();
        WorldPoint p = EventData.ROAM[roamIdx % EventData.ROAM.length];
        if (me != null && me.distanceTo(p) <= 2) {
            roamIdx++;
            p = EventData.ROAM[roamIdx % EventData.ROAM.length];
        }
        step = step + " - looking around";
        walk(p);
    }

    /** NPC ids whose sub-varbit is still 0. */
    private int[] remaining(int[] ids, int[] varbits) {
        int n = 0;
        int[] out = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            if (client.getVarbitValue(varbits[i]) == 0) out[n++] = ids[i];
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private int countDone(int[] varbits) {
        int n = 0;
        for (int v : varbits) if (client.getVarbitValue(v) != 0) n++;
        return n;
    }

    private static int[] lanternVarbits() {
        int[] v = new int[EventData.LANTERNS.length];
        for (int i = 0; i < v.length; i++) v[i] = EventData.LANTERNS[i].varbit;
        return v;
    }

    private void acted(int minTicks) {
        actionTick = tick;
        waitUntilTick = tick + minTicks;
    }

    private boolean recentlyActed(int ticks) {
        return tick - actionTick < ticks;
    }

    /** Still walking to / doing the last click (up to 15 ticks), or our own walk is running. */
    private boolean busy() {
        if (main.walking()) { step = step.startsWith("Walk") ? step : "Walking: " + step; return true; }
        if (tick < waitUntilTick) return true;
        return tick - actionTick < 15 && (api.player.isMoving() || api.player.isAnimating());
    }
}
