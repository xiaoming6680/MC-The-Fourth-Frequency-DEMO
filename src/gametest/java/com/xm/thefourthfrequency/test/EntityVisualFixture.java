package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.client_ui.TerminalAutoOpenController;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import java.util.UUID;

/** A successful screenshot must contain the staged entity, not the first-boot terminal UI. */
final class EntityVisualFixture {
	private EntityVisualFixture() { }

	static void finishFirstBoot(ClientGameTestContext context) {
		TerminalHandheldClientGameTest.awaitIssuedTerminal(context);
		TerminalHandheldClientGameTest.completeWalkthrough(context);
		context.waitFor(client -> client.screen == null && client.getOverlay() == null
				&& !TerminalAutoOpenController.armed(), 200);
	}

	static void assertVisible(ClientGameTestContext context, UUID id) {
		context.runOnClient(client -> {
			if (client.screen != null || client.getOverlay() != null || TerminalAutoOpenController.armed()) {
				throw new AssertionError("Entity screenshot is obscured by an interface or pending terminal open");
			}
			if (client.level == null || client.player == null) throw new AssertionError("No client world");
			var target = java.util.stream.StreamSupport.stream(client.level.entitiesForRendering().spliterator(), false)
					.filter(entity -> entity.getUUID().equals(id)).findFirst()
					.orElseThrow(() -> new AssertionError("Staged entity has not reached the client: " + id));
			var camera = client.gameRenderer.getMainCamera();
			var toward = target.getBoundingBox().getCenter().subtract(camera.position());
			if (target.isRemoved() || target.isInvisible() || toward.lengthSqr() < 0.01
					|| toward.normalize().dot(client.player.getViewVector(1.0F)) < 0.85) {
				throw new AssertionError("Staged entity is absent, invisible, or outside the central camera view: " + id);
			}
		});
	}
}
