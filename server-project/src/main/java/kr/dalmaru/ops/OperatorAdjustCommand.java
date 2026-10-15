package kr.dalmaru.ops;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import kr.dalmaru.account.AccountSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class OperatorAdjustCommand {
	private OperatorAdjustCommand() { }

	public static LiteralArgumentBuilder<CommandSourceStack> command(Function<ServerPlayer, AccountSession> sessionOf, java.util.function.BiConsumer<net.minecraft.server.MinecraftServer, kr.dalmaru.account.CharacterProfile> refreshOnline) {
		return literal("adjust")
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
			.then(argument("character", StringArgumentType.word())
				.then(argument("kind", StringArgumentType.word())
					.then(argument("item", StringArgumentType.word())
						.then(argument("delta", IntegerArgumentType.integer(-1_000_000, 1_000_000))
							.then(argument("request", LongArgumentType.longArg(1))
								.then(argument("reason", StringArgumentType.greedyString()).executes(context -> {
									ServerPlayer player = context.getSource().getPlayerOrException();
									var server = context.getSource().getServer();
									UUID target;
									try { target = UUID.fromString(StringArgumentType.getString(context, "character")); }
									catch (IllegalArgumentException error) { player.sendSystemMessage(Component.literal("대상 캐릭터 UUID가 아닙니다.")); return 0; }
									String item = StringArgumentType.getString(context, "item");
									AccountSession session = sessionOf.apply(player);
									String kind = StringArgumentType.getString(context, "kind");
									int delta = IntegerArgumentType.getInteger(context, "delta");
									long request = LongArgumentType.getLong(context, "request");
									String reason = StringArgumentType.getString(context, "reason");
									CompletableFuture.runAsync(() -> {
										String message = session == null ? "먼저 달마루 계정에 접속하세요." : new OperatorLedger().submit(session.accountId(), target, kind, "-".equals(item) ? null : item, delta, request, reason);
										kr.dalmaru.account.CharacterProfile profile = null;
										if (message.startsWith("처리했습니다.") || message.startsWith("이미 처리된 요청입니다.")) try { profile = new kr.dalmaru.account.CharacterRepository().profile(target); } catch (Exception error) { profile = null; }
										var latest = profile;
										server.execute(() -> { player.sendSystemMessage(Component.literal(message)); if (latest != null) refreshOnline.accept(server, latest); });
									});
									return 1;
								})))))));
	}
}
