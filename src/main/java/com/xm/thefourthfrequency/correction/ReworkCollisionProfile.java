package com.xm.thefourthfrequency.correction;

/** Physical core of each form; rendered back-arms deliberately stay outside the damage box. */
public record ReworkCollisionProfile(float width, float height, float eyeHeight) {
	public static ReworkCollisionProfile forStage(int stage) {
		return switch (Math.clamp(stage, ReworkFormStage.MIN_STAGE, ReworkFormStage.MAX_STAGE)) {
			case 1 -> new ReworkCollisionProfile(0.72F, 2.45F, 2.23F);
			case 2 -> new ReworkCollisionProfile(0.76F, 2.75F, 2.53F);
			default -> new ReworkCollisionProfile(0.80F, 3.15F, 2.93F);
		};
	}
}
