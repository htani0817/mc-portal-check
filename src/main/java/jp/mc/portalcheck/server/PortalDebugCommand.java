package jp.mc.portalcheck.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import jp.mc.portalcheck.McPortalCheck;
import jp.mc.portalcheck.logic.Dim;
import jp.mc.portalcheck.logic.Portal;
import jp.mc.portalcheck.logic.PortalCluster;
import jp.mc.portalcheck.logic.PortalLinkCalc;
import jp.mc.portalcheck.logic.Pos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * 確認用のコマンド {@code /portalcheck debug}（OP のみ）。いま立っている場所の判定について、
 * <ol>
 * <li>Mod の一覧（覚えているもの）で判定した結果</li>
 * <li>一覧を作り直して判定した結果（1 と違えば、覚えていた一覧が古い）</li>
 * <li>戻りの探索範囲にある POI（ゲートの位置の記録）を直接読んだもの</li>
 * <li>サーバー自身のゲート探索（PortalForcer）が選ぶゲート（くぐったときに実際に使われるもの）</li>
 * </ol>
 * をチャットとサーバーのログに出す。4 はチャンクを読み込むことがある（くぐったときと同じ動き）。
 */
public final class PortalDebugCommand {
    private PortalDebugCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("portalcheck")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("debug").executes(c -> run(c.getSource()))));
    }

    private static int run(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.level();
        ServerLevel other = PortalCheckServer.otherSide(level);
        if (other == null) {
            say(source, "オーバーワールドかネザーで実行してください");
            return 0;
        }
        Dim a = PortalCheckServer.dimOf(level);
        Dim b = PortalCheckServer.dimOf(other);
        BlockPos feet = player.blockPosition();
        Pos feetPos = new Pos(feet.getX(), feet.getY(), feet.getZ());

        // 1. Mod の一覧（覚えているもの）
        PoiPortalSource cachedA = PortalCheckServer.source(level);
        PoiPortalSource cachedB = PortalCheckServer.source(other);
        Portal self = PortalLinkCalc.findNear(cachedA, feetPos, 2);
        double[] entry = self != null ? self.entry() : new double[]{player.getX(), player.getY(), player.getZ()};
        if (self == null) {
            self = Portal.virtualAt(feetPos);
        }
        say(source, "== portalcheck debug ==");
        say(source, "調べたゲート: " + self.anchor() + (self.virtual() ? "（これから建てる）" : "（既存）"));
        PortalLinkCalc.Result cached = PortalLinkCalc.analyze(a, cachedA, b, cachedB, self, entry);
        say(source, "[1 Mod の一覧] " + describe(cached));

        // 2. 一覧を作り直して判定
        PoiPortalSource freshA = new PoiPortalSource(level);
        PoiPortalSource freshB = new PoiPortalSource(other);
        PortalLinkCalc.Result fresh = PortalLinkCalc.analyze(a, freshA, b, freshB, self, entry);
        say(source, "[2 作り直し] " + describe(fresh));

        // 3. 戻りの探索範囲の POI を直接読む（読み込み済みのチャンクかどうか、ブロックが本当にゲートかも出す）
        Pos back = fresh.back();
        BlockPos backPos = new BlockPos(back.x(), back.y(), back.z());
        List<BlockPos> raw = level.getPoiManager()
                .getInSquare(t -> t.is(PoiTypes.NETHER_PORTAL), backPos, a.arrivalRadius(), PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .toList();
        List<Portal> rawPortals = PortalCluster.cluster(raw.stream().map(p -> new Pos(p.getX(), p.getY(), p.getZ())).toList());
        say(source, "[3 POI] 戻りの探索中心 " + back + " ±" + a.arrivalRadius() + " に POI " + raw.size() + " 個 / " + rawPortals.size() + " 基");
        for (Portal p : rawPortals) {
            BlockPos bp = new BlockPos(p.anchor().x(), p.anchor().y(), p.anchor().z());
            boolean loaded = level.isLoaded(bp);
            String state = loaded ? (level.getBlockState(bp).is(Blocks.NETHER_PORTAL) ? "ゲート" : level.getBlockState(bp).toString()) : "未読み込み";
            boolean inCache = cachedA.blocksInSquare(p.anchor(), 0).contains(p.anchor());
            say(source, "  " + p.anchor() + " ブロック" + p.blocks().size() + "個 / 読み込み=" + loaded + " / 中身=" + state
                    + " / 境界内=" + level.getWorldBorder().isWithinBounds(bp) + " / Modの一覧に=" + inCache);
        }

        // 4. サーバー自身のゲート探索
        say(source, "[4 サーバー] 行き先: " + serverPick(other, fresh.target(), other.dimension() == Level.NETHER)
                + " / 戻り: " + serverPick(level, back, level.dimension() == Level.NETHER));
        return 1;
    }

    private static String describe(PortalLinkCalc.Result r) {
        String dest = (r.dest().virtual() ? "新規 " : "既存 ") + r.dest().anchor();
        String ret = r.returnsToSelf() ? "このゲート" : r.returnTo() == null ? "新規" : "別 " + r.returnTo().anchor();
        String shared = r.shared().stream().map(p -> p.anchor().toString()).collect(Collectors.joining(" "));
        return "行き先 " + dest + " / 戻り " + ret + " / 共有 [" + shared + "]";
    }

    /** サーバーの PortalForcer が選ぶゲートブロック。Youer などで呼べなければその旨を返す */
    private static String serverPick(ServerLevel level, Pos center, boolean toNether) {
        try {
            Optional<BlockPos> pos = level.getPortalForcer()
                    .findClosestPortalPosition(new BlockPos(center.x(), center.y(), center.z()), toNether, level.getWorldBorder());
            return pos.map(p -> "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")").orElse("見つからない（新規）");
        } catch (Throwable t) {
            return "呼べない（" + t.getClass().getSimpleName() + "）";
        }
    }

    private static void say(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        McPortalCheck.LOGGER.info("[portalcheck debug] {}", text);
    }
}
