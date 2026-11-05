package jp.mc.portalcheck.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ゲートブロックの集合を、面で隣り合うまとまりごとに 1 基のゲートに分ける */
public final class PortalCluster {
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
    };

    /** vanilla のゲートは最大 21×21 なので、これを超えてたどらない */
    private static final int MAX_BLOCKS = 21 * 21;

    private PortalCluster() {
    }

    /** start を含むゲート 1 基を、隣り合うゲートブロックをたどって取り出す */
    public static Portal expand(PortalSource source, Pos start) {
        Set<Pos> seen = new HashSet<>();
        seen.add(start);
        List<Pos> group = new ArrayList<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && group.size() < MAX_BLOCKS) {
            Pos p = queue.poll();
            group.add(p);
            for (int[] d : NEIGHBORS) {
                Pos n = new Pos(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                if (seen.add(n) && source.isPortal(n)) {
                    queue.add(n);
                }
            }
        }
        return Portal.of(group);
    }

    public static List<Portal> cluster(Collection<Pos> blocks) {
        Set<Pos> rest = new HashSet<>(blocks);
        List<Portal> portals = new ArrayList<>();
        while (!rest.isEmpty()) {
            Pos start = rest.iterator().next();
            rest.remove(start);
            List<Pos> group = new ArrayList<>();
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Pos p = queue.poll();
                group.add(p);
                for (int[] d : NEIGHBORS) {
                    Pos n = new Pos(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                    if (rest.remove(n)) {
                        queue.add(n);
                    }
                }
            }
            portals.add(Portal.of(group));
        }
        portals.sort(Portal.BY_ANCHOR);
        return portals;
    }
}
