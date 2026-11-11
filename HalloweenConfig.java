import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import org.pureapps.api.ui.plugins.ShowIf;

@ConfigGroup("halloween2026")
public interface HalloweenConfig extends Config {

    // ── Event ────────────────────────────────────────────────────────────────
    @ConfigItem(
        keyName = "costumeSet",
        name = "Costume set",
        description = "Halloween set taken from Diango when fewer than 'Costume pieces' Halloween items are worn. "
            + "Other sets are used as fallbacks for pieces the store hides (already owned).",
        position = 0
    )
    default CostumeSet costumeSet() { return CostumeSet.SKELETON; }

    @ConfigItem(
        keyName = "costumePieces",
        name = "Costume pieces",
        description = "Halloween items to wear before spooking the children (wiki: 3-4 needed).",
        position = 1
    )
    default int costumePieces() { return 4; }

    @ConfigItem(
        keyName = "riddleAnswer",
        name = "Riddle answer",
        description = "The Chemist's riddle answer (COFFIN = C6+O8+F9+F9+I53+N7 = 92). 105 is tried if it's rejected.",
        position = 2
    )
    default String riddleAnswer() { return "92"; }

    // ── Pairing ──────────────────────────────────────────────────────────────
    @ConfigSection(
        name = "Pairing",
        description = "Run several accounts side by side behind one shared, human-like cursor",
        position = 5
    )
    String pairingSection = "pairingSection";

    @ConfigItem(
        keyName = "pairId",
        name = "Pair ID",
        description = "Blank = solo. Every client of a group sets the SAME Pair ID and a DIFFERENT member slot.",
        section = "pairingSection",
        position = 0
    )
    default String pairId() { return ""; }

    @ConfigItem(
        keyName = "memberSlot",
        name = "Member slot",
        description = "1-8, unique within the pair. Lower slots win cursor ties.",
        section = "pairingSection",
        position = 1
    )
    default int memberSlot() { return 1; }

    // ── Cursor Flow ──────────────────────────────────────────────────────────
    @ConfigSection(
        name = "Cursor Flow",
        description = "One mouse shared by the paired clients: only one clicks at a time, hand-offs look human",
        position = 6,
        closedByDefault = true
    )
    String cursorFlowSection = "cursorFlowSection";

    @ConfigItem(
        keyName = "cursorFlowEnabled",
        name = "Enable cursor flow",
        description = "Only one paired client holds the cursor at a time. Set the same on ALL clients.",
        section = "cursorFlowSection",
        position = 0
    )
    default boolean cursorFlowEnabled() { return true; }

    @ShowIf(key = "cursorFlowEnabled", value = "true")
    @ConfigItem(
        keyName = "cursorMaxHoldTicks",
        name = "Max hold (ticks)",
        description = "Longest a client keeps the cursor while another one is waiting for it.",
        section = "cursorFlowSection",
        position = 1
    )
    default int cursorMaxHoldTicks() { return 6; }

    @ShowIf(key = "cursorFlowEnabled", value = "true")
    @ConfigItem(
        keyName = "cursorCrossMinMs",
        name = "Cross time min (ms)",
        description = "Shortest time for the hand to move between windows (close windows).",
        section = "cursorFlowSection",
        position = 2
    )
    default int cursorCrossMinMs() { return 120; }

    @ShowIf(key = "cursorFlowEnabled", value = "true")
    @ConfigItem(
        keyName = "cursorCrossMaxMs",
        name = "Cross time max (ms)",
        description = "Longest time for the hand to move between windows (far apart).",
        section = "cursorFlowSection",
        position = 3
    )
    default int cursorCrossMaxMs() { return 450; }

    @ShowIf(key = "cursorFlowEnabled", value = "true")
    @ConfigItem(
        keyName = "cursorFlowDebug",
        name = "Debug cursor flow",
        description = "Log every request/claim/release decision and show the cursor state on the paint.",
        section = "cursorFlowSection",
        position = 4
    )
    default boolean cursorFlowDebug() { return false; }
}
