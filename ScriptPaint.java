import java.awt.Dimension;
import java.awt.Graphics2D;

import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.pureapps.api.overlays.StatusCard;

public class ScriptPaint extends Overlay {

    private final Main main;
    private final HalloweenConfig config;
    private final StatusCard card = new StatusCard();

    ScriptPaint(Main main, HalloweenConfig config) {
        this.main = main;
        this.config = config;
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPosition(OverlayPosition.TOP_LEFT);
        setMovable(true);
    }

    @Override
    public Dimension render(Graphics2D g) {
        EventBrain b = main.brain();
        if (b == null) return null;
        card.clear();
        card.title("Halloween 2026");
        card.row("Stage", b.stage() + " / 45");
        String prog = b.progress();
        if (!prog.isEmpty()) card.row("Progress", prog);
        card.row("Step", b.step());
        long secs = (System.currentTimeMillis() - main.scriptStartTime) / 1000;
        card.row("Runtime", String.format("%d:%02d", secs / 60, secs % 60));
        if (main.cursorFlow != null) {
            card.row("Pair", "slot " + main.slot + ", " + main.cursorFlow.liveMembers() + " live member(s)");
            if (config.cursorFlowDebug()) card.row("Cursor", main.cursorFlow.describe());
        }
        return card.render(g, null);
    }
}
