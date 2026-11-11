import net.runelite.api.coords.WorldPoint;

/**
 * IDs, tiles and varbits of the 2026 Halloween event ("All trick, no treats"), from the
 * tick-by-tick recording (2026-11-11, world 397) cross-checked with the OSRS wiki.
 */
public final class EventData {
    private EventData() {}

    // ── Progress (varbit 16579 / varp 5996) ──────────────────────────────────
    public static final int VARBIT_PROGRESS = 16579;
    public static final int STAGE_NOT_STARTED = 0;
    public static final int STAGE_SMASH = 5;          // smash the 8 jack-o-lanterns
    public static final int STAGE_REPORT_LANTERNS = 10;
    public static final int STAGE_SPOOK = 15;         // costume + spook 5 children
    public static final int STAGE_REPORT_CHILDREN = 20;
    public static final int STAGE_CHEMIST = 25;       // riddle -> sugar
    public static final int STAGE_ADULTS = 30;        // candy onions -> 5 adults
    public static final int STAGE_REPORT_ADULTS = 35; // talk + cutscene
    public static final int STAGE_REWARD = 40;
    public static final int STAGE_DONE = 45;

    // ── NPCs ─────────────────────────────────────────────────────────────────
    public static final int HENJA = 3306;
    public static final int ANGEL = 3369;
    public static final int CHEMIST = 1146;
    public static final int DIANGO = 8693;
    public static final int[] CHILDREN = {16778, 16779, 16780, 16781, 16782};
    public static final int[] ADULTS = {16794, 16795, 16796, 16797, 16798};
    /** Sub-varbits (1 = done), index-aligned with CHILDREN / ADULTS. */
    public static final int[] CHILD_VARBITS = {16615, 16618, 16625, 16626, 16627};
    public static final int[] ADULT_VARBITS = {16628, 16636, 16637, 16638, 16645};

    // ── Objects ──────────────────────────────────────────────────────────────
    public static final int CRATE_OF_ONIONS = 50779;
    public static final int RANGE = 9682;
    public static final int LANTERN_INTACT = 50777;    // impostor with "Smash"

    public static final class Lantern {
        public final WorldPoint tile;
        public final int baseId;
        public final int varbit;
        Lantern(int x, int y, int baseId, int varbit) {
            this.tile = new WorldPoint(x, y, 0);
            this.baseId = baseId;
            this.varbit = varbit;
        }
    }

    public static final Lantern[] LANTERNS = {
        new Lantern(2971, 3216, 50770, 16584),
        new Lantern(2962, 3215, 50774, 16612),
        new Lantern(2964, 3208, 50775, 16613),
        new Lantern(2966, 3203, 50771, 16585),
        new Lantern(2951, 3201, 50772, 16610),
        new Lantern(2954, 3206, 50776, 16614),
        new Lantern(2946, 3210, 50773, 16611),
        new Lantern(2950, 3219, 50742, 16583),
    };

    // ── Items ────────────────────────────────────────────────────────────────
    public static final int CANDY_ONION = 34732;
    public static final int SUGAR = 34734;
    public static final int MOLTEN_SUGAR = 34736;
    public static final int OLD_ONION = 34738;

    // ── Tiles ────────────────────────────────────────────────────────────────
    public static final WorldPoint HOUSE = new WorldPoint(2964, 3211, 0);        // Henja & Angel
    public static final WorldPoint CRATE_TILE = new WorldPoint(2968, 3217, 0);
    public static final WorldPoint RANGE_TILE = new WorldPoint(2969, 3209, 0);
    public static final WorldPoint CHEMIST_TILE = new WorldPoint(2933, 3210, 0);
    public static final WorldPoint DIANGO_TILE = new WorldPoint(3081, 3246, 0);
    /** Where the trick or treaters were seen; visited in turn when none is in view. */
    public static final WorldPoint[] ROAM = {
        new WorldPoint(2962, 3219, 0),
        new WorldPoint(2952, 3212, 0),
        new WorldPoint(2962, 3206, 0),
        new WorldPoint(2949, 3206, 0),
    };

    // ── Interfaces ───────────────────────────────────────────────────────────
    public static final int DIANGO_STORE_GROUP = 78;
    public static final int REWARD_GROUP = 807;

    // ── Texts ────────────────────────────────────────────────────────────────
    public static final String NOT_SCARY = "not even wearing a full costume";
    public static final String RIDDLE_WRONG = "That's not right";
    public static final String FALLBACK_RIDDLE_ANSWER = "105";
    public static final String[] CHEMIST_OPTIONS = {"Halloween", "Trick or treat", "more sugar"};
}
