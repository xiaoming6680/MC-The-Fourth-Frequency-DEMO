package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.EndBossEncounterService;
import com.xm.thefourthfrequency.narrative.NarrativeFileCatalog;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.networking.DebugActionPayload;
import com.xm.thefourthfrequency.networking.DebugStatusPayload;
import com.xm.thefourthfrequency.pursuit.PursuitDirector;
import com.xm.thefourthfrequency.terminal.AmbientAnomalyService;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.AnomalyConditions;
import com.xm.thefourthfrequency.terminal.DebugNames;
import com.xm.thefourthfrequency.terminal.TerminalAnomalyLogService;
import com.xm.thefourthfrequency.terminal.AnomalyDefinition;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.ending.WorldInterfaceState;
import com.xm.thefourthfrequency.pursuit.PursuitSlotManager;
import com.xm.thefourthfrequency.unrendered.UnrenderedSessionService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


public final class DebugPanelService {
	/**
	 * How often the server pushes status to a debug-enabled player, with no screen open.
	 *
	 * <p>The HUD has to keep reading after the panel is closed, and a client that polls only while a
	 * screen is up cannot give it that. Half a second is chosen against what the HUD shows rather
	 * than against the tick rate: every value on it is a second-resolution countdown or a state flag,
	 * and the client counts the seconds down itself between pushes, so a faster cadence would buy
	 * nothing but packets.</p>
	 */
	public static final int PUSH_INTERVAL_TICKS = 10;
	private static boolean initialized;

	private DebugPanelService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(DebugPanelService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % PUSH_INTERVAL_TICKS != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			// Silent pushes only. A message is the answer to something the developer just pressed, and
			// repeating it twice a second would keep overwriting the footer line they are reading.
			if (enabled(player)) sendStatus(player, "");
		}
	}

	public static boolean setEnabled(ServerPlayer player, boolean enabled) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return false;
		data.updateTerminalRecord(player.getUUID(), tag -> tag.putBoolean(TerminalData.DEBUG_ENABLED, enabled));
		if (!enabled) ServerPlayNetworking.send(player, denied("调试面板已关闭"));
		return true;
	}

	public static void open(ServerPlayer player) {
		if (!enabled(player)) {
			ServerPlayNetworking.send(player, denied("请先使用 /tff debug true 开启个人调试面板"));
			return;
		}
		sendStatus(player, "状态已刷新");
	}

	public static void handle(ServerPlayer player, DebugActionPayload payload) {
		if (!enabled(player)) {
			ServerPlayNetworking.send(player, denied("调试权限已关闭"));
			return;
		}
		String message;
		try {
			message = apply(player, payload.action(), payload.target(), payload.value());
		} catch (IllegalArgumentException exception) {
			message = "操作被拒绝：" + exception.getMessage();
		}
		TerminalRuntimeService.synchronizeProjection(player);
		sendStatus(player, message);
	}

	private static String apply(ServerPlayer player, String action, String target, int value) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow(() -> new IllegalArgumentException("没有终端记录"));
		return switch (action) {
			case "refresh" -> "状态已刷新";
			case "prelude_ready" -> {
				data.updateTerminalRecord(player.getUUID(), tag -> {
					tag.putString(TerminalData.PROOF_ROUTE, "debug");
					tag.putBoolean(TerminalData.NIGHT_ENTERED, true);
					tag.putBoolean(TerminalData.NIGHT_WITNESSED, true);
					tag.putInt(TerminalData.PRELUDE_ANOMALY_MASK, 0b11);
					tag.putBoolean(TerminalData.WATCHER_WITNESSED, true);
					tag.putBoolean(TerminalData.BOUND, true);
					tag.putBoolean(TerminalData.SECOND_CACHE_UNLOCKED, true);
					tag.putInt(TerminalData.PLOT_STAGE, Math.max(2, tag.getIntOr(TerminalData.PLOT_STAGE, 1)));
					tag.putInt(TerminalData.BAND_STAGE, Math.max(1, tag.getIntOr(TerminalData.BAND_STAGE, 0)));
				});
				yield "前期准备已完成，异常信号层已经显现";
			}
			case "progress_next" -> {
				int next = Math.clamp(record.getIntOr(TerminalData.PLOT_STAGE, 1) + 1, 1, 5);
				data.updateTerminalRecord(player.getUUID(), tag -> tag.putInt(TerminalData.PLOT_STAGE, next));
				yield "主线阶段已推进到 " + next;
			}
			case "progress_reset" -> { resetProgress(player, data); yield "个人主线状态已重置"; }
			case "band" -> {
				int stage = Math.clamp(value, 0, 3);
				data.updateTerminalRecord(player.getUUID(), tag -> tag.putInt(TerminalData.BAND_STAGE, stage));
				yield "异常信号状态已设为“" + DebugNames.bandStage(stage) + "”";
			}
			case "milestone" -> {
				if (value < 0 || value >= SurvivalMilestone.values().length)
					throw new IllegalArgumentException("未知生存节点");
				SurvivalMilestone milestone = SurvivalMilestone.values()[value];
				data.updateTerminalRecord(player.getUUID(), tag -> tag.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
						tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0) | milestone.mask()));
				yield "已完成生存节点：" + DebugNames.milestone(milestone.ordinal());
			}
			case "anomaly" -> {
				if (!AnomalyCatalog.contains(target)) throw new IllegalArgumentException("未知异象类型");
				AmbientAnomalyService.TriggerResult result = AmbientAnomalyService.triggerDetailed(player, target, value > 0);
				if (!result.started()) throw new IllegalArgumentException(anomalyFailureReason(player, target, result.failure()));
				yield "已触发异象：" + DebugNames.anomaly(target) + (value > 0 ? "（最强）" : "");
			}
			case "anomaly_stop" -> { AmbientAnomalyService.stop(player); yield "已停止当前异象并还原临时影响"; }
			case "anomaly_resume" -> { AmbientAnomalyService.resume(player); yield "已恢复自动触发异象"; }
			case "watcher_spawn" -> {
				if (!WatcherService.debugSpawn(player)) throw new IllegalArgumentException("附近没有合适的生成位置");
				yield "暗处人影已在玩家视野外生成";
			}
			case "him_spawn" -> {
				// The service answers with one boolean for several different refusals, and guessing
				// which one it was would send the tester looking in the wrong place. Name them all.
				if (!HimService.debugSpawn(player))
					throw new IllegalArgumentException("附近没有合适的生成位置，或这名玩家已经有一个 HIM 还没消失，"
							+ "或玩家正在私人镜像里（镜像内不允许生成）");
				yield "HIM 已在玩家视野外生成：偏离视线 95°–180°、22–44 格；被看到后 4 Tick 消失，"
						+ "走到 4 格内或 30 秒无人看见也会消失";
			}
			case "pursuit_test" -> {
				PursuitDirector.DebugStartResult result = PursuitDirector.debugStart(player, value);
				if (result != PursuitDirector.DebugStartResult.STARTED) {
					throw new IllegalArgumentException(pursuitFailure(result));
				}
				yield "追逐测试已启动：第 " + value + " 形态；本次结算不会改动正式追逐进度";
			}
			case "boss_test" -> {
				EndBossEncounterService.DebugBossResult result =
						EndBossEncounterService.debugStartEncounter(player);
				yield switch (result) {
					case STARTED -> "BOSS 战测试已启动：已进入末地竞技场并完成终端献祭";
					case WAITING_FOR_OTHERS -> "竞技场已就绪，你的终端已投入；其余在线玩家仍需向祭坛投入终端";
					default -> throw new IllegalArgumentException(bossFailure(result));
				};
			}
			case "file_unlock" -> {
				NarrativeFileCatalog.require(target);
				long now = player.level().getGameTime();
				data.updateTerminalRecord(player.getUUID(), tag -> TerminalFileState.setUnlocked(tag, target, true, now, player.level().getDayTime()));
				yield "已解锁文件：" + DebugNames.file(target);
			}
			case "file_lock" -> {
				NarrativeFileCatalog.require(target);
				long now = player.level().getGameTime();
				data.updateTerminalRecord(player.getUUID(), tag -> TerminalFileState.setUnlocked(
						tag, target, false, now, player.level().getDayTime()));
				yield "已重新锁定文件：" + DebugNames.file(target);
			}
			case "files_lock" -> {
				data.updateTerminalRecord(player.getUUID(), tag -> {
					tag.put(TerminalData.FILE_STATES, new ListTag());
					tag.putInt(TerminalData.UNREAD_FILE_COUNT, 0);
				});
				yield "文件进度已重置，等待对应阶段或建筑触发";
			}
			case "decay" -> {
				int stage = Math.clamp(value, 0, 5);
				data.updateNarrativeState(tag -> tag.putInt("decay_stage_override", stage));
				yield "异象效果等级已设为 " + stage;
			}
			case "decay_auto" -> {
				data.updateNarrativeState(tag -> tag.remove("decay_stage_override"));
				yield "异象效果已恢复为自动控制";
			}
			default -> throw new IllegalArgumentException("未知调试动作");
		};
	}

	private static void resetProgress(ServerPlayer player, FrequencyWorldData data) {
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putBoolean(TerminalData.BOUND, false); tag.putInt(TerminalData.BAND_STAGE, 0); tag.putInt(TerminalData.PLOT_STAGE, 1);
			tag.putBoolean(TerminalData.SECOND_CACHE_UNLOCKED, false);
			tag.putBoolean(TerminalData.LOCAL_FILE_UNLOCKED, false);
			tag.putBoolean(TerminalData.CONTINUITY_LEARNED, false);
			tag.putInt(TerminalData.SURVIVAL_MILESTONE_MASK, 0); tag.putInt(TerminalData.BREACH_MASK, 0);
			tag.putLong(TerminalData.TOOLS_DISABLED_UNTIL, 0L);
			tag.putBoolean(TerminalData.TRUTH_READ, false); tag.putBoolean(TerminalData.PORTAL_ROOM_FOUND, false);
			tag.putLong(TerminalData.PORTAL_ROOM_POSITION, 0L); tag.putString(TerminalData.PORTAL_ROOM_DIMENSION, "");
			tag.putBoolean(TerminalData.NIGHT_ENTERED, false);
			tag.putBoolean(TerminalData.NIGHT_WITNESSED, false); tag.putInt(TerminalData.PRELUDE_ANOMALY_MASK, 0);
			 tag.putBoolean(TerminalData.WATCHER_WITNESSED, false); tag.putString(TerminalData.PROOF_ROUTE, "none");
			tag.putInt(TerminalData.ANOMALY_TIER, 0); tag.putInt(TerminalData.ANOMALY_STORY_CEILING, 0);
			tag.putLong(TerminalData.ANOMALY_TIER_ONLINE_TICKS, 0L); tag.putInt(TerminalData.ANOMALY_HEAT, 0);
			tag.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, 0L); tag.putBoolean(TerminalData.ANOMALIES_SUSPENDED, false);
			tag.put(TerminalData.FILE_STATES, new ListTag()); tag.putInt(TerminalData.UNREAD_FILE_COUNT, 0);
		});
	}

	private static String anomalyFailureReason(ServerPlayer player, String id,
			AmbientAnomalyService.TriggerFailure failure) {
		return switch (failure) {
			case ALREADY_ACTIVE -> {
				var active = com.xm.thefourthfrequency.terminal.AnomalyRuntimeService.active(player);
				yield active == null ? "另一个异象正在启动，请稍后再试"
						: "已有异象正在发生：" + DebugNames.anomaly(active.anomalyId());
			}
			case PRECONDITION_UNMET -> switch (id) {
				case "phantom_echo" -> "玩家前方或侧前方没有可作用的实体方块";
				case "light_dropout" -> AnomalyConditions.nightLike(player.level())
						? "玩家周围 16 格内没有可安全熄灭的普通方块光源"
						: "当前不是夜晚：光源失效只在夜间启用";
				case "action_echo" -> "进入世界尚未满 3 秒，无法取得动作历史";
				case "unrendered_layer" -> switch (com.xm.thefourthfrequency.unrendered
						.UnrenderedSessionService.unavailableReason(player)) {
					case ALREADY_IN_LAYER -> "玩家已经在未渲染层里";
					case IN_MIRROR -> "玩家正在私人镜像维度中，返回地址已被追逐占用";
					case IN_THE_END -> "末地不触发未渲染层：终局途中的六分钟绕路是损失而不是惊吓";
					case TRANSITION_IN_FLIGHT -> "上一次进入或捕获的传送尚未完成";
					case NO_TERMINAL_RECORD -> "玩家没有终端记录，无法写入返回地址";
					case PURSUIT_ACTIVE -> "玩家有正在进行的追逐会话";
					case SESSION_ACTIVE -> "玩家已有一个未渲染层会话未结束";
					case DIMENSION_MISSING -> "未渲染层维度未加载：检查数据包与生成器编解码器注册";
					case NO_FREE_SLOT -> "未渲染层槽位已满（上限 "
							+ com.xm.thefourthfrequency.unrendered.UnrenderedAnchorPolicy.MAX_CONCURRENT + "）";
					case COOLING_DOWN -> "未渲染层冷却中：它与追逐同级，两次之间间隔 20—30 分钟";
					case NO_PLAYER, NONE -> "未渲染层拒绝了启动请求";
				};
				default -> "当前玩家状态或环境不满足该异象的启动条件";
			};
			case EFFECT_UNAVAILABLE -> switch (id) {
				case "dark_watcher" -> "附近无法放置观察者，或已有观察者正在注视玩家";
				case "door_cascade" -> "玩家周围 20 格内不足 2 扇可作用的普通门";
				case "experience_gap" -> "附近找不到至少 12 格长的安全连续移动路径";
				default -> "该异象需要的临时实体或世界效果无法建立";
			};
			case RUNTIME_REJECTED -> "异象运行时拒绝了启动请求，请先停止当前异象后重试";
			case NONE -> "未知启动失败";
		};
	}

	private static String pursuitFailure(PursuitDirector.DebugStartResult result) {
		return switch (result) {
			case INVALID_FORM -> "追逐形态必须在 1–5 之间";
			case NO_TERMINAL_RECORD -> "没有可用的个人终端记录";
			case ALREADY_ACTIVE -> "玩家已经处于追逐或镜像世界中";
			case UNSUPPORTED_DIMENSION -> "测试追逐只能从主世界或下界开始";
			case UNSAFE -> "当前状态无法开始追逐：需要存活且非旁观，且不处于镜像世界、末地或空段中";
			case NO_SLOT -> "两个私人追逐槽位都已占用";
			case TRANSFER_REJECTED -> "镜像世界未能接受追逐会话";
			case STARTED -> "";
		};
	}

	private static String bossFailure(EndBossEncounterService.DebugBossResult result) {
		return switch (result) {
			case ALREADY_RUNNING -> "世界接口战斗已经开始或已经结束，请先重置该存档的终局状态";
			case END_MISSING -> "服务器没有末地维度";
			case ARENA_FAILED -> "末地竞技场未能建立或玩家未能进入";
			case DEPOSIT_REJECTED -> "祭坛拒绝了终端献祭，请确认玩家持有已绑定的终端";
			case STARTED, WAITING_FOR_OTHERS -> "";
		};
	}

	private static boolean enabled(ServerPlayer player) {
		return FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID())
				.map(tag -> tag.getBooleanOr(TerminalData.DEBUG_ENABLED, false)).orElse(false);
	}

	private static void sendStatus(ServerPlayer player, String message) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(new CompoundTag());
		var files = TerminalFileState.states(record);
		int discoveredFileMask = fileMask(files, FileStateKind.DISCOVERED);
		int unlockedFileMask = fileMask(files, FileStateKind.UNLOCKED);
		int readFileMask = fileMask(files, FileStateKind.READ);
		long now = player.level().getGameTime();
		ServerPlayNetworking.send(player, new DebugStatusPayload(DebugStatusPayload.CURRENT_PROTOCOL_VERSION, true,
				player.getGameProfile().name(), record.getIntOr(TerminalData.PLOT_STAGE, 1),
				record.getIntOr(TerminalData.BAND_STAGE, 0), record.getBooleanOr(TerminalData.BOUND, false),
				files.size(), (int) files.stream().filter(TerminalFileState.State::unlocked).count(),
				discoveredFileMask, unlockedFileMask, readFileMask, TerminalFileState.unreadCount(record),
				WorldDecayService.stage(data, record),
				!data.narrativeState().contains("decay_stage_override"),
				record.getIntOr(TerminalData.ANOMALY_TIER, 0), record.getIntOr(TerminalData.ANOMALY_STORY_CEILING, 0),
				record.getIntOr(TerminalData.ANOMALY_HEAT, 0), record.getStringOr(TerminalData.ACTIVE_ANOMALY_ID, "none"),
				seconds(record.getLongOr(TerminalData.ACTIVE_ANOMALY_UNTIL, 0L) - now),
				// Frozen dimensions report what is left rather than a deadline that has quietly gone
				// negative behind the player while they were somewhere the director does not run.
				seconds(record.getLongOr(TerminalData.ANOMALY_FROZEN_REMAINING, 0L) > 0L
						? record.getLongOr(TerminalData.ANOMALY_FROZEN_REMAINING, 0L)
						: record.getLongOr(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, 0L) - now),
				seconds(record.getLongOr(TerminalData.NEXT_STRONG_ANOMALY_TICK, 0L) - now),
				seconds(record.getLongOr(TerminalData.NEXT_COMPOSITE_ANOMALY_TICK, 0L) - now),
				record.getBooleanOr(TerminalData.ANOMALIES_SUSPENDED, false),
				candidateMask(player, record, now), unseenMask(record),
				AmbientAnomalyService.selectionTier(record),
				record.getBooleanOr(TerminalData.SIGNATURE_ANOMALY_PENDING, false),
				record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false),
				record.getIntOr(TerminalData.PURSUIT_SESSION_FORM, 0),
				PursuitSlotManager.activeCount(),
				UnrenderedSessionService.inLayer(player), UnrenderedSessionService.activeCount(),
				WorldInterfaceState.snapshot(player.level().getServer()).stage().wireId(),
				record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0),
				message));
	}

	/**
	 * Which anomalies the next draw would be choosing between, as catalogue-order bits.
	 *
	 * <p>Asked through {@link AmbientAnomalyService#candidatePool} rather than rebuilt here, because a
	 * second copy of the selection rules would eventually answer something the director does not do.
	 * The weighted pool is reduced to distinct ids: weights decide odds, and the panel is reporting
	 * membership.</p>
	 */
	private static long candidateMask(ServerPlayer player, CompoundTag record, long now) {
		long mask = 0L;
		for (AnomalyDefinition definition : AmbientAnomalyService.candidatePool(player, record,
				AmbientAnomalyService.selectionTier(record), now)) {
			int index = AnomalyCatalog.definitions().indexOf(definition);
			if (index >= 0 && index < Long.SIZE) mask |= 1L << index;
		}
		return mask;
	}

	/** Catalogue-order bits for entries this player has never met, re-indexed off the save-file order. */
	private static long unseenMask(CompoundTag record) {
		long seen = record.getLongOr(TerminalData.ANOMALY_SEEN_MASK, 0L);
		var definitions = AnomalyCatalog.definitions();
		long mask = 0L;
		for (int index = 0; index < definitions.size() && index < Long.SIZE; index++) {
			int seenBit = AnomalyCatalog.indexOf(definitions.get(index).id());
			if (seenBit < 0 || (seen & 1L << seenBit) == 0L) mask |= 1L << index;
		}
		return mask;
	}

	private static DebugStatusPayload denied(String message) {
		return new DebugStatusPayload(DebugStatusPayload.CURRENT_PROTOCOL_VERSION, false, "", 0, 0, false,
				0, 0, 0, 0,
				0, 0, 0, true,
				0, 0, 0, "none", 0, 0, 0, 0, false,
				0L, 0L, 0, false, false, 0, 0, false, 0, 0, 0,
				message);
	}

	private static int fileMask(java.util.List<TerminalFileState.State> states, FileStateKind kind) {
		int mask = 0;
		var definitions = NarrativeFileCatalog.definitions();
		for (int index = 0; index < definitions.size(); index++) {
			String id = definitions.get(index).id();
			for (TerminalFileState.State state : states) {
				if (!state.id().equals(id) || !kind.matches(state)) continue;
				mask |= 1 << index;
				break;
			}
		}
		return mask;
	}

	private static int seconds(long ticks) { return (int) Math.clamp((ticks + 19L) / 20L, 0L, Integer.MAX_VALUE); }

	private enum FileStateKind {
		DISCOVERED, UNLOCKED, READ;
		private boolean matches(TerminalFileState.State state) {
			return switch (this) {
				case DISCOVERED -> true;
				case UNLOCKED -> state.unlocked();
				case READ -> state.read();
			};
		}
	}
}
