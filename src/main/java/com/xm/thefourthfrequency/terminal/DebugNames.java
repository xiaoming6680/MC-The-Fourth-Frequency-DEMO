package com.xm.thefourthfrequency.terminal;

public final class DebugNames {
	private DebugNames() { }

	public static String anomaly(String id) {
		return switch (id) {
			case "none" -> "无";
			case "phantom_echo" -> "近处挖掘声与墙面裂纹";
			case "light_dropout" -> "附近突然变暗";
			case "peripheral_residue" -> "双手遮住视线";
			case "window_pulse" -> "游戏窗口闪动";
			case "watcher_alignment" -> "动物同时转头";
			case "dark_watcher" -> "暗处出现发光眼睛";
			case "action_echo" -> "刚才的动作重复出现";
			case "viewpoint_separation" -> "视角离开身体";
			case "door_cascade" -> "门由远到近破碎";
			case "organ_misread" -> "物品显示成眼睛";
			case "experience_gap" -> "记忆空白";
			case "local_rule_collapse" -> "附近光照与材质停止解算";
			case "red_horizon" -> "红色视界";
			case "silent_world" -> "环境音全部消失";
			case "metric_drift" -> "天体位置与终端读数持续偏差";
			case "channel_override" -> "频道接管";
			case "desktop_presence" -> "桌面在场";
			case "unrendered_layer" -> "未渲染层";
			default -> "未知异象";
		};
	}

	public static String file(String id) {
		return switch (id) {
			case "maintenance_handoff" -> "终端备忘";
			case "surface_shelter_record" -> "避难所日记";
			case "field_observation_record" -> "观测点日记";
			case "underground_mine_record" -> "矿站日记";
			case "abandoned_warehouse_record" -> "仓库日记";
			case "encrypted_witness_file" -> "前任留下的日记";
			case "body_mapping_warning" -> "进入祭坛前";
			case "recovered_predecessor_record" -> "上一局的残篇";
			case "manual_sky_monitor" -> "手册·天空";
			case "manual_mineral_probe" -> "手册·探针";
			case "manual_structure_navigator" -> "手册·导航";
			case "manual_stronghold_estimate" -> "手册·要塞";
			default -> "未知文件";
		};
	}

	/**
	 * Survival milestone names, addressed by enum ordinal.
	 *
	 * <p>Ordinal rather than the persisted bit index, because the panel sends the ordinal: the debug
	 * service reads {@code SurvivalMilestone.values()[value]}. The two agree today and the enum
	 * comment explains why they may not forever, so this deliberately follows the wire.</p>
	 */
	public static String milestone(int ordinal) {
		return switch (ordinal) {
			case 0 -> "落脚点";
			case 1 -> "取得铁";
			case 2 -> "下界准备就绪";
			case 3 -> "进入下界";
			case 4 -> "从下界返回";
			case 5 -> "合成末影之眼";
			case 6 -> "投掷末影之眼";
			case 7 -> "找到要塞";
			case 8 -> "采伐原木";
			case 9 -> "收集烈焰棒";
			case 10 -> "进入末地";
			case 11 -> "击败末影龙";
			case 12 -> "进入下界要塞";
			default -> "未知节点";
		};
	}

	/**
	 * World-interface lifecycle names, addressed by {@code WorldInterfaceStage.wireId()}.
	 *
	 * <p>The wire id rather than the ordinal, because that is the number the encounter promises never
	 * to reorder and therefore the only one safe to switch on from outside the enum.</p>
	 */
	public static String worldInterfaceStage(int wireId) {
		return switch (wireId) {
			case 0 -> "未准备";
			case 1 -> "竞技场就绪";
			case 2 -> "等待终端";
			case 3 -> "召唤中";
			case 4 -> "第一阶段";
			case 5 -> "第二阶段";
			case 6 -> "第三阶段";
			case 7 -> "胜利结算";
			case 8 -> "失败结算";
			case 9 -> "传送门开启";
			case 10 -> "已结束";
			default -> "未知阶段";
		};
	}

	public static String bandStage(int stage) {
		return switch (stage) {
			case 0 -> "尚未显现";
			case 1 -> "异常信号已侵入";
			case 2 -> "终端已经绑定";
			case 3 -> "异常信号已经终止";
			default -> "未知状态";
		};
	}
}
