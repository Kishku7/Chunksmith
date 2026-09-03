package com.kishku7.chunksmith.worldenter.client;

import com.kishku7.chunksmith.worldenter.WorldEnterPregen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The world-enter progress screen: a filling bar, a live ETA, and a button that hands the world back
 * to the player.
 *
 * <p>This is the first {@link Screen} in Chunksmith, so two vanilla behaviours are worth spelling out
 * rather than leaving as bare overrides -- both of them break the feature silently if omitted:
 *
 * <ul>
 *   <li><b>{@link #isPauseScreen()} must be false.</b> It defaults to TRUE, and a true pause screen
 *       halts the integrated server. That would stop the very generation this screen exists to
 *       display: the bar would sit at 0% forever, looking for all the world like the pregen never
 *       started. There is no error to read in that state, which is what makes it dangerous.
 *   <li><b>Every exit releases the world.</b> Closing the screen while the world is still frozen
 *       would leave the player standing in a world that does not tick, with no UI left to fix it
 *       and nothing on screen explaining why. So Escape is allowed -- but {@link #onClose()} routes
 *       through the same {@code release()} the button uses. There is exactly one way out, and it is
 *       the safe one.
 * </ul>
 *
 * <p>Releasing does NOT stop generation, and the button says so. The remaining work carries on in
 * the background and yields to the player from then on, because release() hands the throttle's
 * player-reserve back at the same time.
 */
public final class WorldEnterScreen extends Screen {

    private static final int BAR_WIDTH = 300;
    private static final int BAR_HEIGHT = 14;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFA0A0A0;
    private static final int COLOR_BAR_BORDER = 0xFF000000;
    private static final int COLOR_BAR_TRACK = 0xFF303030;
    private static final int COLOR_BAR_FILL = 0xFF3CB043;
    private static final int COLOR_PANEL = 0xC8101010;
    private static final int COLOR_PANEL_EDGE = 0xFF000000;
    private static final int PANEL_HALF_WIDTH = 200;

    public WorldEnterScreen() {
        super(Component.literal("Preparing your world"));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(
                        Component.literal("Enter World Now"),
                        button -> onClose())
                .bounds((this.width - 200) / 2, barTop() + 58, 200, 20)
                .build());
    }

    /**
     * False, deliberately -- see the class javadoc. A pause screen would stop the integrated server
     * and therefore stop the generation being displayed.
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        WorldEnterPregen.release();
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int centerX = this.width / 2;
        int top = barTop();
        double fraction = WorldEnterPregen.fraction();

        // A backing panel. Not decoration: isPauseScreen() is false, so the live world renders behind
        // this screen at full brightness, and on grass or snow the un-backed text was genuinely hard
        // to read -- confirmed by eyeballing the first render rather than assumed.
        int panelLeft = centerX - PANEL_HALF_WIDTH;
        int panelRight = centerX + PANEL_HALF_WIDTH;
        graphics.fill(panelLeft - 1, top - 58, panelRight + 1, top + 100, COLOR_PANEL_EDGE);
        graphics.fill(panelLeft, top - 57, panelRight, top + 99, COLOR_PANEL);

        // ORDER MATTERS, and it is the reason this call is here rather than at the top of the
        // method. super draws the child widgets, so with super first the opaque panel above paints
        // straight OVER the button: it stayed clickable and answered `describe`, so the functional
        // gate passed green while the player could see no button at all. Panel, then widgets, then
        // our own text on top.
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        graphics.centeredText(this.font, this.title, centerX, top - 46, COLOR_TEXT);
        graphics.centeredText(this.font,
                Component.literal("Pre-generating terrain so it is ready before you arrive."),
                centerX, top - 32, COLOR_DIM);

        int left = centerX - BAR_WIDTH / 2;
        int right = left + BAR_WIDTH;
        graphics.fill(left - 1, top - 1, right + 1, top + BAR_HEIGHT + 1, COLOR_BAR_BORDER);
        graphics.fill(left, top, right, top + BAR_HEIGHT, COLOR_BAR_TRACK);
        int filled = (int) Math.round(BAR_WIDTH * fraction);
        if (filled > 0) {
            graphics.fill(left, top, left + filled, top + BAR_HEIGHT, COLOR_BAR_FILL);
        }

        graphics.centeredText(this.font,
                Component.literal(String.format("%.1f%%  --  %,d chunks", fraction * 100.0,
                        WorldEnterPregen.chunksDone())),
                centerX, top + BAR_HEIGHT + 8, COLOR_TEXT);
        graphics.centeredText(this.font,
                Component.literal(WorldEnterPregen.eta()),
                centerX, top + BAR_HEIGHT + 22, COLOR_DIM);
        graphics.centeredText(this.font,
                Component.literal("Entering now will not stop it -- generation continues in the background."),
                centerX, top + 84, COLOR_DIM);
    }

    private int barTop() {
        return this.height / 2 - BAR_HEIGHT / 2;
    }
}
