package dev.custommodels.cmv.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The page control shared by the explorer and the wrap bank.
 *
 * <p>Laid out and drawn in one call, which also records where the buttons
 * ended up so a click can be tested against them afterwards. Doing it in two
 * passes would mean computing the same widths twice and letting them drift.</p>
 *
 * <p>The control is deliberately wider than the arrows alone: the page number
 * is the thing a player looks at to know where they are in twelve thousand
 * models, so it gets the middle and a readable amount of room. First and last
 * are separate buttons because "page 1 of 340" invites jumping to the end.</p>
 */
public final class Pager {

    /** What a click on the control asked for. */
    public enum Action {
        NONE, FIRST, PREVIOUS, NEXT, LAST
    }

    private static final int BUTTON_H = 14;
    private static final int GAP = 4;

    private int firstX;
    private int firstW;
    private int prevX;
    private int prevW;
    private int nextX;
    private int nextW;
    private int lastX;
    private int lastW;
    private int y;
    /**
     * False until the first draw. A click arriving before the control has been
     * laid out — which happens on the frame a screen opens — must hit nothing
     * rather than whatever zeros the fields happen to hold.
     */
    private boolean live;

    /**
     * Draws the control centred on {@code centreX}, and remembers its layout.
     *
     * @param page  zero-based
     * @param pages total, at least one
     */
    public void draw(GuiGraphicsExtractor graphics, Font font, int centreX, int top,
                     int page, int pages, int shown, int total, int mouseX, int mouseY) {
        this.y = top;
        this.live = true;

        String label = String.format("Page %,d of %,d", page + 1, pages);
        firstW = font.width("<<") + 10;
        prevW = font.width("< Prev") + 10;
        nextW = font.width("Next >") + 10;
        lastW = font.width(">>") + 10;
        int labelW = Math.max(font.width(label) + 16, 96);

        int width = firstW + prevW + labelW + nextW + lastW + GAP * 4;
        int x = centreX - width / 2;

        firstX = x;
        prevX = firstX + firstW + GAP;
        int labelX = prevX + prevW + GAP;
        nextX = labelX + labelW + GAP;
        lastX = nextX + nextW + GAP;

        boolean back = page > 0;
        boolean forward = page < pages - 1;

        button(graphics, font, firstX, firstW, "<<", back, mouseX, mouseY);
        button(graphics, font, prevX, prevW, "< Prev", back, mouseX, mouseY);

        GuiTheme.rect(graphics, labelX, y, labelX + labelW, y + BUTTON_H, GuiTheme.DARK_SUNKEN);
        GuiTheme.text(graphics, font, label,
                labelX + (labelW - font.width(label)) / 2, y + 3, GuiTheme.TEXT_BRIGHT, false);

        button(graphics, font, nextX, nextW, "Next >", forward, mouseX, mouseY);
        button(graphics, font, lastX, lastW, ">>", forward, mouseX, mouseY);

        // The count sits to the right of the control rather than inside it, so
        // that the page number stays the thing the eye lands on.
        String counts = String.format("%,d of %,d", shown, total);
        GuiTheme.text(graphics, font, counts, lastX + lastW + 10, y + 3,
                GuiTheme.TEXT_MUTED, false);
    }

    private void button(GuiGraphicsExtractor graphics, Font font, int x, int width,
                        String label, boolean enabled, int mouseX, int mouseY) {
        boolean hovered = enabled && hit(mouseX, mouseY, x, width);
        GuiTheme.rect(graphics, x, y, x + width, y + BUTTON_H,
                hovered ? GuiTheme.PANEL_ALT : GuiTheme.DARK_PANEL);
        int edge = hovered ? GuiTheme.ACCENT_BLUE : GuiTheme.BORDER;
        GuiTheme.rect(graphics, x, y, x + width, y + 1, edge);
        GuiTheme.rect(graphics, x, y + BUTTON_H - 1, x + width, y + BUTTON_H, edge);
        GuiTheme.text(graphics, font, label, x + (width - font.width(label)) / 2, y + 3,
                enabled ? (hovered ? GuiTheme.TEXT_HEADING : GuiTheme.TEXT_DIM)
                        : GuiTheme.TEXT_MUTED,
                false);
    }

    private boolean hit(int mouseX, int mouseY, int x, int width) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BUTTON_H;
    }

    /** What a click at this position would do. Safe to call before any draw. */
    public Action clicked(int mouseX, int mouseY) {
        if (!live) {
            return Action.NONE;
        }
        if (hit(mouseX, mouseY, firstX, firstW)) {
            return Action.FIRST;
        }
        if (hit(mouseX, mouseY, prevX, prevW)) {
            return Action.PREVIOUS;
        }
        if (hit(mouseX, mouseY, nextX, nextW)) {
            return Action.NEXT;
        }
        if (hit(mouseX, mouseY, lastX, lastW)) {
            return Action.LAST;
        }
        return Action.NONE;
    }

    /** Height the control occupies, for laying out whatever sits under it. */
    public static int height() {
        return BUTTON_H;
    }
}
