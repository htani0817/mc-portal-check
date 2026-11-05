package jp.mc.portalcheck.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** テスト用。ゲートブロックの一覧で次元を表す */
final class ListSource implements PortalSource {
    private final Set<Pos> blocks = new HashSet<>();

    static ListSource of(Portal... portals) {
        ListSource s = new ListSource();
        for (Portal p : portals) {
            s.blocks.addAll(p.blocks());
        }
        return s;
    }

    @Override
    public List<Pos> blocksInSquare(Pos center, int radius) {
        List<Pos> out = new ArrayList<>();
        for (Pos p : blocks) {
            if (p.inSquare(center, radius)) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public boolean isPortal(Pos pos) {
        return blocks.contains(pos);
    }

    /** 内側 2×3 の普通のゲート（X 方向に並ぶ）。(x, y, z) が左下 */
    static Portal gate(int x, int y, int z) {
        List<Pos> b = new ArrayList<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                b.add(new Pos(x + dx, y + dy, z));
            }
        }
        return Portal.of(b);
    }
}
