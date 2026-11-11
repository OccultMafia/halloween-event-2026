/** Halloween outfits Diango's Holiday-items store hands out, matched by item name prefix. */
public enum CostumeSet {
    SKELETON("Skeleton ", "lantern", "tabard"),
    GRIM_REAPER("Grim reaper"),
    SPOOKY("Spooky ", "chair"),
    WITCH("Witch "),
    BANSHEE("Banshee");

    private final String prefix;
    private final String[] excluded;

    CostumeSet(String prefix, String... excluded) {
        this.prefix = prefix;
        this.excluded = excluded;
    }

    public boolean matches(String itemName) {
        if (itemName == null) return false;
        String n = itemName.toLowerCase();
        if (!n.startsWith(prefix.toLowerCase())) return false;
        for (String x : excluded) {
            if (n.contains(x)) return false;
        }
        return true;
    }

    /** Any wearable Halloween item counted towards the costume (worn pieces). */
    public static boolean isHalloweenWearable(String itemName) {
        if (itemName == null) return false;
        for (CostumeSet s : values()) {
            if (s.matches(itemName)) return true;
        }
        String n = itemName.toLowerCase();
        return n.startsWith("spookier ") || n.startsWith("gravedigger ") || n.startsWith("clown ")
            || n.equals("scythe") || n.equals("jack lantern mask") || n.equals("zombie head")
            || n.equals("halloween wig") || n.equals("cobweb cape") || n.equals("carved pumpkin head")
            || n.startsWith("vampyre ");
    }
}
