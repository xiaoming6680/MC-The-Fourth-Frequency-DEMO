package com.xm.thefourthfrequency.client_ui;

/** A brief private event spends its lifetime only while its world can be seen. */
public final class PrivateAnomalyPresentation {
	private String id = "none";
	private int variant;
	private int remaining;
	public void accept(String id, int variant) {
		this.id = id;
		this.variant = Math.floorMod(variant, 4);
		remaining = 100;
	}
	public void advance(boolean worldVisible) {
		if (worldVisible && remaining > 0 && --remaining == 0) id = "none";
	}
	public void clear() { id = "none"; variant = 0; remaining = 0; }
	public String id() { return id; }
	public int variant() { return variant; }
	public int remaining() { return remaining; }
}
