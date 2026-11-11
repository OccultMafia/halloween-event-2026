import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import org.pureapps.api.Api;
import org.pureapps.api.util.Reachability;

/**
 * The event as a stage machine keyed on varbit 16579. Looks at the game, does at most one click,
 * returns. Every click first asks Main.cursor(weight) (always true when running solo).
 *
 * Each click sets a Pending: what it should lead to (dialogue opens, a varbit flips, an item
 * arrives) and how long to wait for it. Nothing is clicked again until that happens or it times
 * out, so a click is never repeated just because the result takes a few ticks to show.
 */
public final class EventBrain {

    private static final int W_DIALOGUE = 9;
    private static final int W_ACTION = 8;
    private static final int W_WALK = 6;

    /** Chatbox interfaces that mean a dialogue is up (NPC/player chathead, sprite, message box, options). */
    private static final int[] DIALOGUE_GROUPS = {231, 217, 193, 11, 229, 219};

    private final Main main;
    private final Api api;
    private final Client client;
    private final HalloweenConfig config;
    private final ItemManager itemManager;

    private int tick;
    private int lastStage = -1;
    private int stageSinceTick = 0;
    private String step = "";

    // ── Pending action ───────────────────────────────────────────────────────
    private String pendingWhat;
    private BooleanSupplier pendingDone;
    private int pendingDeadline;
    private int pendingSince;
    private final Map<String, Integer> timeouts = new HashMap<>();

    // Dialogue
    private int dialogueClickTick = -100;
    private String lastDialogueText = "";
    private int dialogueStuckSince = -1;

    // Costume
    private int piecesNeeded;
    private int storeVisits = 0;
    private final Set<Integer> takenFromStore = new HashSet<>();
    private final Set<Integer> notWearable = new HashSet<>();
    private int closeTries = 0;

    // Misc
    private int riddleAttempt = 0;
    private int roamIdx = 0;
    private boolean rewardClosed = false;

    public EventBrain(Main main, Api api, Client client, HalloweenConfig config, ItemManager itemManager) {
        this.main = main;
        this.api = api;
        this.client = client;
        this.config = config;
        this.itemManager = itemManager;
        this.piecesNeeded = Math.max(1, Math.min(8, config.costumePieces()));
    }

    public int stage() { return client.getVarbitValue(EventData.VARBIT_PROGRESS); }
    public String step() { return step; }

    public String progress() {
        switch (stage()) {
            case EventData.STAGE_SMASH: return "lanterns " + countDone(lanternVarbits()) + "/8";
            case EventData.STAGE_SPOOK: return "costume " + wornPieces() + "/" + piecesNeeded
                + ", children " + countDone(EventData.CHILD_VARBITS) + "/5";
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
            clearPending();
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
            stageSinceTick = tick;
            clearPending();
        }

        if (stage >= EventData.STAGE_DONE && !dialogueOpen()) { finish(); return; }
        if (api.player.inCutscene()) { step = "Cutscene"; clearPending(); return; }
        if (handleDialogue()) return;
        if (waitingOnPending()) return;
        if (main.walking()) { step = "Walking: " + step.replace("Walking: ", ""); return; }

        // Diango's store left open outside the costume step (or once the costume is complete).
        if (storeOpen() && (stage != EventData.STAGE_SPOOK || storePick() == null)) { closeStore(); return; }
        closeTries = 0;

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
                step = "Waiting (stage " + stage + ")";
        }
    }

    // ── Pending ──────────────────────────────────────────────────────────────

    private void pending(String what, int timeoutTicks, BooleanSupplier done) {
        pendingWhat = what;
        pendingDone = done;
        pendingSince = tick;
        pendingDeadline = tick + timeoutTicks;
    }

    private void clearPending() {
        pendingWhat = null;
        pendingDone = null;
    }

    /** True while the last click's result hasn't shown yet (the deadline moves on while still walking). */
    private boolean waitingOnPending() {
        if (pendingDone == null) return false;
        boolean done;
        try { done = pendingDone.getAsBoolean(); } catch (Throwable t) { done = true; }
        if (done) {
            clearPending();
            return false;
        }
        boolean moving = api.player.isMoving() || api.player.isAnimating() || api.player.isInteracting();
        if (moving && pendingDeadline - tick < 3 && tick - pendingSince < 30) pendingDeadline = tick + 3;
        if (tick >= pendingDeadline) {
            int n = timeouts.merge(pendingWhat, 1, Integer::sum);
            main.info("No result from '{}' after {} ticks (timeout #{}): retrying", pendingWhat, tick - pendingSince, n);
            clearPending();
            return false;
        }
        step = "Waiting: " + pendingWhat;
        return true;
    }

    // ── Dialogue ─────────────────────────────────────────────────────────────

    private boolean dialogueOpen() {
        if (api.dialogue.isOpen() || api.dialogue.isInputOpen()) return true;
        for (int g : DIALOGUE_GROUPS) {
            Widget w = client.getWidget(g, 0);
            if (w != null && !w.isHidden()) return true;
        }
        return false;
    }

    /** Handles any open dialogue. Returns true whenever one is open, so nothing else is clicked under it. */
    private boolean handleDialogue() {
        if (api.dialogue.isInputOpen()) {
            clearPending();
            if (riddleAttempt > 1) { fail("Riddle answers " + config.riddleAnswer() + " and 105 were both rejected"); return true; }
            String answer = riddleAttempt == 0 ? config.riddleAnswer().trim() : EventData.FALLBACK_RIDDLE_ANSWER;
            step = "Riddle answer " + answer;
            if (tick - dialogueClickTick < 3) return true;
            if (!main.cursor(W_DIALOGUE)) return true;
            main.info("Entering riddle answer {}", answer);
            if (!api.dialogue.enterText(answer)) api.keyboard.typeAndEnter(answer);
            dialogueClickTick = tick;
            return true;
        }
        if (!dialogueOpen()) {
            dialogueStuckSince = -1;
            return false;
        }
        clearPending();   // whatever we clicked has answered with a dialogue
        step = "Dialogue";

        String text = safe(api.dialogue.getText());
        if (!text.equals(lastDialogueText)) {
            lastDialogueText = text;
            dialogueStuckSince = tick;
            if (!text.isEmpty()) main.info("Dialogue [{}]: {}", safe(api.dialogue.getSpeaker()), text);
        }
        // One click per new line; the next line takes a tick to arrive.
        if (tick - dialogueClickTick < 1) return true;

        if (api.dialogue.hasOptions()) {
            if (tick - dialogueClickTick < 2) return true;
            if (!main.cursor(W_DIALOGUE)) return true;
            List<String> opts = api.dialogue.getOptions();
            String pick = null;
            for (String pref : EventData.CHEMIST_OPTIONS) {
                if (api.dialogue.findOption(pref) > 0) { pick = pref; break; }
            }
            main.info("Dialogue options {} -> {}", opts, pick == null ? "option 1" : "'" + pick + "'");
            boolean ok = pick != null ? api.dialogue.chooseOption(pick) : api.dialogue.chooseOption(1);
            if (ok) dialogueClickTick = tick;
            return true;
        }
        if (api.dialogue.canContinue()) {
            if (!main.cursor(W_DIALOGUE)) return true;
            if (api.dialogue.continueDialogue()) dialogueClickTick = tick;
            return true;
        }
        // Open but no continue button found: the line is still being shown, or a box PureApi doesn't
        // know. Give it 3 ticks, then click any "continue" widget, then press space.
        if (dialogueStuckSince >= 0 && tick - dialogueStuckSince >= 3 && tick - dialogueClickTick >= 3) {
            if (!main.cursor(W_DIALOGUE)) return true;
            Optional<Widget> cont = continueWidget();
            if (cont.isPresent()) {
                main.info("Dialogue: clicking '{}' widget", safe(cont.get().getText()));
                api.widgets.interact(cont.get(), "Continue");
            } else {
                main.info("Dialogue: no continue button found - pressing space");
                api.keyboard.type(" ");
            }
            dialogueClickTick = tick;
        }
        return true;
    }

    private Optional<Widget> continueWidget() {
        for (int g : DIALOGUE_GROUPS) {
            List<Widget> l = api.widgets.query().group(g).visible().textContains("continue").list();
            if (!l.isEmpty()) return Optional.of(l.get(0));
        }
        return Optional.empty();
    }

    // ── Stages ───────────────────────────────────────────────────────────────

    private void talkToChildren() {
        int first = stage() == EventData.STAGE_NOT_STARTED || stage() >= EventData.STAGE_REWARD
            ? EventData.HENJA : EventData.ANGEL;
        Optional<NPC> npc = api.npcs.search().withId(first).nearestReachable();
        if (!npc.isPresent()) npc = api.npcs.search().withId(EventData.HENJA, EventData.ANGEL).nearestReachable();
        talkTo(npc, EventData.HOUSE);
    }

    private void talkToChemist() {
        talkTo(api.npcs.search().withId(EventData.CHEMIST).nearestReachable(), EventData.CHEMIST_TILE);
    }

    private void talkTo(Optional<NPC> npc, WorldPoint fallback) {
        if (!npc.isPresent()) { walk(fallback); return; }
        NPC n = npc.get();
        if (!Reachability.canReach(n)) { walk(n.getWorldLocation()); return; }
        if (!main.cursor(W_ACTION)) return;
        if (api.npcs.interact(n, "Talk-to")) {
            main.info("Talk-to {} ({}) at {}", n.getName(), n.getId(), n.getWorldLocation());
            pending("Talk-to " + n.getName(), 6, this::dialogueOpen);
        }
    }

    private void smashLanterns() {
        // The crate is by the back door: take the 5 old onions now and save a trip at stage 30.
        if (onionsHeld() < 5 && api.inventory.emptySlots() > 0) {
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
        if (next == null) { step = "Lanterns done"; return; }
        final EventData.Lantern target = next;
        step = "Smash lantern at " + target.tile.getX() + "," + target.tile.getY();
        Optional<GameObject> obj = api.objects.search().atLocation(target.tile)
            .filter(o -> o.getId() == target.baseId || o.getId() == EventData.LANTERN_INTACT).first();
        if (obj.isPresent() && Reachability.canReach(obj.get())) {
            if (!main.cursor(W_ACTION)) return;
            if (api.objects.interact(obj.get(), "Smash")) {
                main.info("Smash lantern {} at {}", target.baseId, target.tile);
                pending("Smash lantern", 4, () -> client.getVarbitValue(target.varbit) != 0);
            }
            return;
        }
        walk(target.tile);
    }

    private void spookChildren() {
        int[] ids = remaining(EventData.CHILDREN, EventData.CHILD_VARBITS);
        if (ids.length == 0) { step = "Children done"; return; }
        step = "Spook children (" + (5 - ids.length) + "/5)";
        Optional<NPC> npc = api.npcs.search().withId(ids).nearestReachable();
        if (!npc.isPresent()) { roam(); return; }
        NPC n = npc.get();
        if (!Reachability.canReach(n)) { walk(n.getWorldLocation()); return; }
        final int varbit = varbitFor(n.getId(), EventData.CHILDREN, EventData.CHILD_VARBITS);
        if (!main.cursor(W_ACTION)) return;
        if (api.npcs.interact(n, "Spook")) {
            main.info("Spook child {} at {}", n.getId(), n.getWorldLocation());
            pending("Spook child", 5, () -> client.getVarbitValue(varbit) != 0);
        }
    }

    private void candyOnions() {
        int[] ids = remaining(EventData.ADULTS, EventData.ADULT_VARBITS);
        if (ids.length == 0) { step = "Adults done"; return; }
        int candy = api.inventory.count(EventData.CANDY_ONION);
        int old = api.inventory.count(EventData.OLD_ONION);
        boolean molten = api.inventory.contains(EventData.MOLTEN_SUGAR);

        // Coat every old onion first while standing at the range (0-tick, no walking between).
        if (molten && old > 0 && candy < ids.length) {
            step = "Dip onions (" + candy + "/" + ids.length + ")";
            if (!main.cursor(W_ACTION)) return;
            final int before = candy;
            if (api.inventory.useOnItem(EventData.MOLTEN_SUGAR, EventData.OLD_ONION)) {
                pending("Dip onion", 2, () -> api.inventory.count(EventData.CANDY_ONION) > before);
            }
            return;
        }
        if (candy > 0 && (candy >= ids.length || old == 0)) {
            step = "Give candy onions (" + (5 - ids.length) + "/5)";
            Optional<NPC> npc = api.npcs.search().withId(ids).nearestReachable();
            if (!npc.isPresent()) { roam(); return; }
            NPC n = npc.get();
            if (!Reachability.canReach(n)) { walk(n.getWorldLocation()); return; }
            final int varbit = varbitFor(n.getId(), EventData.ADULTS, EventData.ADULT_VARBITS);
            if (!main.cursor(W_ACTION)) return;
            if (api.inventory.useOnNpc(EventData.CANDY_ONION, n)) {
                main.info("Candy onion -> adult {} at {}", n.getId(), n.getWorldLocation());
                // The adult answers with a dialogue line (handled), then eats it and the varbit flips.
                pending("Give candy onion", 8, () -> client.getVarbitValue(varbit) != 0);
            }
            return;
        }
        if (candy + old < ids.length) {
            if (api.inventory.emptySlots() == 0) { fail("Inventory full: can't take more old onions"); return; }
            step = "Take old onions";
            takeOnion();
            return;
        }
        if (!molten) {
            if (!api.inventory.contains(EventData.SUGAR)) {
                // The sugar arrives a tick after the riddle dialogue: only go back once it's really gone.
                if (tick - stageSinceTick < 5) { step = "Waiting for the sugar"; return; }
                step = "Sugar lost: back to the Chemist";
                talkToChemist();
                return;
            }
            step = "Melt sugar on the range";
            Optional<GameObject> range = api.objects.search().withId(EventData.RANGE).nearestReachable();
            if (range.isPresent() && Reachability.canReach(range.get())) {
                if (!main.cursor(W_ACTION)) return;
                if (api.inventory.useOnObject(EventData.SUGAR, range.get())) {
                    main.info("Sugar -> range at {}", range.get().getWorldLocation());
                    pending("Melt sugar", 6, () -> api.inventory.contains(EventData.MOLTEN_SUGAR));
                }
                return;
            }
            walk(EventData.RANGE_TILE);
        }
    }

    private int onionsHeld() {
        return api.inventory.count(EventData.OLD_ONION) + api.inventory.count(EventData.CANDY_ONION);
    }

    private void takeOnion() {
        Optional<GameObject> crate = api.objects.search().withId(EventData.CRATE_OF_ONIONS).nearestReachable();
        if (crate.isPresent() && Reachability.canReach(crate.get())) {
            if (!main.cursor(W_ACTION)) return;
            final int before = api.inventory.count(EventData.OLD_ONION);
            if (api.objects.interact(crate.get(), "Take-from")) {
                pending("Take onion", 4, () -> api.inventory.count(EventData.OLD_ONION) > before);
            }
            return;
        }
        walk(EventData.CRATE_TILE);
    }

    // ── Costume (Diango's Holiday-items) ─────────────────────────────────────

    private void costume() {
        // 1. Wear a Halloween piece from the inventory whose slot has no Halloween piece yet.
        Item wear = wearableForFreeSlot();
        if (wear != null) {
            if (storeOpen()) { closeStore(); return; }
            final int id = wear.getId();
            step = "Wear " + itemName(id);
            if (!main.cursor(W_ACTION)) return;
            if (api.inventory.equip(id)) {
                main.info("Wear {} (slot {}, {}/{} pieces)", itemName(id), slotOf(id), wornPieces() + 1, piecesNeeded);
                pending("Wear " + itemName(id), 3, () -> isWorn(id));
            } else {
                notWearable.add(id);
            }
            return;
        }
        // 2. Store open: take pieces for slots nothing covers yet.
        if (storeOpen()) {
            Widget pick = storePick();
            if (pick == null) { closeStore(); return; }
            final int id = pick.getItemId();
            step = "Take " + itemName(id);
            if (!main.cursor(W_ACTION)) return;
            if (api.widgets.interact(pick, "Take")) {
                takenFromStore.add(id);
                main.info("Diango: take {} (slot {})", itemName(id), slotOf(id));
                pending("Take " + itemName(id), 3, () -> api.inventory.contains(id));
            }
            return;
        }
        if (storeVisits >= 3) {
            fail("Diango has no more Halloween pieces for free slots: wear " + piecesNeeded + " pieces by hand");
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
                pending("Open Diango's store", 6, this::storeOpen);
            }
            return;
        }
        walk(EventData.DIANGO_TILE);
    }

    /**
     * Next store item to take: one per free equipment slot, the chosen set first, then the other
     * sets, until worn + carried pieces cover piecesNeeded slots. Null when nothing more is needed.
     */
    private Widget storePick() {
        Set<Integer> covered = coveredSlots();
        if (covered.size() >= piecesNeeded) return null;
        List<Widget> items = api.widgets.query().group(EventData.DIANGO_STORE_GROUP).list();
        CostumeSet preferred = config.costumeSet();
        Widget pick = pickFrom(items, covered, preferred);
        if (pick != null) return pick;
        for (CostumeSet s : CostumeSet.values()) {
            if (s == preferred) continue;
            pick = pickFrom(items, covered, s);
            if (pick != null) return pick;
        }
        return null;
    }

    private Widget pickFrom(List<Widget> items, Set<Integer> covered, CostumeSet set) {
        for (Widget w : items) {
            int id = w.getItemId();
            if (id <= 0 || takenFromStore.contains(id) || w.isHidden()) continue;
            if (!set.matches(itemName(id))) continue;
            int slot = slotOf(id);
            if (slot < 0 || covered.contains(slot)) continue;
            return w;
        }
        return null;
    }

    /** Equipment slots covered by a Halloween piece, worn or carried. */
    private Set<Integer> coveredSlots() {
        Set<Integer> s = new HashSet<>();
        for (int id : halloweenIds(InventoryID.WORN)) s.add(slotOf(id));
        for (int id : halloweenIds(InventoryID.INV)) s.add(slotOf(id));
        s.remove(-1);
        return s;
    }

    private Item wearableForFreeSlot() {
        Set<Integer> wornSlots = new HashSet<>();
        for (int id : halloweenIds(InventoryID.WORN)) wornSlots.add(slotOf(id));
        ItemContainer c = client.getItemContainer(InventoryID.INV);
        if (c == null) return null;
        for (Item i : c.getItems()) {
            if (i == null || i.getId() <= 0 || notWearable.contains(i.getId())) continue;
            if (!CostumeSet.isHalloweenWearable(itemName(i.getId()))) continue;
            int slot = slotOf(i.getId());
            if (slot < 0 || wornSlots.contains(slot)) continue;
            return i;
        }
        return null;
    }

    private List<Integer> halloweenIds(int containerId) {
        List<Integer> out = new java.util.ArrayList<>();
        ItemContainer c = client.getItemContainer(containerId);
        if (c == null) return out;
        for (Item i : c.getItems()) {
            if (i == null || i.getId() <= 0 || notWearable.contains(i.getId())) continue;
            if (CostumeSet.isHalloweenWearable(itemName(i.getId()))) out.add(i.getId());
        }
        return out;
    }

    private int wornPieces() {
        Set<Integer> s = new HashSet<>();
        for (int id : halloweenIds(InventoryID.WORN)) s.add(slotOf(id));
        return s.size();
    }

    private boolean isWorn(int id) {
        return halloweenIds(InventoryID.WORN).contains(id);
    }

    /** Equipment slot index of an item, or -1 when it can't be worn. */
    private int slotOf(int id) {
        try {
            ItemStats st = itemManager.getItemStats(id);
            if (st == null || !st.isEquipable() || st.getEquipment() == null) return -1;
            return st.getEquipment().getSlot();
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean storeOpen() {
        Widget w = client.getWidget(EventData.DIANGO_STORE_GROUP, 0);
        return w != null && !w.isHidden();
    }

    /** Closes Diango's store with its own Close button (78.2), the way a player clicks the X. */
    private void closeStore() {
        step = "Close Diango's store";
        if (pendingDone != null) return;
        if (!main.cursor(W_ACTION)) return;
        closeTries++;
        Widget close = client.getWidget(EventData.DIANGO_STORE_GROUP, EventData.DIANGO_STORE_CLOSE_CHILD);
        boolean sent = false;
        if (close != null && !close.isHidden()) {
            sent = api.widgets.interact(close, "Close");
            if (!sent && close.getChildren() != null) {
                for (Widget ch : close.getChildren()) {
                    if (ch != null && hasAction(ch, "Close")) { sent = api.widgets.interact(ch, "Close"); break; }
                }
            }
        }
        if (!sent) {
            List<Widget> any = api.widgets.query().group(EventData.DIANGO_STORE_GROUP).visible().hasAction("Close").list();
            if (!any.isEmpty()) sent = api.widgets.interact(any.get(0), "Close");
        }
        main.info("Diango store: Close {} (try {})", sent ? "clicked" : "NOT found", closeTries);
        if (!sent && closeTries >= 3) {
            // Last resort: walking away closes any interface.
            WorldPoint me = api.player.getWorldLocation();
            if (me != null) api.movement.walk(new WorldPoint(me.getX(), me.getY() - 2, me.getPlane()));
        }
        pending("Close Diango's store", 3, () -> !storeOpen());
    }

    private static boolean hasAction(Widget w, String action) {
        String[] a = w.getActions();
        if (a == null) return false;
        for (String s : a) if (s != null && s.equalsIgnoreCase(action)) return true;
        return false;
    }

    private String itemName(int id) {
        try { return client.getItemDefinition(id).getName(); } catch (Throwable t) { return "item " + id; }
    }

    // ── Finish ───────────────────────────────────────────────────────────────

    private void finish() {
        step = "Complete";
        if (!rewardClosed) {
            rewardClosed = true;
            Widget close = client.getWidget(EventData.REWARD_GROUP, EventData.REWARD_CLOSE_CHILD);
            if (close != null && !close.isHidden() && main.cursor(W_ACTION)) {
                api.widgets.interact(close, "Close");
                return;
            }
        }
        main.complete();
    }

    private void fail(String reason) {
        step = reason;
        main.fail(reason);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void walk(WorldPoint tile) {
        if (main.walking()) return;
        WorldPoint me = api.player.getWorldLocation();
        if (me != null && me.distanceTo(tile) <= 1) return;
        if (!main.cursor(W_WALK)) return;
        main.info("Walk to {}", tile);
        api.walker.walkTo(tile);
        pending("Walk to " + tile.getX() + "," + tile.getY(), 2, () -> api.walker.isWalking());
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

    private int[] remaining(int[] ids, int[] varbits) {
        int n = 0;
        int[] out = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            if (client.getVarbitValue(varbits[i]) == 0) out[n++] = ids[i];
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static int varbitFor(int npcId, int[] ids, int[] varbits) {
        for (int i = 0; i < ids.length; i++) if (ids[i] == npcId) return varbits[i];
        return varbits[0];
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

    private static String safe(String s) { return s == null ? "" : s; }
}
