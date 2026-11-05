package jp.mc.portalcheck.logic;

import static jp.mc.portalcheck.logic.ListSource.gate;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortalClusterTest {
    @Test
    void 離れた2基は別のゲートになる() {
        List<Pos> blocks = new ArrayList<>(gate(0, 64, 0).blocks());
        blocks.addAll(gate(10, 70, 5).blocks());
        List<Portal> portals = PortalCluster.cluster(blocks);
        assertEquals(2, portals.size());
        assertEquals(new Pos(0, 64, 0), portals.get(0).anchor());
        assertEquals(new Pos(10, 70, 5), portals.get(1).anchor());
        assertEquals(6, portals.get(0).blocks().size());
    }

    @Test
    void 角の1ブロックから1基全体をたどれる() {
        Portal g = gate(3, 64, -7);
        ListSource src = ListSource.of(g, gate(20, 64, -7));
        Portal found = PortalCluster.expand(src, new Pos(4, 66, -7));
        assertEquals(g, found);
    }

    @Test
    void くぐる位置はゲートの中央の足元() {
        double[] e = gate(0, 64, 0).entry();
        assertEquals(1.0, e[0], 1e-9);
        assertEquals(64.0, e[1], 1e-9);
        assertEquals(0.5, e[2], 1e-9);
    }
}
