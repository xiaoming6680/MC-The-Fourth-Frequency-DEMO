package com.xm.thefourthfrequency.client_render;

import com.xm.thefourthfrequency.entity.HorrorMotion;
import net.minecraft.client.model.geom.ModelPart;
import java.util.ArrayList;
import java.util.List;

/** Binds the MCP-authored phalanges once; the parent hand still follows the full body animation. */
final class HorrorDigits {
	private final List<ModelPart> digits = new ArrayList<>();
	HorrorDigits(ModelPart root) {
		root.getAllParts().forEach(part -> {
			for (int i = 0; i < 4; i++) if (part.hasChild("digit_" + i)) digits.add(part.getChild("digit_" + i));
		});
	}
	void animate(float age, float movement) {
		for (int i = 0; i < digits.size(); i++) {
			ModelPart digit = digits.get(i);
			digit.xRot += HorrorMotion.digitCurl(age, i, movement);
			digit.getChild("distal").xRot += HorrorMotion.digitCurl(age - 3.5F, i, movement) * .72F;
		}
	}
}
