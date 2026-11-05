package jp.mc.portalcheck.logic;

import java.util.Comparator;
import java.util.List;

/**
 * ゲート 1 基。隣り合うゲートブロックのまとまり。
 *
 * @param blocks  ゲートブロックの座標（vanilla はこの 1 個ずつを行き先の候補にする）
 * @param anchor  表示に使う代表の座標（X・Y・Z それぞれの最小）
 * @param virtual まだ無いゲート（これから建てる・これから自動で作られる）なら true
 */
public record Portal(List<Pos> blocks, Pos anchor, boolean virtual) {
    public Portal {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks が空");
        }
        blocks = List.copyOf(blocks);
    }

    /** ブロックの並びを揃えておくので、同じゲートなら equals が true になる */
    public static Portal of(List<Pos> blocks) {
        List<Pos> sorted = blocks.stream().sorted(BLOCK_ORDER).toList();
        return new Portal(sorted, minCorner(sorted), false);
    }

    private static final Comparator<Pos> BLOCK_ORDER =
            Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z);

    /** まだ無いゲートを 1 ブロックだけで表す */
    public static Portal virtualAt(Pos pos) {
        return new Portal(List.of(pos), pos, true);
    }

    /**
     * プレイヤーがくぐるときの位置の目安。vanilla は入ったエンティティの座標で行き先を決めるので、
     * ゲートの中央の足元（X・Z はブロックの中心の平均、Y は一番下のブロック）を使う。
     */
    public double[] entry() {
        double sx = 0;
        double sz = 0;
        int minY = Integer.MAX_VALUE;
        for (Pos p : blocks) {
            sx += p.x() + 0.5;
            sz += p.z() + 0.5;
            minY = Math.min(minY, p.y());
        }
        return new double[]{sx / blocks.size(), minY, sz / blocks.size()};
    }

    public boolean contains(Pos pos) {
        return blocks.contains(pos);
    }

    private static Pos minCorner(List<Pos> blocks) {
        int x = blocks.stream().mapToInt(Pos::x).min().orElseThrow();
        int y = blocks.stream().mapToInt(Pos::y).min().orElseThrow();
        int z = blocks.stream().mapToInt(Pos::z).min().orElseThrow();
        return new Pos(x, y, z);
    }

    /** 並べたときに順番がぶれないようにするための比較 */
    public static final Comparator<Portal> BY_ANCHOR =
            Comparator.comparingInt((Portal p) -> p.anchor.x()).thenComparingInt(p -> p.anchor.z()).thenComparingInt(p -> p.anchor.y());
}
