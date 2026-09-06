package com.xm.thefourthfrequency.test;

import com.google.gson.JsonParser;
import com.xm.thefourthfrequency.pursuit.PursuitDimensions;
import com.xm.thefourthfrequency.pursuit.PursuitSlotManager;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

public final class PursuitDimensionGameTests implements CustomTestMethodInvoker {
	@GameTest
	public void sixMirrorDimensionDefinitionsArePackagedAndConcurrencyIsBounded(GameTestHelper helper) {
		for (var dimension : PursuitDimensions.mirrors()) {
			String resourcePath = "/data/" + dimension.identifier().getNamespace()
					+ "/dimension/" + dimension.identifier().getPath() + ".json";
			try (var stream = PursuitDimensionGameTests.class.getResourceAsStream(resourcePath)) {
				if (stream == null) {
					throw new AssertionError("Missing pursuit mirror dimension definition " + resourcePath);
				}
				var json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
						.getAsJsonObject();
				if (!json.has("type") || !json.has("generator")) {
					throw new AssertionError("Incomplete pursuit mirror dimension definition " + resourcePath);
				}
			} catch (IOException exception) {
				throw new AssertionError("Unable to read pursuit mirror dimension definition " + resourcePath,
						exception);
			}
		}
		// Six mirrors, one chase. The dimensions stay registered past the cap drop because recovery
		// has to be able to find a player left in any of them by an older save; the cap is what says
		// only one person can be taken out of shared reality at a time.
		if (PursuitDimensions.mirrors().stream().distinct().count() != 6
				|| PursuitSlotManager.MAX_ACTIVE_PURSUITS != 1) {
			throw new AssertionError("Pursuit mirror/cap topology changed");
		}
		helper.succeed();
	}

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		helper.setBlock(0, 0, 0, Blocks.AIR);
		try {
			method.invoke(this, helper);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof AssertionError error) throw error;
			if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
			throw exception;
		}
	}
}
