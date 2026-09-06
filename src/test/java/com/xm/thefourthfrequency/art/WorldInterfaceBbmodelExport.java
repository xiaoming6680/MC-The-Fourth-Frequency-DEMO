package com.xm.thefourthfrequency.art;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xm.thefourthfrequency.client_render.WorldInterfaceGeometry;
import com.xm.thefourthfrequency.client_render.WorldInterfaceModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Vector3fc;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the world interface's baked Java model out as a Blockbench {@code .bbmodel}.
 *
 * <p>This is the first half of the round trip that makes Blockbench the editing source for the
 * boss: the skeleton the server poses ({@code WorldInterfaceRig}) has to arrive in the editor with
 * exactly its bone names, pivots and bind rotations, or whatever is modelled there attaches to bones
 * the runtime does not know. Rather than restate the skeleton, this bakes the real
 * {@link WorldInterfaceModel#createLayer()} and walks it.
 *
 * <p><b>Coordinate convention.</b> Blockbench's own "Modded Entity" exporter (with {@code flip_y})
 * is the reference, inverted: a Java bone offset {@code (x, y, z)} relative to its parent becomes a
 * Blockbench pivot of {@code parentPivot + (-x, -y, z)}, with {@code +24} on Y for root-level bones;
 * a Java rotation {@code (rx, ry, rz)} becomes Blockbench degrees {@code (-rx, -ry, rz)}; and a Java
 * box {@code addBox(x, y, z, w, h, d)} becomes {@code from = pivot + (-(x + w), -(y + h), z)}. Every
 * number here comes from that exporter's source, so a model exported from Blockbench with the
 * built-in codec reproduces the Java model exactly.
 *
 * <p>Run with {@code gradlew exportWorldInterfaceBbmodel}. Not a test; it lives in the test source
 * set only because that is the one classpath that can see both the client model and Minecraft.
 */
public final class WorldInterfaceBbmodelExport {
	private static final Path OUT = Path.of("docs/art/world_interface/world_interface_runtime.bbmodel");
	private static final Path TEXTURE = Path.of(
			"src/main/resources/assets/thefourthfrequency/textures/entity/world_interface_form_1.png");
	private static final float ROOT_LIFT = 24.0F;

	private String modelName = "world_interface";
	private Path texturePath = TEXTURE;
	private int width, height;
	private final List<JsonObject> elements = new ArrayList<>();
	private int sequence;

	private WorldInterfaceBbmodelExport() {
	}

	public static void main(String[] args) throws Exception {
		Path out = args.length > 0 ? Path.of(args[0]) : OUT;
		WorldInterfaceBbmodelExport export = new WorldInterfaceBbmodelExport();
		String json = export.build(WorldInterfaceModel.createLayer());
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, json, StandardCharsets.UTF_8);
		System.out.println("wrote " + out + " (" + export.elements.size() + " cubes)");
	}

	static void exportLayer(String name, LayerDefinition layer, int width, int height, String texture) throws Exception {
		var export = new WorldInterfaceBbmodelExport();
		export.modelName = name;
		export.texturePath = Path.of("src/main/resources/assets/thefourthfrequency/textures/entity/" + texture + ".png");
		export.width = width; export.height = height;
		Path out = Path.of("docs/art/entities/" + name + ".bbmodel");
		Files.createDirectories(out.getParent());
		Files.writeString(out, export.build(layer), StandardCharsets.UTF_8);
		System.out.println(name + ": " + export.elements.size() + " cubes");
	}

	String build(LayerDefinition layer) throws Exception {
		MeshDefinition mesh = field(layer, "mesh");
		PartDefinition root = mesh.getRoot();
		JsonArray outliner = new JsonArray();
		for (Map.Entry<String, PartDefinition> child : children(root)) {
			outliner.add(group(child.getKey(), child.getValue(), null));
		}
		JsonObject model = new JsonObject();
		JsonObject meta = new JsonObject();
		meta.addProperty("format_version", "4.10");
		meta.addProperty("model_format", "modded_entity");
		meta.addProperty("box_uv", true);
		model.add("meta", meta);
		model.addProperty("name", modelName);
		model.addProperty("model_identifier", modelName);
		model.addProperty("modded_entity_version", "1.17");
		model.addProperty("modded_entity_flip_y", true);
		JsonObject resolution = new JsonObject();
		resolution.addProperty("width", width == 0 ? WorldInterfaceGeometry.load().textureWidth() : width);
		resolution.addProperty("height", height == 0 ? WorldInterfaceGeometry.load().textureHeight() : height);
		model.add("resolution", resolution);
		JsonArray elementArray = new JsonArray();
		for (JsonObject element : elements) elementArray.add(element);
		model.add("elements", elementArray);
		model.add("outliner", outliner);
		JsonArray textures = new JsonArray();
		if (Files.exists(texturePath)) textures.add(texture());
		model.add("textures", textures);
		Gson gson = new GsonBuilder().disableHtmlEscaping().create();
		return gson.toJson(model);
	}

	private JsonObject group(String name, PartDefinition part, float[] parentPivot) throws Exception {
		PartPose pose = field(part, "partPose");
		float[] pivot = parentPivot == null
				? new float[]{-pose.x(), ROOT_LIFT - pose.y(), pose.z()}
				: new float[]{parentPivot[0] - pose.x(), parentPivot[1] - pose.y(), parentPivot[2] + pose.z()};
		JsonObject group = new JsonObject();
		group.addProperty("name", name);
		group.add("origin", array(pivot));
		group.add("rotation", array(new float[]{
				-(float) Math.toDegrees(pose.xRot()), -(float) Math.toDegrees(pose.yRot()),
				(float) Math.toDegrees(pose.zRot())}));
		group.addProperty("color", 0);
		group.addProperty("uuid", uuid(name));
		group.addProperty("export", true);
		group.addProperty("mirror_uv", false);
		group.addProperty("isOpen", false);
		group.addProperty("locked", false);
		group.addProperty("visibility", true);
		group.addProperty("autouv", 0);
		JsonArray children = new JsonArray();
		List<CubeDefinition> cubes = field(part, "cubes");
		int index = 0;
		for (CubeDefinition cube : cubes) {
			// The geometry loader passes each Blockbench cube's name through as the box comment.
			String comment = field(cube, "comment");
			String label = comment != null && !comment.isEmpty() ? comment
					: cubes.size() == 1 ? name : name + "#" + index;
			children.add(cube(label, cube, pivot));
			index++;
		}
		for (Map.Entry<String, PartDefinition> child : children(part)) {
			children.add(group(child.getKey(), child.getValue(), pivot));
		}
		group.add("children", children);
		return group;
	}

	private String cube(String name, CubeDefinition cube, float[] pivot) throws Exception {
		Vector3fc origin = field(cube, "origin");
		Vector3fc dimensions = field(cube, "dimensions");
		CubeDeformation grow = field(cube, "grow");
		boolean mirror = field(cube, "mirror");
		UVPair texCoord = field(cube, "texCoord");
		float inflate = field(grow, "growX");
		float w = dimensions.x();
		float h = dimensions.y();
		float d = dimensions.z();
		float[] from = {pivot[0] - (origin.x() + w), pivot[1] - (origin.y() + h), pivot[2] + origin.z()};
		float[] to = {from[0] + w, from[1] + h, from[2] + d};
		JsonObject element = new JsonObject();
		element.addProperty("name", name);
		element.addProperty("box_uv", true);
		element.addProperty("rescale", false);
		element.addProperty("locked", false);
		element.addProperty("render_order", "default");
		element.addProperty("allow_mirror_modeling", true);
		element.add("from", array(from));
		element.add("to", array(to));
		element.addProperty("autouv", 0);
		element.addProperty("color", 0);
		element.addProperty("inflate", inflate);
		element.add("origin", array(pivot));
		element.add("uv_offset", array(new float[]{texCoord.u(), texCoord.v()}));
		element.addProperty("mirror_uv", mirror);
		element.add("faces", boxFaces(texCoord.u(), texCoord.v(), w, h, d, mirror));
		element.addProperty("type", "cube");
		String id = uuid(name + ":" + elements.size());
		element.addProperty("uuid", id);
		elements.add(element);
		return id;
	}

	/** Blockbench's own box-UV unwrap, copied from {@code Cube.preview_controller.updateUV}. */
	private static JsonObject boxFaces(float u, float v, float w, float h, float d, boolean mirror) {
		String[] names = {"east", "west", "up", "down", "south", "north"};
		float[][] from = {{0, d}, {d + w, d}, {d + w, d}, {d + w * 2, 0}, {d * 2 + w, d}, {d, d}};
		float[][] size = {{d, h}, {d, h}, {-w, -d}, {-w, d}, {w, h}, {w, h}};
		if (mirror) {
			for (int face = 0; face < names.length; face++) {
				from[face][0] += size[face][0];
				size[face][0] = -size[face][0];
			}
			float[] swapFrom = from[0];
			float[] swapSize = size[0];
			from[0] = from[1];
			size[0] = size[1];
			from[1] = swapFrom;
			size[1] = swapSize;
		}
		JsonObject faces = new JsonObject();
		for (int face = 0; face < names.length; face++) {
			JsonObject entry = new JsonObject();
			entry.add("uv", array(new float[]{from[face][0] + u, from[face][1] + v,
					from[face][0] + size[face][0] + u, from[face][1] + size[face][1] + v}));
			entry.addProperty("texture", 0);
			faces.add(names[face], entry);
		}
		return faces;
	}

	private JsonObject texture() throws IOException {
		byte[] png = Files.readAllBytes(texturePath);
		JsonObject texture = new JsonObject();
		texture.addProperty("path", texturePath.toAbsolutePath().toString());
		texture.addProperty("name", texturePath.getFileName().toString());
		texture.addProperty("folder", "entity");
		texture.addProperty("namespace", "thefourthfrequency");
		texture.addProperty("id", "0");
		texture.addProperty("particle", false);
		texture.addProperty("render_mode", "default");
		texture.addProperty("render_sides", "auto");
		texture.addProperty("visible", true);
		texture.addProperty("mode", "bitmap");
		texture.addProperty("saved", true);
		texture.addProperty("uuid", UUID.nameUUIDFromBytes("texture:0".getBytes(StandardCharsets.UTF_8)).toString());
		texture.addProperty("relative_path", "../../../src/main/resources/assets/thefourthfrequency/textures/entity/"
				+ texturePath.getFileName());
		texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
		return texture;
	}

	/** Declaration order: the map behind {@code PartDefinition} is a {@code LinkedHashMap}. */
	private static List<Map.Entry<String, PartDefinition>> children(PartDefinition part) throws Exception {
		Map<String, PartDefinition> children = field(part, "children");
		return new ArrayList<>(children.entrySet());
	}

	private String uuid(String label) {
		return UUID.nameUUIDFromBytes(("world_interface:" + label + ":" + sequence++)
				.getBytes(StandardCharsets.UTF_8)).toString();
	}

	private static JsonArray array(float[] values) {
		JsonArray array = new JsonArray();
		for (float value : values) array.add(Math.round(value * 1000.0F) / 1000.0F);
		return array;
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object target, String name) throws Exception {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return (T) field.get(target);
			} catch (NoSuchFieldException ignored) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name + " on " + target.getClass());
	}
}
