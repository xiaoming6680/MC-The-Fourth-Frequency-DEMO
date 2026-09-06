package com.xm.thefourthfrequency.client_render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world interface's geometry, as exported from its Blockbench model.
 *
 * <p>The model used to be several hundred cubes scattered by hash from a dozen procedural
 * generators in Java, with a Python script re-deriving every one of them to paint a texture for
 * it. The geometry now lives in {@code docs/art/world_interface/world_interface.bbmodel}, which is
 * something an artist can open; {@code tools/export_world_interface_model.py} writes it out as the
 * JSON this class bakes, in Java model space (Y down, the face toward -Z, one unit a sixteenth of
 * a block), with every bone's pose relative to its parent exactly as
 * {@link PartDefinition#addOrReplaceChild} wants it.
 *
 * <p>The bones the rig poses - {@code hover}, {@code storm_body}, the three head chains and the
 * ten limbs - keep the pivots and bind rotations of {@code WorldInterfaceRig}; the geometry
 * contract test compares them directly, because a bone drawn somewhere other than where the
 * server hangs its hit box is the one failure this whole arrangement exists to prevent.
 */
public final class WorldInterfaceGeometry {
	public static final String RESOURCE = "/assets/thefourthfrequency/models/entity/world_interface.json";

	/** One box under a bone: {@code addBox(origin, size)} at {@code texOffs(uv)}. */
	public record Box(String name, float[] origin, float[] size, float[] uv, boolean mirror, float inflate) {
	}

	/** One {@code ModelPart}: its pose relative to {@code parent}, and the boxes it draws. */
	public record Bone(String name, String parent, float[] pose, List<Box> boxes) {
	}

	private static volatile WorldInterfaceGeometry cached;
	private static final Map<String, WorldInterfaceGeometry> ENTITY_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<String, String> localNames = new HashMap<>();

	private final int textureWidth;
	private final int textureHeight;
	private final List<Bone> bones;
	private final Map<String, Bone> byName;
	private final Map<String, List<Bone>> children;

	private WorldInterfaceGeometry(int textureWidth, int textureHeight, List<Bone> bones) {
		this.textureWidth = textureWidth;
		this.textureHeight = textureHeight;
		this.bones = Collections.unmodifiableList(bones);
		byName = new HashMap<>(bones.size() * 2);
		children = new HashMap<>(bones.size() * 2);
		for (Bone bone : bones) {
			if (byName.put(bone.name(), bone) != null) {
				throw new IllegalStateException("duplicate bone in " + RESOURCE + ": " + bone.name());
			}
			if (bone.parent() != null && !byName.containsKey(bone.parent())) {
				throw new IllegalStateException("bone " + bone.name() + " precedes its parent " + bone.parent());
			}
			children.computeIfAbsent(bone.parent(), key -> new ArrayList<>()).add(bone);
		}
	}

	/** The bundled geometry, parsed once. */
	public static WorldInterfaceGeometry load() {
		WorldInterfaceGeometry loaded = cached;
		if (loaded == null) {
			try (InputStream stream = WorldInterfaceGeometry.class.getResourceAsStream(RESOURCE)) {
				if (stream == null) throw new IllegalStateException("missing " + RESOURCE);
				loaded = parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
			cached = loaded;
		}
		return loaded;
	}

	public static WorldInterfaceGeometry parse(Reader reader) {
		JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
		JsonArray texture = root.getAsJsonArray("texture");
		List<Bone> bones = new ArrayList<>();
		for (JsonElement element : root.getAsJsonArray("bones")) {
			JsonObject bone = element.getAsJsonObject();
			List<Box> boxes = new ArrayList<>();
			for (JsonElement cube : bone.getAsJsonArray("cubes")) {
				JsonObject box = cube.getAsJsonObject();
				boxes.add(new Box(box.get("name").getAsString(), floats(box.getAsJsonArray("origin")),
						floats(box.getAsJsonArray("size")), floats(box.getAsJsonArray("uv")),
						box.has("mirror") && box.get("mirror").getAsBoolean(),
						box.has("inflate") ? box.get("inflate").getAsFloat() : 0.0F));
			}
			JsonElement parent = bone.get("parent");
			bones.add(new Bone(bone.get("name").getAsString(),
					parent == null || parent.isJsonNull() ? null : parent.getAsString(),
					floats(bone.getAsJsonArray("pose")), Collections.unmodifiableList(boxes)));
		}
		WorldInterfaceGeometry geometry = new WorldInterfaceGeometry(texture.get(0).getAsInt(), texture.get(1).getAsInt(), bones);
		for (JsonElement element : root.getAsJsonArray("bones")) {
			JsonObject bone = element.getAsJsonObject();
			if (bone.has("localName")) geometry.localNames.put(bone.get("name").getAsString(), bone.get("localName").getAsString());
		}
		return geometry;
	}

	/** Additional Blockbench assets use qualified paths while retaining local animation names. */
	public static WorldInterfaceGeometry loadEntity(String name) {
		return ENTITY_CACHE.computeIfAbsent(name, key -> {
			String resource = "/assets/thefourthfrequency/models/entity/" + key + ".json";
			try (InputStream stream = WorldInterfaceGeometry.class.getResourceAsStream(resource)) {
				if (stream == null) throw new IllegalStateException("missing " + resource);
				return parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
			} catch (IOException exception) { throw new UncheckedIOException(exception); }
		});
	}

	private static float[] floats(JsonArray array) {
		float[] values = new float[array.size()];
		for (int index = 0; index < values.length; index++) values[index] = array.get(index).getAsFloat();
		return values;
	}

	public int textureWidth() {
		return textureWidth;
	}

	public int textureHeight() {
		return textureHeight;
	}

	/** Every bone, parents before children. */
	public List<Bone> bones() {
		return bones;
	}

	public Bone bone(String name) {
		return byName.get(name);
	}

	public boolean hasBone(String name) {
		return byName.containsKey(name);
	}

	public List<Bone> childrenOf(String name) {
		return children.getOrDefault(name, List.of());
	}

	/** Parts drawn under a bone, itself included. */
	public int partsUnder(String name) {
		int total = 1;
		for (Bone child : childrenOf(name)) total += partsUnder(child.name());
		return total;
	}

	public LayerDefinition layer() {
		MeshDefinition mesh = new MeshDefinition();
		Map<String, PartDefinition> parts = new HashMap<>(bones.size() * 2);
		for (Bone bone : bones) {
			PartDefinition parent = bone.parent() == null ? mesh.getRoot() : parts.get(bone.parent());
			CubeListBuilder builder = CubeListBuilder.create();
			for (Box box : bone.boxes()) {
				// The packer lays islands out on whole UV units; texOffs takes nothing finer.
				builder.mirror(box.mirror()).texOffs(Math.round(box.uv()[0]), Math.round(box.uv()[1]))
						.addBox(box.name(), box.origin()[0], box.origin()[1], box.origin()[2],
								box.size()[0], box.size()[1], box.size()[2],
								box.inflate() == 0.0F ? CubeDeformation.NONE : new CubeDeformation(box.inflate()));
			}
			float[] pose = bone.pose();
			parts.put(bone.name(), parent.addOrReplaceChild(localNames.getOrDefault(bone.name(), bone.name()), builder,
					PartPose.offsetAndRotation(pose[0], pose[1], pose[2], pose[3], pose[4], pose[5])));
		}
		return LayerDefinition.create(mesh, textureWidth, textureHeight);
	}
}
