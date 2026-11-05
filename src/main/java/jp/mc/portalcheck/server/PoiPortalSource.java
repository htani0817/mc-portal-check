package jp.mc.portalcheck.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import jp.mc.portalcheck.logic.PortalSource;
import jp.mc.portalcheck.logic.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 1 つの次元のゲートブロックの一覧。POI（ゲートブロックの位置の記録）から引き、チャンクごとに覚えておく。
 * <ul>
 * <li>vanilla の PortalForcer は呼ばない（Youer などのパッチで引数が変わっていても動くように）</li>
 * <li>チャンクを読み込んだり生成したりしない。読み込み済みのチャンクだけ、ブロックが本当にゲートかも確かめる</li>
 * <li>覚えたチャンクでゲートができた・壊れたら、そのチャンクを忘れて {@link #generation()} を進める。
 * 判定する側は generation が変わったときだけ判定し直せばよい</li>
 * <li>POI の更新は vanilla でも後回しのタスク（server.execute）になり、負荷のあるサーバーでは何 tick も遅れる。
 * そのため、変化を見たチャンクは次の tick にもう一度忘れ、火を付けたゲートは POI に入るまで毎 tick 見張る</li>
 * <li>イベントで気づけない変化に備え、1 秒ごとに読み込み済みのチャンクだけ読み直して比べ、変わっていれば番号を進める
 * （読み込まれていないチャンクのゲートは変わらないので、覚えたまま使う）</li>
 * </ul>
 * メインスレッドからだけ使う。
 */
final class PoiPortalSource implements PortalSource {
    /** ゲートの一番大きいもの（21×21）がまたがりうるチャンクの範囲 */
    private static final int LIT_CHUNK_REACH = 2;

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<List<Pos>> byChunk = new Long2ObjectOpenHashMap<>();
    /** 次の tick にもう一度忘れるチャンク */
    private final LongOpenHashSet recheck = new LongOpenHashSet();
    /** 火を付けたが、まだ POI に入っていないゲートの位置と、見張りをやめる tick */
    private final Long2ObjectOpenHashMap<Long> pendingLit = new Long2ObjectOpenHashMap<>();
    /** 覚えるチャンクの上限。超えたら全部忘れる（めったに起きない） */
    private static final int MAX_CHUNKS = 50_000;
    /** 火を付けたゲートを見張る長さ（tick） */
    private static final int LIT_WATCH_TICKS = 100;
    private int generation;

    PoiPortalSource(ServerLevel level) {
        this.level = level;
    }

    /** ゲートの一覧が変わるたびに進む番号 */
    int generation() {
        return generation;
    }

    @Override
    public List<Pos> blocksInSquare(Pos center, int radius) {
        List<Pos> out = new ArrayList<>();
        int minCx = Math.floorDiv(center.x() - radius, 16);
        int maxCx = Math.floorDiv(center.x() + radius, 16);
        int minCz = Math.floorDiv(center.z() - radius, 16);
        int maxCz = Math.floorDiv(center.z() + radius, 16);
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (Pos p : chunk(cx, cz)) {
                    if (p.inSquare(center, radius)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public boolean isPortal(Pos pos) {
        return chunk(pos.x() >> 4, pos.z() >> 4).contains(pos);
    }

    /**
     * ブロックが変わったとき（NeighborNotifyEvent）。覚えているチャンクで、ゲートができた・消えた、
     * または黒曜石が置かれた（くぐった先で自動で作られるゲートは周りへの通知が無いので、枠の黒曜石で気づく）なら忘れる。
     */
    void onBlockChanged(BlockPos pos, BlockState state) {
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        List<Pos> cached = byChunk.get(key);
        if (cached == null) {
            return;
        }
        boolean isPortal = state.is(Blocks.NETHER_PORTAL);
        boolean wasPortal = cached.contains(new Pos(pos.getX(), pos.getY(), pos.getZ()));
        if (isPortal != wasPortal || state.is(Blocks.OBSIDIAN)) {
            forget(key);
        }
    }

    /** ゲートに火を付けたとき。ゲートがまたがりうるチャンクをまとめて忘れる */
    void onPortalLit(BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -LIT_CHUNK_REACH; dx <= LIT_CHUNK_REACH; dx++) {
            for (int dz = -LIT_CHUNK_REACH; dz <= LIT_CHUNK_REACH; dz++) {
                forget(ChunkPos.pack(cx + dx, cz + dz));
            }
        }
    }

    /** 火を付けたゲートを、POI に入るまで見張る */
    void watchLit(BlockPos pos, long now) {
        pendingLit.put(pos.asLong(), Long.valueOf(now + LIT_WATCH_TICKS));
    }

    /** 火を付けて、まだ POI に入っていないゲートか（その間は「未登録」と出さない） */
    boolean isPendingLit(jp.mc.portalcheck.logic.Portal portal) {
        for (long key : pendingLit.keySet()) {
            BlockPos bp = BlockPos.of(key);
            if (portal.contains(new Pos(bp.getX(), bp.getY(), bp.getZ()))) {
                return true;
            }
        }
        return false;
    }

    /** 毎 tick の終わりに呼ぶ */
    void endTick(long now) {
        // 前の tick に変化を見たチャンクを、もう一度忘れる
        if (!recheck.isEmpty()) {
            recheck.forEach(byChunk::remove);
            recheck.clear();
            generation++;
        }
        // 火を付けたゲートが POI に入ったら、そのまわりを読み直させる
        if (!pendingLit.isEmpty()) {
            var it = pendingLit.long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                BlockPos bp = BlockPos.of(e.getLongKey());
                if (level.getPoiManager().existsAtPosition(PoiTypes.NETHER_PORTAL, bp)) {
                    onPortalLit(bp);
                    it.remove();
                } else if (now > e.getValue()) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 1 秒ごとに呼ぶ。読み込み済みのチャンクだけ POI を読み直し、覚えている一覧と違えば入れ替えて番号を進める。
     * 読み込み済みのチャンクの POI はメモリ上にあるので軽い
     */
    void refreshLoaded() {
        if (byChunk.size() > MAX_CHUNKS) {
            byChunk.clear();
            generation++;
            return;
        }
        boolean changed = false;
        var it = byChunk.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            ChunkPos cp = ChunkPos.unpack(e.getLongKey());
            if (!level.getChunkSource().hasChunk(cp.x(), cp.z())) {
                continue;
            }
            List<Pos> fresh = read(cp.x(), cp.z());
            if (!sameBlocks(fresh, e.getValue())) {
                e.setValue(fresh);
                changed = true;
            }
        }
        if (changed) {
            generation++;
        }
    }

    private void forget(long key) {
        byChunk.remove(key);
        recheck.add(key);
        generation++;
    }

    private List<Pos> chunk(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        List<Pos> cached = byChunk.get(key);
        if (cached != null) {
            return cached;
        }
        List<Pos> list = read(cx, cz);
        byChunk.put(key, list);
        return list;
    }

    /** 並び順に関係なく同じゲートブロックか */
    private static boolean sameBlocks(List<Pos> x, List<Pos> y) {
        return x.size() == y.size() && new java.util.HashSet<>(x).containsAll(y);
    }

    private List<Pos> read(int cx, int cz) {
        return level.getPoiManager()
                .getInChunk(type -> type.is(PoiTypes.NETHER_PORTAL), new ChunkPos(cx, cz), PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(level.getWorldBorder()::isWithinBounds)
                .filter(bp -> !level.isLoaded(bp) || level.getBlockState(bp).is(Blocks.NETHER_PORTAL))
                .map(bp -> new Pos(bp.getX(), bp.getY(), bp.getZ()))
                .toList();
    }
}
