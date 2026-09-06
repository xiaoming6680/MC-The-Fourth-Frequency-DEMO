package com.xm.thefourthfrequency.art;

import com.xm.thefourthfrequency.client_render.*;

/** Exports the exact runtime anatomy for MCP editing, retaining every animation pivot. */
public final class EntityBbmodelExport {
	public static void main(String[] args) throws Exception {
		WorldInterfaceBbmodelExport.exportLayer("watcher", WatcherModel.createAuthoringLayer(), 128, 128, "watcher");
		WorldInterfaceBbmodelExport.exportLayer("bacteria", BacteriaModel.createAuthoringLayer(), 64, 64, "bacteria");
		WorldInterfaceBbmodelExport.exportLayer("him", HimModel.createAuthoringLayer(), 64, 64, "him");
		WorldInterfaceBbmodelExport.exportLayer("stability_anchor", StabilityAnchorModel.createAuthoringLayer(),
				StabilityAnchorUv.SHEET_WIDTH, StabilityAnchorUv.SHEET_HEIGHT, "stability_anchor");
		WorldInterfaceBbmodelExport.exportLayer("rework_body_stage_1", ReworkBodyModel.createAuthoringLayer(1), 128, 128, "rework_body_stage_1");
		WorldInterfaceBbmodelExport.exportLayer("rework_body_stage_2", ReworkBodyModel.createAuthoringLayer(2), 128, 128, "rework_body_stage_2");
		WorldInterfaceBbmodelExport.exportLayer("rework_body_stage_3", ReworkBodyModel.createAuthoringLayer(3), 128, 128, "rework_body_stage_3");
	}
}
