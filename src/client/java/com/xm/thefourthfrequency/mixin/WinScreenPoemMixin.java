package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.client_ui.PoemOutroTimeline;
import com.xm.thefourthfrequency.client_ui.PoemSkipGuard;
import com.xm.thefourthfrequency.client_ui.WorldInterfaceVanillaPoemClient;
import com.xm.thefourthfrequency.networking.PoemStartS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuses the real End poem screen while replacing only its encounter-authorized poem and credits
 * resources.
 */
@Mixin(WinScreen.class)
public abstract class WinScreenPoemMixin {
	@Shadow @Final @Mutable private Runnable onFinished;
	@Shadow private List<FormattedCharSequence> lines;
	@Shadow private boolean speedupActive;
	@Shadow private float scroll;
	@Shadow private float scrollSpeed;

	@Shadow
	private void respawn() {
		throw new AssertionError("shadow");
	}

	/**
	 * The screen's height, read through the superclass rather than shadowed.
	 *
	 * <p>{@code height} is declared on {@code Screen}, not on {@code WinScreen}, and Mixin resolves
	 * {@code @Shadow} <em>fields</em> against the target class alone - inheritance is not searched,
	 * because a field access is not virtual. Shadowing it therefore passes every check this project
	 * can run (it remaps correctly, and the field genuinely exists on the parent) and then fails at
	 * class-load with "was not located in the target class". Shadow methods do not have this problem,
	 * which is why {@code respawn} above is fine.
	 *
	 * <p>The cast is the ordinary way out: after application {@code this} really is a
	 * {@code WinScreen}, so it really is a {@code Screen}, and the field is public.
	 */
	@Unique
	private int thefourthfrequency$screenHeight() {
		return ((Screen) (Object) this).height;
	}

	/** When the closing quote came to rest, or -1 while the roll is still moving. */
	@Unique private long thefourthfrequency$settledAtMillis = -1L;
	@Unique private PoemStartS2C thefourthfrequency$poem;
	@Unique private boolean thefourthfrequency$skipped;
	@Unique private boolean thefourthfrequency$completionStarted;
	/**
	 * Index of the first line of the closing quote, or -1 before it has been read.
	 *
	 * <p>The quote is the last thing {@code init} appends, so "this line or later" is the whole test.
	 */
	@Unique private int thefourthfrequency$postcreditsFrom = -1;
	/** Set by the centring redirect, read by the drawing one, in the same loop iteration. */
	@Unique private boolean thefourthfrequency$drawingPostcredits;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void thefourthfrequency$bindWorldInterfacePoem(boolean includesPoem, Runnable vanillaFinish,
			CallbackInfo callback) {
		thefourthfrequency$poem = WorldInterfaceVanillaPoemClient.claim(includesPoem);
		if (thefourthfrequency$poem == null) return;
		PoemSkipGuard.begin();
		// The multiplier has to be applied to the field here as well as to the calculation below.
		// The constructor assigns unmodifiedScrollSpeed straight to scrollSpeed without going
		// through calculateScrollSpeed, and that calculation is only ever reached again from a key
		// event - so without this line a poem watched in silence ran at vanilla's own pace for its
		// entire length, and only jumped to the intended speed if the player happened to press
		// something. Written as a multiply because the field already holds unmodifiedScrollSpeed at
		// this point, which keeps the two paths reading the same way.
		scrollSpeed *= BASE_SCROLL_SCALE;
		Runnable originalFinish = onFinished;
		onFinished = () -> {
			if (thefourthfrequency$completionStarted) return;
			thefourthfrequency$completionStarted = true;
			WorldInterfaceProtocol.PoemCompletion completion = thefourthfrequency$skipped
					? WorldInterfaceProtocol.PoemCompletion.SKIPPED
					: WorldInterfaceProtocol.PoemCompletion.READ;
			WorldInterfaceVanillaPoemClient.finish(thefourthfrequency$poem, completion, originalFinish,
					() -> thefourthfrequency$completionStarted = false);
		};
	}

	@ModifyArg(method = "init", at = @At(value = "INVOKE", ordinal = 0, target =
			"Lnet/minecraft/client/gui/screens/WinScreen;wrapCreditsIO(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/gui/screens/WinScreen$CreditsReader;)V"), index = 0)
	private Identifier thefourthfrequency$selectWorldInterfacePoem(Identifier vanillaPoem) {
		return thefourthfrequency$poem == null
				? vanillaPoem : WorldInterfaceVanillaPoemClient.poemResource(thefourthfrequency$poem);
	}

	// Ordinal 1 is the credits roll: init() reads the poem, then the credits, then the postcredits
	// quote. The poem read sits behind the `poem` flag at runtime, but the ordinal is a bytecode
	// position, so it stays 1 either way.
	@ModifyArg(method = "init", at = @At(value = "INVOKE", ordinal = 1, target =
			"Lnet/minecraft/client/gui/screens/WinScreen;wrapCreditsIO(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/gui/screens/WinScreen$CreditsReader;)V"), index = 0)
	private Identifier thefourthfrequency$selectWorldInterfaceCredits(Identifier vanillaCredits) {
		return thefourthfrequency$poem == null ? vanillaCredits : WorldInterfaceVanillaPoemClient.creditsResource();
	}

	// Ordinal 2 is the closing quote the roll ends on. Vanilla's is a signed sailing quote that has
	// nothing to do with this ending, so the authored roll owns this read too.
	@ModifyArg(method = "init", at = @At(value = "INVOKE", ordinal = 2, target =
			"Lnet/minecraft/client/gui/screens/WinScreen;wrapCreditsIO(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/gui/screens/WinScreen$CreditsReader;)V"), index = 0)
	private Identifier thefourthfrequency$selectWorldInterfacePostcredits(Identifier vanillaPostcredits) {
		// Runs immediately before the read that appends the quote, so the current line count is
		// exactly where the quote begins. Recorded here rather than counted afterwards because this
		// is the one point that knows which of the three reads is about to happen.
		thefourthfrequency$postcreditsFrom = thefourthfrequency$poem == null ? -1 : lines.size();
		return thefourthfrequency$poem == null
				? vanillaPostcredits : WorldInterfaceVanillaPoemClient.postcreditsResource();
	}

	/**
	 * Centres the closing quote, and marks the line so the draw below can enlarge it.
	 *
	 * <p>The quote is read with the poem reader, which lays its lines out left-aligned like the poem
	 * - correct for a poem being read as text, wrong for the two lines the credits end on. Those are
	 * the last thing on screen and they are addressed to the player, so they get the credits' own
	 * centred treatment instead.
	 *
	 * <p>Redirecting the centring test rather than injecting somewhere is what makes the line index
	 * reachable: it is the loop variable, and this call is the only place it is handed to anything.
	 */
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/ints/IntSet;contains(I)Z"))
	private boolean thefourthfrequency$centreTheClosingQuote(IntSet centered, int line) {
		thefourthfrequency$drawingPostcredits = thefourthfrequency$postcreditsFrom >= 0
				&& line >= thefourthfrequency$postcreditsFrom;
		return thefourthfrequency$drawingPostcredits || centered.contains(line);
	}

	/**
	 * Draws the closing quote larger than the roll it follows.
	 *
	 * <p>Scaled about the line's own centre, so enlarging it does not also move it off the axis the
	 * centring just put it on. Everything else draws through untouched.
	 */
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;"
					+ "Lnet/minecraft/util/FormattedCharSequence;III)V"))
	private void thefourthfrequency$enlargeTheClosingQuote(GuiGraphics graphics, Font font,
			FormattedCharSequence line, int x, int y, int colour) {
		if (!thefourthfrequency$drawingPostcredits) {
			graphics.drawString(font, line, x, y, colour);
			return;
		}
		float centreX = x + font.width(line) / 2.0F;
		float centreY = y + font.lineHeight / 2.0F;
		graphics.pose().pushMatrix();
		graphics.pose().translate(centreX, centreY);
		graphics.pose().scale(POSTCREDITS_SCALE, POSTCREDITS_SCALE);
		graphics.pose().translate(-centreX, -centreY);
		graphics.drawString(font, line, x, y, colour);
		graphics.pose().popMatrix();
	}

	/**
	 * How much larger the closing quote is drawn than the credits roll.
	 *
	 * <p>Enough to read as the last word rather than as one more credit line, and short of the point
	 * where a long line would run off the sides at the narrowest supported GUI scale.
	 */
	@Unique private static final float POSTCREDITS_SCALE = 1.45F;

	/**
	 * Speeds the roll up, and speeds the held-space skip up further.
	 *
	 * <p>Two separate multipliers on purpose. The poem and the roll together are several minutes at
	 * vanilla's pace, which was written for a credits list rather than for text a player is expected
	 * to read to the end; and the hold-to-skip was five times that, which is only fast if the thing
	 * being skipped was already short. Raising the base alone would have narrowed the gap between
	 * reading and skipping until holding space stopped feeling like it did anything.
	 *
	 * <p>Applied to the computed speed rather than to {@code unmodifiedScrollSpeed}, so vanilla's own
	 * modifier-key stacking and the direction sign both survive untouched.
	 */
	@Inject(method = "calculateScrollSpeed", at = @At("RETURN"), cancellable = true)
	private void thefourthfrequency$quickenTheRoll(CallbackInfoReturnable<Float> callback) {
		if (thefourthfrequency$poem == null) return;
		// Once the quote has settled the roll is over. Holding the speed at zero is what keeps it in
		// the middle of the screen through the hold and the fade, instead of drifting on out of shot
		// while the frame darkens around where it used to be.
		if (thefourthfrequency$settledAtMillis >= 0L) {
			callback.setReturnValue(0.0F);
			return;
		}
		callback.setReturnValue(callback.getReturnValueF()
				* (speedupActive ? SKIP_SCROLL_SCALE : BASE_SCROLL_SCALE));
	}

	/** Multiplier on the unassisted scroll. */
	@Unique private static final float BASE_SCROLL_SCALE = 2.6F;
	/** Multiplier while space is held. Above the base, so skipping stays clearly faster than reading. */
	@Unique private static final float SKIP_SCROLL_SCALE = 4.0F;

	@Inject(method = "onClose", at = @At("HEAD"))
	private void thefourthfrequency$rememberExplicitSkip(CallbackInfo callback) {
		if (thefourthfrequency$poem != null) {
			thefourthfrequency$skipped = true;
			PoemSkipGuard.end();
		}
	}

	/**
	 * Holds Escape shut for the poem's opening seconds, and answers the press it refuses.
	 *
	 * <p>Before {@code Screen.keyPressed}, which is what turns Escape into {@code onClose} - so a
	 * refused press never reaches the close path and the skip is not recorded. Returning true
	 * consumes the key: vanilla's own speed-up handling below is for the modifier keys and space,
	 * and none of them is what was pressed.
	 *
	 * <p>Only ever narrows Escape, and only while an authored poem is up. Every other key, and every
	 * other win screen, passes straight through.
	 */
	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$holdTheExitBrieflyOpen(KeyEvent event,
			CallbackInfoReturnable<Boolean> callback) {
		if (thefourthfrequency$poem == null || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
		if (PoemSkipGuard.allowsSkip()) return;
		PoemSkipGuard.refuse();
		callback.setReturnValue(true);
	}

	/**
	 * Draws the refusal notice and the closing fade over the finished frame.
	 *
	 * <p>At TAIL so both land on top of the poem and the vignette rather than under them, and in this
	 * order so the blackout covers the notice too - once the story is fading out, an aside about the
	 * exit key is no longer something to keep on screen.
	 */
	@Inject(method = "render", at = @At("TAIL"))
	private void thefourthfrequency$drawOverlays(GuiGraphics graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo callback) {
		if (thefourthfrequency$poem == null) return;
		Minecraft client = Minecraft.getInstance();
		PoemSkipGuard.renderNotice(graphics, client.font, client.getWindow().getGuiScaledWidth());
		if (thefourthfrequency$settledAtMillis < 0L) return;
		float alpha = PoemOutroTimeline.blackoutAlpha(
				System.currentTimeMillis() - thefourthfrequency$settledAtMillis);
		if (alpha <= 0.0F) return;
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(),
				Math.round(alpha * 255.0F) << 24);
	}

	/**
	 * Ends the poem on the settled quote instead of on a scroll count.
	 *
	 * <p>Vanilla pins the last line once it crosses the middle of the screen, and then keeps counting
	 * scroll until {@code totalScrollLength + 2 * height + 24} before it hands the world back. The
	 * text does not move for any of that: at a 480-high GUI it is roughly another screen and a half
	 * of scroll spent on a motionless frame, which reads as the credits having stopped working.
	 *
	 * <p>So the pin becomes the cue. The condition below is vanilla's own, reproduced from
	 * {@code render}: the last line sits at {@code height + 150 + (size - 1) * 12 - scroll}, and the
	 * roll stops advancing it once that is above {@code height / 2 - 6}. From there
	 * {@link PoemOutroTimeline} holds it, fades the frame out, and hands the world back from behind
	 * full black.
	 *
	 * <p>Cancelling the vanilla tick on the last frame skips one music and sound manager tick. That
	 * is the tick the screen is replaced on, so there is nothing after it for the loss to affect.
	 */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void thefourthfrequency$settleThenFadeOut(CallbackInfo callback) {
		if (thefourthfrequency$poem == null || lines.isEmpty()) return;
		if (thefourthfrequency$settledAtMillis < 0L) {
			int quote = thefourthfrequency$quoteLine();
			if (quote < 0) return;
			// Line y is base + index * 12 - scroll, with base = height + 150; both constants are
			// vanilla's, read out of render. Settle the instant the quote reaches the middle.
			int screenHeight = thefourthfrequency$screenHeight();
			float quoteY = screenHeight + 150.0F + quote * 12.0F - scroll;
			if (quoteY > screenHeight / 2.0F) return;
			thefourthfrequency$settledAtMillis = System.currentTimeMillis();
			// Written to the field, not left to calculateScrollSpeed. render advances scroll by the
			// stored scrollSpeed every frame, and that field is only ever recomputed in the
			// constructor and on a key event - so returning zero from the calculation, on its own,
			// stops nothing until the player happens to press something. This is why the quote kept
			// drifting past the middle of the screen.
			scrollSpeed = 0.0F;
			return;
		}
		if (PoemOutroTimeline.finished(
				System.currentTimeMillis() - thefourthfrequency$settledAtMillis)) {
			respawn();
			callback.cancel();
		}
	}

	/**
	 * The line the roll should come to rest on: the closing quote itself.
	 *
	 * <p>Not the last line. {@code addPoemFile} follows every line it reads with a blank one and then
	 * appends eight more, so {@code lines.size() - 1} is empty - which is what vanilla's own centring
	 * pins, and why the credits appeared to stop on an empty screen with the thank-you already gone
	 * past. The quote is the first line with any width in the postcredits range.
	 *
	 * <p>Resolved once and remembered: {@code Font#width} on every line of the tail, every tick,
	 * would be work for an answer that cannot change.
	 */
	@Unique
	private int thefourthfrequency$quoteLine() {
		if (thefourthfrequency$resolvedQuoteLine != Integer.MIN_VALUE) {
			return thefourthfrequency$resolvedQuoteLine;
		}
		if (thefourthfrequency$postcreditsFrom < 0) return -1;
		Font font = Minecraft.getInstance().font;
		for (int line = thefourthfrequency$postcreditsFrom; line < lines.size(); line++) {
			if (font.width(lines.get(line)) > 0) {
				thefourthfrequency$resolvedQuoteLine = line;
				return line;
			}
		}
		thefourthfrequency$resolvedQuoteLine = -1;
		return -1;
	}

	@Unique private int thefourthfrequency$resolvedQuoteLine = Integer.MIN_VALUE;
}
