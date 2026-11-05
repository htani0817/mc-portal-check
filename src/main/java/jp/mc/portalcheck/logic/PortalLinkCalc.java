package jp.mc.portalcheck.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ネザーゲートのつながり方を vanilla と同じ規則で求め、干渉を調べる。
 * <ol>
 * <li>行き先: 入った位置 × 倍率 を中心に、行き先の次元の ±探索範囲 で一番近いゲートブロック。無ければ新しく作られる</li>
 * <li>戻り先: 行き先のゲートからくぐり直したとき、元の次元で一番近いゲート。自分以外なら片道</li>
 * <li>共有: 元の次元のほかのゲートで、行き先が自分と同じになるもの</li>
 * </ol>
 * 「一番近い」は 3D の直線距離（ブロックの角どうし）で、同じ距離なら Y が低い方（vanilla PortalForcer と同じ）。
 */
public final class PortalLinkCalc {
    /** 共有の判定で、元の次元を戻りの探索範囲より少し広く見る分（ゲートの幅の分） */
    private static final int SHARE_MARGIN = 4;

    /**
     * 判定の結果。
     *
     * @param target         行き先の目安の座標（入った位置 × 倍率）
     * @param back           戻りの探索の中心（行き先のゲートの位置 × 倍率）
     * @param dest           行き先のゲート。{@link Portal#virtual()} なら新しく作られる
     * @param returnTo       戻り先のゲート。見つからなければ null（くぐり直すとまた新しく作られる）
     * @param returnsToSelf  戻り先が自分か
     * @param shared         行き先が同じになる、元の次元のほかのゲート
     * @param destCandidates 行き先の探索範囲に入っているゲートの数
     */
    public record Result(Pos target, Pos back, Portal dest, Portal returnTo, boolean returnsToSelf, List<Portal> shared, int destCandidates) {
        /** 片道になるか、行き先をほかのゲートと共有するなら干渉あり */
        public boolean interferes() {
            return !returnsToSelf || !shared.isEmpty();
        }
    }

    private PortalLinkCalc() {
    }

    /**
     * @param a     元の次元（ゲートを建てる側）
     * @param srcA  元の次元のゲートブロック。self が実在するならそれも含む
     * @param b     行き先の次元
     * @param srcB  行き先の次元のゲートブロック
     * @param self  調べるゲート。建てる前なら {@link Portal#virtualAt} で作る
     * @param entry くぐる位置（x, y, z）
     */
    public static Result analyze(Dim a, PortalSource srcA, Dim b, PortalSource srcB, Portal self, double[] entry) {
        double toB = b.scaleFrom(a);
        double toA = a.scaleFrom(b);

        // 1. 行き先
        Pos target = b.clampToBlock(entry[0] * toB, entry[1], entry[2] * toB);
        List<Pos> destBlocks = srcB.blocksInSquare(target, b.arrivalRadius());
        Pos destBlock = nearest(destBlocks, target);
        Portal dest = destBlock == null ? Portal.virtualAt(target) : PortalCluster.expand(srcB, destBlock);
        int destCandidates = PortalCluster.cluster(destBlocks).size();

        // 2. 戻り先（まだ建てていない自分も候補に入れる）
        double[] destEntry = dest.entry();
        Pos back = a.clampToBlock(destEntry[0] * toA, destEntry[1], destEntry[2] * toA);
        List<Pos> backBlocks = new ArrayList<>(srcA.blocksInSquare(back, a.arrivalRadius()));
        if (self.virtual()) {
            self.blocks().stream().filter(p -> p.inSquare(back, a.arrivalRadius())).forEach(backBlocks::add);
        }
        Pos returnBlock = nearest(backBlocks, back);
        Portal returnTo;
        if (returnBlock == null) {
            returnTo = null;
        } else if (self.contains(returnBlock)) {
            returnTo = self;
        } else {
            returnTo = PortalCluster.expand(srcA, returnBlock);
        }
        boolean returnsToSelf = returnTo != null && returnTo.equals(self);

        // 3. 共有: 戻りの探索範囲にある元の次元のゲートについて、それぞれの行き先が dest と同じか
        List<Portal> shared = new ArrayList<>();
        Set<Portal> others = new LinkedHashSet<>();
        for (Pos p : srcA.blocksInSquare(back, a.arrivalRadius() + SHARE_MARGIN)) {
            if (!self.contains(p) && others.stream().noneMatch(o -> o.contains(p))) {
                others.add(PortalCluster.expand(srcA, p));
            }
        }
        for (Portal other : others) {
            double[] e = other.entry();
            Pos t = b.clampToBlock(e[0] * toB, e[1], e[2] * toB);
            List<Pos> blocks = new ArrayList<>(srcB.blocksInSquare(t, b.arrivalRadius()));
            if (dest.virtual()) {
                dest.blocks().stream().filter(p -> p.inSquare(t, b.arrivalRadius())).forEach(blocks::add);
            }
            Pos hit = nearest(blocks, t);
            if (hit != null && dest.contains(hit)) {
                shared.add(other);
            }
        }
        shared.sort(Portal.BY_ANCHOR);

        return new Result(target, back, dest, returnTo, returnsToSelf, List.copyOf(shared), destCandidates);
    }

    /**
     * 足元のすぐ近く（X・Z が ±reach、Y が -1〜+3）にある既存のゲート。無ければ null。
     * 目の前にゲートがあるときは、新しく建てる予測ではなくそのゲート自身を調べるために使う。
     */
    public static Portal findNear(PortalSource src, Pos feet, int reach) {
        List<Pos> near = src.blocksInSquare(feet, reach).stream()
                .filter(p -> p.y() >= feet.y() - 1 && p.y() <= feet.y() + 3)
                .toList();
        Pos hit = nearest(near, feet);
        return hit == null ? null : PortalCluster.expand(src, hit);
    }

    /** vanilla PortalForcer.findClosestPortalPosition と同じ選び方。距離が同じなら Y が低い方 */
    static Pos nearest(List<Pos> blocks, Pos center) {
        return blocks.stream()
                .min(Comparator.<Pos>comparingDouble(p -> p.distSqr(center)).thenComparingInt(Pos::y))
                .orElse(null);
    }
}
