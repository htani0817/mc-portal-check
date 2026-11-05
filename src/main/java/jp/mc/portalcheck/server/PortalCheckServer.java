package jp.mc.portalcheck.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.mc.portalcheck.McPortalCheck;
import jp.mc.portalcheck.logic.Dim;
import jp.mc.portalcheck.logic.Portal;
import jp.mc.portalcheck.logic.PortalCluster;
import jp.mc.portalcheck.logic.PortalLinkCalc;
import jp.mc.portalcheck.logic.PortalSource;
import jp.mc.portalcheck.logic.Pos;
import jp.mc.portalcheck.net.PortalStatusPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * サーバー側の判定。毎 tick、判定の材料（足元・行き先の目安・ゲートの一覧の番号・手持ち）が変わったときだけ判定し直す。
 * <ul>
 * <li>建てる前: 黒曜石・火打石と打ち金・ファイヤーチャージを持っている間、足元に建てたときの結果を送る</li>
 * <li>目の前にゲートがあるとき: 新しく建てる予測ではなく、そのゲート自身の結果を送る</li>
 * <li>火を付けた直後: 近くのプレイヤーに、できたゲートの結果を 10 秒送る（手に何を持っていても）</li>
 * </ul>
 * ゲートの一覧はチャンクごとに覚えておき、ゲートができた・壊れたチャンクだけ読み直す（{@link PoiPortalSource}）。
 * ネザーゲートの行き来はオーバーワールドとネザーの間だけ調べる。
 */
public final class PortalCheckServer {
    /** 火を付けた直後の結果を見せておく時間（tick） */
    private static final int PLACED_TICKS = 200;
    /** 火を付けた直後の結果を送る範囲（ブロック） */
    private static final double PLACED_NOTIFY_RANGE = 16.0;
    /** 足元からこの範囲（X・Z）にゲートがあれば「目の前のゲート」として扱う */
    private static final int NEAR_REACH = 2;
    /** 読み込み済みのチャンクのゲートを読み直して比べる間隔（tick）。イベントで気づけない変化への備え */
    private static final int REFRESH_TICKS = 20;

    /** 判定の材料。これが前回と同じなら判定し直さない */
    private record Key(ResourceKey<Level> dim, long feet, long target, int genA, int genB, boolean holding, boolean placed) {
    }

    /** プレイヤーごとの、前回送ったもの */
    private static final class Watch {
        PortalStatusPayload last = PortalStatusPayload.clear();
        Key key;
        long placedUntilTick = Long.MIN_VALUE;
        ResourceKey<Level> placedDim;
        BlockPos placedPos;
    }

    private record Lit(ServerLevel level, BlockPos pos) {
    }

    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    private static final Map<ServerLevel, PoiPortalSource> SOURCES = new IdentityHashMap<>();
    private static final List<Lit> LIT = new ArrayList<>();
    private static boolean warned;

    private PortalCheckServer() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(PlayerTickEvent.Post.class, PortalCheckServer::onPlayerTick);
        bus.addListener(BlockEvent.PortalSpawnEvent.class, PortalCheckServer::onPortalSpawn);
        bus.addListener(BlockEvent.NeighborNotifyEvent.class, PortalCheckServer::onNeighborNotify);
        bus.addListener(ServerTickEvent.Post.class, PortalCheckServer::onServerTick);
        bus.addListener(RegisterCommandsEvent.class, e -> PortalDebugCommand.register(e.getDispatcher()));
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> WATCHES.remove(e.getEntity().getUUID()));
        bus.addListener(ServerStoppedEvent.class, e -> {
            WATCHES.clear();
            SOURCES.clear();
            LIT.clear();
        });
    }

    static PoiPortalSource source(ServerLevel level) {
        return SOURCES.computeIfAbsent(level, PoiPortalSource::new);
    }

    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.connection.hasChannel(PortalStatusPayload.TYPE)) {
            return;
        }
        ServerLevel level = player.level();
        long now = level.getServer().getTickCount();
        Watch w = WATCHES.computeIfAbsent(player.getUUID(), id -> new Watch());
        boolean holding = isHoldingPortalItem(player);
        boolean placed = now < w.placedUntilTick && level.dimension() == w.placedDim;
        ServerLevel other = otherSide(level);
        if ((!holding && !placed) || other == null) {
            w.key = null;
            if (w.last.mode() != PortalStatusPayload.Mode.NONE) {
                send(player, w, PortalStatusPayload.clear());
            }
            return;
        }

        PoiPortalSource srcA = source(level);
        PoiPortalSource srcB = source(other);
        Dim a = dimOf(level);
        Dim b = dimOf(other);
        double toB = b.scaleFrom(a);
        BlockPos feet = player.blockPosition();
        Pos target = b.clampToBlock(player.getX() * toB, player.getY(), player.getZ() * toB);
        Key key = new Key(level.dimension(), feet.asLong(), BlockPos.asLong(target.x(), target.y(), target.z()),
                srcA.generation(), srcB.generation(), holding, placed);
        if (key.equals(w.key)) {
            return;
        }
        w.key = key;

        try {
            PortalStatusPayload payload = analyze(player, w, level, other, srcA, srcB, a, b, placed);
            if (!payload.equals(w.last)) {
                send(player, w, payload);
            }
        } catch (RuntimeException ex) {
            // 判定の失敗でサーバーを止めない。ログが埋まらないよう 1 回だけ出す
            if (!warned) {
                warned = true;
                McPortalCheck.LOGGER.warn("ネザーゲートの判定に失敗しました", ex);
            }
        }
    }

    private static PortalStatusPayload analyze(ServerPlayer player, Watch w, ServerLevel level, ServerLevel other,
                                               PoiPortalSource srcA, PoiPortalSource srcB, Dim a, Dim b, boolean placed) {
        BlockPos feet = player.blockPosition();
        Pos feetPos = new Pos(feet.getX(), feet.getY(), feet.getZ());

        // 調べるゲートを決める: 火を付けたゲート → 目の前のゲート → 足元に建てる予定のゲート
        Portal self = null;
        PortalStatusPayload.Mode mode = PortalStatusPayload.Mode.PLAN;
        if (placed) {
            Pos lit = new Pos(w.placedPos.getX(), w.placedPos.getY(), w.placedPos.getZ());
            if (srcA.isPortal(lit)) {
                self = PortalCluster.expand(srcA, lit);
                mode = PortalStatusPayload.Mode.PLACED;
            } else if (blocks(level).isPortal(lit)) {
                // ブロックはできたが、POI への追加（後回しのタスク）がまだ。ブロックから組み立て、戻り先の候補にも入れる
                self = asCandidate(PortalCluster.expand(blocks(level), lit));
                mode = PortalStatusPayload.Mode.PLACED;
            }
        }
        if (self == null) {
            Portal near = PortalLinkCalc.findNear(srcA, feetPos, NEAR_REACH);
            if (near != null) {
                self = near;
                mode = PortalStatusPayload.Mode.EXISTING;
            }
        }
        if (self == null) {
            // ブロックはゲートなのに位置の記録（POI）に無いゲート。くぐって出ることはできるが、
            // サーバーはどのゲートからの戻り先にも選ばない
            Portal orphan = findUnregistered(level, feet);
            if (orphan != null && srcA.isPendingLit(orphan)) {
                // 火を付けたばかりで POI への追加待ち。「未登録」とは出さない
                self = asCandidate(orphan);
                mode = PortalStatusPayload.Mode.EXISTING;
            } else if (orphan != null) {
                self = orphan;
                mode = PortalStatusPayload.Mode.UNREGISTERED;
            }
        }
        double[] entry;
        if (self == null) {
            if (!isHoldingPortalItem(player)) {
                return PortalStatusPayload.clear();
            }
            self = Portal.virtualAt(feetPos);
            entry = new double[]{player.getX(), player.getY(), player.getZ()};
        } else {
            entry = self.entry();
        }
        PortalLinkCalc.Result r = PortalLinkCalc.analyze(a, srcA, b, srcB, self, entry);
        return PortalStatusPayload.of(mode, other.dimension() == Level.NETHER, b.arrivalRadius(), self.anchor(), r);
    }

    /**
     * 足元のすぐ近くで、ブロックはゲートなのに位置の記録に無いものを探す（読み込み済みのブロックを直接見る）。
     * 範囲は {@link PortalLinkCalc#findNear} と同じ（X・Z が ±2、Y が -1〜+3）
     */
    private static Portal findUnregistered(ServerLevel level, BlockPos feet) {
        PortalSource blocks = blocks(level);
        Pos center = new Pos(feet.getX(), feet.getY(), feet.getZ());
        Pos best = null;
        for (int dx = -NEAR_REACH; dx <= NEAR_REACH; dx++) {
            for (int dz = -NEAR_REACH; dz <= NEAR_REACH; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    Pos p = new Pos(center.x() + dx, center.y() + dy, center.z() + dz);
                    if (blocks.isPortal(p) && (best == null || p.distSqr(center) < best.distSqr(center))) {
                        best = p;
                    }
                }
            }
        }
        return best == null ? null : PortalCluster.expand(blocks, best);
    }

    /** 読み込み済みのブロックを直接見る一覧（目の前のゲートを組み立てるときだけ使う） */
    private static PortalSource blocks(ServerLevel level) {
        return new PortalSource() {
            @Override
            public List<Pos> blocksInSquare(Pos center, int radius) {
                return List.of();
            }

            @Override
            public boolean isPortal(Pos pos) {
                BlockPos bp = new BlockPos(pos.x(), pos.y(), pos.z());
                return level.isLoaded(bp) && level.getBlockState(bp).is(Blocks.NETHER_PORTAL);
            }
        };
    }

    /**
     * POI にまだ入っていないゲートを、戻り先の候補に入れて判定するための形にする
     * （PortalLinkCalc は virtual のゲートを戻り先の候補に足す）
     */
    private static Portal asCandidate(Portal p) {
        return new Portal(p.blocks(), p.anchor(), true);
    }

    /** 火を付けた時点ではまだゲートブロックが無いので、チャンクを忘れておき、tick の終わりに近くの人へ知らせる */
    private static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (event.getLevel() instanceof ServerLevel level && otherSide(level) != null) {
            PoiPortalSource src = source(level);
            src.onPortalLit(event.getPos());
            // POI への追加は後回しのタスクなので、入るまで見張る
            src.watchLit(event.getPos(), level.getServer().getTickCount());
            LIT.add(new Lit(level, event.getPos().immutable()));
        }
    }

    /** ブロックの変化。覚えているチャンクでゲートができた・壊れたときだけ読み直す（ほとんどは何もせず返る） */
    private static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PoiPortalSource src = SOURCES.get(level);
            if (src != null) {
                src.onBlockChanged(event.getPos(), event.getState());
            }
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        if (!LIT.isEmpty()) {
            for (Lit l : LIT) {
                notifyPlaced(l, now);
            }
            LIT.clear();
        }
        boolean refresh = now % REFRESH_TICKS == 0;
        for (PoiPortalSource src : SOURCES.values()) {
            src.endTick(now);
            if (refresh) {
                src.refreshLoaded();
            }
        }
    }

    private static void notifyPlaced(Lit lit, long now) {
        double rangeSqr = PLACED_NOTIFY_RANGE * PLACED_NOTIFY_RANGE;
        BlockPos pos = lit.pos();
        for (ServerPlayer p : lit.level().players()) {
            if (p.connection.hasChannel(PortalStatusPayload.TYPE)
                    && p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= rangeSqr) {
                Watch w = WATCHES.computeIfAbsent(p.getUUID(), id -> new Watch());
                w.placedUntilTick = now + PLACED_TICKS;
                w.placedDim = lit.level().dimension();
                w.placedPos = pos;
                w.key = null;
            }
        }
    }

    private static void send(ServerPlayer player, Watch w, PortalStatusPayload payload) {
        w.last = payload;
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * ゲートの向こう側。vanilla と同じくネザーならオーバーワールド、そうでなければネザー。
     * オーバーワールドとネザーの間でなければ null（判定しない）。
     */
    static ServerLevel otherSide(ServerLevel level) {
        ResourceKey<Level> dim = level.dimension();
        if (dim != Level.OVERWORLD && dim != Level.NETHER) {
            return null;
        }
        return level.getServer().getLevel(dim == Level.NETHER ? Level.OVERWORLD : Level.NETHER);
    }

    /** vanilla の決め方: ネザーへ来るときは ±16、それ以外へ来るときは ±128 */
    static Dim dimOf(ServerLevel level) {
        WorldBorder wb = level.getWorldBorder();
        int radius = level.dimension() == Level.NETHER ? Dim.NETHER_RADIUS : Dim.OVERWORLD_RADIUS;
        return new Dim(level.dimensionType().coordinateScale(), radius, wb.getMinX(), wb.getMaxX(), wb.getMinZ(), wb.getMaxZ());
    }

    private static boolean isHoldingPortalItem(ServerPlayer player) {
        return isPortalItem(player.getMainHandItem()) || isPortalItem(player.getOffhandItem());
    }

    private static boolean isPortalItem(ItemStack stack) {
        return stack.is(Items.OBSIDIAN) || stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE);
    }
}
