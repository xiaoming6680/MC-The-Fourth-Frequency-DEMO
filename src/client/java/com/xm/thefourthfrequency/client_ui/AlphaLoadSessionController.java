package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.config.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

public final class AlphaLoadSessionController {
	private static final String JAVA_ICON_RESOURCE =
			"/assets/thefourthfrequency/textures/gui/alpha_java_icon.png";
	private static final String VANILLA_VERSION_PREFIX = "Minecraft ";
	private static final String MENU_VERSION_TEXT = "Minecraft 1.0.0";
	/**
	 * The window bar, once the downgrade has happened. Identical to the in-game version stamp.
	 *
	 * <p>These used to be deliberately different - "Minecraft Alpha 1.0.0" on the chrome and
	 * "Minecraft 1.0.0" on the screen - on the reasoning that a launcher of that era named the era
	 * while the client named the build. Two strings for one identity is a difference the player has
	 * to account for, and the thing they are being told is that this client <em>is</em> 1.0.0. It
	 * now says exactly that, in both places and in every session.
	 *
	 * <p>It also carries no world-context suffix. See {@link #titleForStage}: every stage before the
	 * last is still a Minecraft that knows which world it is showing, and the last one is the
	 * identity this client keeps from then on - on the menu, in singleplayer and in multiplayer
	 * alike. A title that read "Minecraft 1.0.0 - Singleplayer World" in a world and "Minecraft
	 * 1.0.0" on the menu would be two states rather than one permanent one.
	 */
	private static final String MENU_WINDOW_TITLE = MENU_VERSION_TEXT;
	private static boolean initialized;
	private static boolean active;
	private static boolean corruptionEverPlayed;
	private static boolean corruptionInProgress;
	private static boolean persistentStartupPending;
	private static boolean persistentStartupApplied;
	private static boolean persistentInitialPackSelectionPrepared;
	private static boolean persistentIdentityPrimed;
	private static boolean presentationRetired;
	private static boolean javaIconApplied;
	private static boolean resourceReloadFinished = true;
	private static boolean resourceReloadInProgress;
	private static boolean resourceReloadFailed;
	private static boolean resourceReloadRequestedThisSession;
	private static boolean suppressNextResourceReloadAnimation;
	private static boolean currentViewportFlooded;
	private static int reloadGeneration;
	private static int corruptionPlayCount;
	private static int javaIconAppliedAtScreenTick = -1;
	private static int appliedVersionStage = -1;
	private static int legacyLoadingScreensRendered;
	private static int lastLoadingScreenTicks;
	private static int lastFailureCopies;
	private static int suppressedResourceReloadAnimations;
	private static int persistentAlphaLoadingOverlays;
	private static int persistentAlphaLoadingFirstFrames;
	private static String pendingRegressionScreenshot;
	private static boolean corruptionScreenshotRequested;
	private static boolean legacyScreenshotRequested;
	private static boolean lastViewportFlooded;
	private static SessionKind sessionKind = SessionKind.MULTIPLAYER;
	private static String launchedVersion = "1.21.11";
	private static String appliedWindowTitle = "";
	private static List<String> activePackOrder = List.of();

	private AlphaLoadSessionController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		WorldInterfaceResourcePackLease.initialize();
		presentationRetired = WorldInterfaceResourcePackLease.presentationRetired();
		ModContainer container = FabricLoader.getInstance().getModContainer(TheFourthFrequency.MOD_ID)
				.orElseThrow(() -> new IllegalStateException("Missing own Fabric mod container"));
		registerPack(container, "golden_days_base", "Golden Days Base");
		registerPack(container, "golden_days_alpha", "Golden Days Alpha");
		corruptionEverPlayed = ConfigManager.loadClientState().alphaDowngradeComplete();
		persistentStartupPending = corruptionEverPlayed && !presentationRetired;

		// Two entry points for one session, because the question they can answer is not the same.
		//
		// The downgrade has to be armed before the loading screen draws, which is why INIT is here at
		// all - but INIT is also too early to ask whether the server runs this mod: the channel test
		// reads Minecraft#getConnection, and there is no player yet to hang it off. What is knowable
		// at INIT is an integrated server, which is this process and therefore certainly this mod, so
		// that is the case it covers - and it is the case the whole sequence was authored against.
		//
		// A remote server is decided at JOIN instead, which fires from handleLogin and so still lands
		// while the world is coming up. begin() is idempotent, so single-player does not start twice.
		// A server without this mod reaches neither, which is the point: joining somebody else's
		// vanilla server used to swap that player's resource packs and rename their window.
		ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() -> {
			if (client.hasSingleplayerServer()) begin(client);
		}));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (ModWorldPresence.currentWorldRunsThisMod()) begin(client);
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> end(client)));
		ClientTickEvents.END_CLIENT_TICK.register(AlphaLoadSessionController::clientTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(AlphaLoadSessionController::end);
	}

	private static void registerPack(ModContainer container, String path, String displayName) {
		Identifier id = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
		if (!ResourceLoader.registerBuiltinPack(id, container, Component.literal(displayName),
				PackActivationType.NORMAL)) {
			throw new IllegalStateException("Missing embedded resource pack resourcepacks/" + path);
		}
	}

	private static void begin(Minecraft client) {
		if (presentationRetired || active || client.getWindow() == null) return;
		active = true;
		corruptionInProgress = false;
		resourceReloadFailed = false;
		suppressNextResourceReloadAnimation = false;
		currentViewportFlooded = false;
		sessionKind = client.hasSingleplayerServer() ? SessionKind.SINGLEPLAYER : SessionKind.MULTIPLAYER;
		launchedVersion = client.getLaunchedVersion() == null || client.getLaunchedVersion().isBlank()
				? "1.21.11" : client.getLaunchedVersion();

		ensureAlphaResourceStack(client, true);
		if (javaIconApplied) applyJavaIcon(client);
		applyVersionTitle(client, corruptionEverPlayed
				? AlphaLoadTimeline.finalVersionStage() : 0);
		TheFourthFrequency.LOGGER.info("Started {} Alpha resource session with order {}; first-entry corruption={}",
				sessionKind.name().toLowerCase(java.util.Locale.ROOT), activePackOrder,
				!corruptionEverPlayed);
	}

	private static void end(Minecraft client) {
		// Also covers the multiplayer paths the loading screen never gets to finish: a kick, a
		// timeout or a server shutdown replaces the screen outright, so its own onClose is not a
		// hook the corruption beds can rely on to be silenced.
		AlphaCorruptionAudio.fadeOutAll();
		active = false;
		corruptionInProgress = false;
		resourceReloadFinished = true;
		resourceReloadInProgress = false;
		++reloadGeneration;
		currentViewportFlooded = false;
		suppressNextResourceReloadAnimation = false;
		if (presentationRetired) return;
		// The Alpha bases intentionally stay selected on the main menu and between later world entries.
		if (javaIconApplied && client.getWindow() != null) applyJavaIcon(client);
		if (corruptionEverPlayed && client.getWindow() != null) {
			applyPersistentFinalTitle(client);
		}
	}

	public static boolean claimInitialCorruptionScreen() {
		if (presentationRetired || !active || corruptionEverPlayed || corruptionInProgress) return false;
		corruptionEverPlayed = true;
		corruptionInProgress = true;
		corruptionPlayCount++;
		currentViewportFlooded = false;
		ConfigManager.updateClientState(ModConfig.ClientState::completeAlphaDowngrade);
		return true;
	}

	public static boolean shouldCorruptLoadingScreen() {
		return active && corruptionInProgress;
	}

	public static boolean shouldPrepareInitialCorruptionScreen() {
		return !presentationRetired && !corruptionEverPlayed;
	}

	public static boolean shouldRenderLegacyLoadingScreen() {
		return shouldUsePersistentAlphaLoadingStyle();
	}

	public static boolean shouldUsePersistentAlphaLoadingStyle() {
		return !presentationRetired && AlphaLoadingPresentationPolicy.usePersistentLegacyPresentation(
				corruptionEverPlayed, corruptionInProgress);
	}

	public static boolean canCloseLoadingScreen(int screenTicks) {
		if (shouldCorruptLoadingScreen()) {
			return AlphaLoadTimeline.mayCloseLoadingScreen(screenTicks, resourceReloadFinished,
					currentViewportFlooded);
		}
		return !shouldRenderLegacyLoadingScreen() || resourceReloadFinished;
	}

	public static void loadingScreenTick(int screenTicks) {
		if (!shouldCorruptLoadingScreen()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.getWindow() != null) {
			if (AlphaLoadTimeline.deadAirWindowTitle(screenTicks)) {
				applyDeadAirTitle(client);
			} else {
				applyVersionTitle(client, AlphaLoadTimeline.versionStage(screenTicks));
			}
			if (screenTicks >= AlphaLoadTimeline.GLITCH_START_TICK && !javaIconApplied
					&& applyJavaIcon(client)) {
				javaIconApplied = true;
				javaIconAppliedAtScreenTick = screenTicks;
			}
		}
	}

	public static void recordViewportFlooded(boolean flooded) {
		if (shouldCorruptLoadingScreen() && flooded) currentViewportFlooded = true;
	}

	public static void loadingScreenClosed(int screenTicks, boolean viewportFlooded) {
		if (!shouldCorruptLoadingScreen()) return;
		lastLoadingScreenTicks = screenTicks;
		lastFailureCopies = AlphaLoadTimeline.copiedFailureLines(screenTicks);
		lastViewportFlooded = currentViewportFlooded || viewportFlooded;
		corruptionInProgress = false;
		applyVersionTitle(Minecraft.getInstance(), AlphaLoadTimeline.finalVersionStage());
	}

	/**
	 * The title this client should be wearing, whatever was asked for.
	 *
	 * <p>Read from {@code WindowTitleMixin} on every {@code Window#setTitle}, so it covers the routes
	 * that do not go through {@code updateTitle} and, more to the point, the ones that go through it
	 * at a moment {@link #retainFinalWindowTitle} declines to act on.
	 *
	 * <p>Requests pass through untouched while the downgrade sequence is running: those are its own
	 * version stamps walking 1.21.11 back to 1.0.0, and overriding them would erase the performance.
	 *
	 * @param requested what the caller wanted
	 * @return the title to actually use
	 */
	public static String overrideWindowTitle(String requested) {
		if (presentationRetired || !corruptionEverPlayed || corruptionInProgress) return requested;
		return MENU_WINDOW_TITLE;
	}

	public static void retainFinalWindowTitle(Minecraft client) {
		if (!presentationRetired && corruptionEverPlayed && !corruptionInProgress && client.getWindow() != null) {
			applyPersistentFinalTitle(client);
		}
	}

	/** Only the vanilla version stamp may be rewritten; any other title-screen string passes through. */
	public static String menuVersionText(String vanillaText) {
		if (!corruptionEverPlayed || presentationRetired) return vanillaText;
		return vanillaText != null && vanillaText.startsWith(VANILLA_VERSION_PREFIX)
				? MENU_VERSION_TEXT : vanillaText;
	}

	public static void recordLegacyLoadingScreenRendered() {
		legacyLoadingScreensRendered++;
	}

	public static void requestRegressionScreenshot(String fileName) {
		if ("alpha-loading-corruption.png".equals(fileName)) {
			if (corruptionScreenshotRequested) return;
			corruptionScreenshotRequested = true;
		} else if ("legacy-loading-normal.png".equals(fileName)) {
			if (legacyScreenshotRequested) return;
			legacyScreenshotRequested = true;
		}
		pendingRegressionScreenshot = fileName;
	}

	private static void clientTick(Minecraft client) {
		if (presentationRetired) return;
		primePersistentIdentity(client);
		applyPersistentStartupIfReady(client);
		capturePendingScreenshot(client);
	}

	public static void preparePersistentPackSelectionBeforeInitialReload(Minecraft client) {
		if (presentationRetired || !corruptionEverPlayed
				|| persistentInitialPackSelectionPrepared || client.options == null) return;
		try {
			selectAlphaResourceStack(client);
			client.options.resourcePacks.removeIf(AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH::contains);
			client.options.resourcePacks.addAll(AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH);
			persistentInitialPackSelectionPrepared = true;
			TheFourthFrequency.LOGGER.info(
					"Prepared persistent Alpha resource order before Minecraft's initial resource reload: {}",
					activePackOrder);
		} catch (RuntimeException exception) {
			TheFourthFrequency.LOGGER.error(
					"Could not prepare the persistent Alpha packs before initial reload; title-screen recovery will retry",
					exception);
		}
	}

	private static void primePersistentIdentity(Minecraft client) {
		if (presentationRetired || !corruptionEverPlayed
				|| active || persistentIdentityPrimed || client.getWindow() == null) return;
		if (applyJavaIcon(client)) javaIconApplied = true;
		applyPersistentFinalTitle(client);
		persistentIdentityPrimed = true;
	}

	private static void applyPersistentStartupIfReady(Minecraft client) {
		if (presentationRetired || !persistentStartupPending || active || client.getWindow() == null
				|| !(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
		persistentStartupPending = false;
		launchedVersion = client.getLaunchedVersion() == null || client.getLaunchedVersion().isBlank()
				? "1.21.11" : client.getLaunchedVersion();
		ensureAlphaResourceStack(client, false);
		primePersistentIdentity(client);
		applyPersistentFinalTitle(client);
		persistentStartupApplied = true;
		TheFourthFrequency.LOGGER.info(
				"Restored persistent Alpha 1.0.0 client identity with resource order {}", activePackOrder);
	}

	private static void capturePendingScreenshot(Minecraft client) {
		if (pendingRegressionScreenshot != null) {
			String fileName = pendingRegressionScreenshot;
			pendingRegressionScreenshot = null;
			Screenshot.grab(client.gameDirectory, fileName, client.getMainRenderTarget(), 1,
					message -> TheFourthFrequency.LOGGER.info("Captured loading regression frame {}: {}",
							fileName, message.getString()));
		}
	}

	private static void ensureAlphaResourceStack(Minecraft client, boolean recordSessionRequest) {
		if (presentationRetired) return;
		boolean selectionChanged = selectAlphaResourceStack(client);
		if (recordSessionRequest) resourceReloadRequestedThisSession = selectionChanged;
		if (!selectionChanged) {
			if (!resourceReloadInProgress) resourceReloadFinished = true;
			return;
		}

		resourceReloadFinished = false;
		resourceReloadInProgress = true;
		resourceReloadFailed = false;
		int generation = ++reloadGeneration;
		boolean restorePersistentIdentity = !recordSessionRequest && corruptionEverPlayed;
		armResourceReloadAnimationSuppression();
		client.reloadResourcePacks().whenComplete((ignored, failure) -> client.execute(() -> {
			if (generation != reloadGeneration) return;
			suppressNextResourceReloadAnimation = false;
			resourceReloadInProgress = false;
			resourceReloadFinished = true;
			resourceReloadFailed = failure != null;
			if (failure != null) {
				TheFourthFrequency.LOGGER.error(
						"Alpha base resource reload failed; world loading may continue with recovered resources",
						failure);
			}
			if (restorePersistentIdentity && client.getWindow() != null) {
				if (applyJavaIcon(client)) javaIconApplied = true;
				applyPersistentFinalTitle(client);
			}
		}));
	}

	private static boolean selectAlphaResourceStack(Minecraft client) {
		if (presentationRetired) return false;
		PackRepository repository = client.getResourcePackRepository();
		repository.reload();
		List<String> selectedBefore = repository.getSelectedPacks().stream().map(Pack::getId).toList();
		if (containsOrderedAlphaBases(selectedBefore)) {
			WorldInterfaceResourcePackLease.adoptExistingAutomaticSelection(selectedBefore);
			activePackOrder = List.copyOf(selectedBefore);
			return false;
		}
		activePackOrder = AlphaResourcePackPlan.selectionForSession(selectedBefore,
				repository.getAvailableIds());
		WorldInterfaceResourcePackLease.captureAutomaticSelection(selectedBefore, activePackOrder);
		for (String packId : AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH) {
			if (!repository.isAvailable(packId)) {
				TheFourthFrequency.LOGGER.error("Required Alpha base resource pack is unavailable: {}", packId);
			}
		}
		repository.setSelected(activePackOrder);
		return !selectedBefore.equals(activePackOrder);
	}

	private static boolean containsOrderedAlphaBases(List<String> packIds) {
		int programmer = packIds.indexOf(AlphaResourcePackPlan.PROGRAMMER_ART_PACK_ID);
		int base = packIds.indexOf(AlphaResourcePackPlan.GOLDEN_DAYS_BASE_PACK_ID);
		int alpha = packIds.indexOf(AlphaResourcePackPlan.GOLDEN_DAYS_ALPHA_PACK_ID);
		return programmer >= 0 && programmer < base && base < alpha;
	}

	private static boolean applyJavaIcon(Minecraft client) {
		if (client.getWindow() == null) return false;
		try (InputStream input = AlphaLoadSessionController.class.getResourceAsStream(JAVA_ICON_RESOURCE)) {
			if (input == null) throw new IllegalStateException("Missing " + JAVA_ICON_RESOURCE);
			BufferedImage image = ImageIO.read(input);
			if (image == null) throw new IllegalStateException("Invalid " + JAVA_ICON_RESOURCE);
			ByteBuffer pixels = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4);
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int argb = image.getRGB(x, y);
					pixels.put((byte) (argb >> 16));
					pixels.put((byte) (argb >> 8));
					pixels.put((byte) argb);
					pixels.put((byte) (argb >> 24));
				}
			}
			pixels.flip();
			try (GLFWImage icon = GLFWImage.malloc(); GLFWImage.Buffer icons = GLFWImage.malloc(1)) {
				icon.set(image.getWidth(), image.getHeight(), pixels);
				icons.put(0, icon);
				GLFW.glfwSetWindowIcon(client.getWindow().handle(), icons);
			}
			return true;
		} catch (Exception exception) {
			TheFourthFrequency.LOGGER.error("Could not apply the Alpha-era Java window icon", exception);
			return false;
		}
	}

	private static void armResourceReloadAnimationSuppression() {
		suppressNextResourceReloadAnimation = true;
	}

	public static boolean consumeResourceReloadAnimationSuppression() {
		if (!suppressNextResourceReloadAnimation) return false;
		suppressNextResourceReloadAnimation = false;
		suppressedResourceReloadAnimations++;
		return true;
	}

	public static boolean activeForTesting() {
		return active;
	}

	public static boolean resourceReloadFinishedForTesting() {
		return resourceReloadFinished;
	}

	public static boolean resourceReloadFailedForTesting() {
		return resourceReloadFailed;
	}

	public static boolean resourceReloadRequestedForTesting() {
		return resourceReloadRequestedThisSession;
	}

	public static boolean corruptionEverPlayedForTesting() {
		return corruptionEverPlayed;
	}

	public static boolean persistentStartupAppliedForTesting() {
		return persistentStartupApplied;
	}

	public static boolean persistentInitialPackSelectionPreparedForTesting() {
		return persistentInitialPackSelectionPrepared;
	}

	public static int corruptionPlayCountForTesting() {
		return corruptionPlayCount;
	}

	public static int versionStageForTesting() {
		return appliedVersionStage;
	}

	public static String appliedWindowTitleForTesting() {
		return appliedWindowTitle;
	}

	public static boolean javaIconAppliedForTesting() {
		return javaIconApplied;
	}

	public static int javaIconAppliedAtScreenTickForTesting() {
		return javaIconAppliedAtScreenTick;
	}

	public static int legacyLoadingScreensRenderedForTesting() {
		return legacyLoadingScreensRendered;
	}

	public static int lastLoadingScreenTicksForTesting() {
		return lastLoadingScreenTicks;
	}

	public static int lastFailureCopiesForTesting() {
		return lastFailureCopies;
	}

	public static boolean lastViewportFloodedForTesting() {
		return lastViewportFlooded;
	}

	public static int suppressedResourceReloadAnimationsForTesting() {
		return suppressedResourceReloadAnimations;
	}

	public static void recordPersistentAlphaLoadingOverlayCreated() {
		persistentAlphaLoadingOverlays++;
	}

	public static void recordPersistentAlphaLoadingFirstFrame() {
		persistentAlphaLoadingFirstFrames++;
	}

	public static int persistentAlphaLoadingOverlaysForTesting() {
		return persistentAlphaLoadingOverlays;
	}

	public static int persistentAlphaLoadingFirstFramesForTesting() {
		return persistentAlphaLoadingFirstFrames;
	}

	public static List<String> activePackOrderForTesting() {
		return activePackOrder;
	}

	public static String sessionKindForTesting() {
		return sessionKind.name();
	}

	/** Permanently ends the optional Alpha presentation without changing core mod resources. */
	public static void retirePresentation() {
		presentationRetired = true;
		active = false;
		persistentStartupPending = false;
		persistentIdentityPrimed = false;
		resourceReloadInProgress = false;
		resourceReloadFinished = true;
		++reloadGeneration;
		WorldInterfaceResourcePackLease.markPresentationRetired();
	}

	public static void resetForReplay() {
		AlphaCorruptionAudio.stopAll();
		active = false;
		corruptionEverPlayed = false;
		corruptionInProgress = false;
		persistentStartupPending = false;
		persistentStartupApplied = false;
		persistentIdentityPrimed = false;
		presentationRetired = false;
		resourceReloadInProgress = false;
		resourceReloadFinished = true;
	}

	public static boolean presentationRetiredForTesting() {
		return presentationRetired;
	}

	private static void applyVersionTitle(Minecraft client, int stage) {
		applyVersionTitle(client, stage, false);
	}

	private static void applyVersionTitle(Minecraft client, int stage, boolean force) {
		int resolved = Math.clamp(stage, 0, AlphaLoadTimeline.finalVersionStage());
		appliedVersionStage = resolved;
		setWindowTitle(client, titleForStage(resolved), force);
	}

	/**
	 * Dead air on the one channel that survives it.
	 *
	 * <p>The picture is gone for these ticks, but the title bar the operating system draws is
	 * not, and it is the only thing still reporting. It stops reporting a version, because at
	 * this point in the sequence there is nothing left running that could name one.</p>
	 */
	private static void applyDeadAirTitle(Minecraft client) {
		setWindowTitle(client, Component.translatable(
				"window.thefourthfrequency.alpha_load.dead_air").getString(), false);
	}

	private static void setWindowTitle(Minecraft client, String title, boolean force) {
		// The corruption sequence asks for a title every tick but it only changes a handful of
		// times. Without the guard that is twenty GLFW calls a second to set a string the window
		// already has. Callers recovering a title Minecraft may have overwritten pass force.
		if (!force && title.equals(appliedWindowTitle)) return;
		appliedWindowTitle = title;
		client.getWindow().setTitle(appliedWindowTitle);
	}

	/**
	 * The final step drops the world-context suffix on purpose.
	 *
	 * <p>Every earlier step is still a Minecraft that knows which world it is showing. The last
	 * one is the title this client keeps from then on, on the menu and in every later session,
	 * so it has to be the identical string the menu uses - a session that ended at
	 * "Minecraft 1.0.0 - Singleplayer World" and then sat at "Minecraft 1.0.0" would read as
	 * two different states rather than one permanent one.</p>
	 */
	private static String titleForStage(int stage) {
		if (stage >= AlphaLoadTimeline.finalVersionStage()) return MENU_WINDOW_TITLE;
		String version = AlphaLoadTimeline.versionAt(stage, launchedVersion);
		String contextKey = AlphaLoadTimeline.windowContextKey(sessionKind == SessionKind.SINGLEPLAYER);
		return Component.translatable(contextKey, VANILLA_VERSION_PREFIX + version).getString();
	}

	private static void applyPersistentFinalTitle(Minecraft client) {
		applyVersionTitle(client, AlphaLoadTimeline.finalVersionStage(), true);
	}

	private enum SessionKind {
		SINGLEPLAYER,
		MULTIPLAYER
	}
}
