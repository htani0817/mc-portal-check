package jp.mc.portalcheck.logic;

import static jp.mc.portalcheck.logic.ListSource.gate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PortalLinkCalcTest {
    private static final Dim OW = Dim.OVERWORLD;
    private static final Dim NE = Dim.NETHER;

    /** OW の (x, y, z) に立ってこれから建てるときの判定 */
    private static PortalLinkCalc.Result planInOverworld(int x, int y, int z, ListSource ow, ListSource nether) {
        return PortalLinkCalc.analyze(OW, ow, NE, nether, Portal.virtualAt(new Pos(x, y, z)), new double[]{x + 0.5, y, z + 0.5});
    }

    @Test
    void OWで100離れていてネザー側が1基だけなら共有と片道になる() {
        Portal a = gate(0, 64, 0);
        Portal na = gate(0, 64, 0);
        PortalLinkCalc.Result r = planInOverworld(100, 64, 0, ListSource.of(a), ListSource.of(na));

        assertEquals(new Pos(12, 64, 0), r.target());
        assertEquals(na, r.dest());
        assertFalse(r.returnsToSelf());
        assertEquals(a, r.returnTo());
        assertEquals(List.of(a), r.shared());
        assertTrue(r.interferes());
    }

    @Test
    void ネザー側に無ければ新しく作られ_その位置を範囲に含む既存ゲートと共有になる() {
        Portal a = gate(0, 64, 0);
        PortalLinkCalc.Result r = planInOverworld(100, 64, 0, ListSource.of(a), ListSource.of());

        assertTrue(r.dest().virtual());
        assertEquals(new Pos(12, 64, 0), r.dest().anchor());
        // 新しいネザーのゲートから戻ると、建てたゲートの方が近い
        assertTrue(r.returnsToSelf());
        // 既存ゲート A の行き先（ネザーの 0 付近）から ±16 に新しいゲートが入るので、A もそちらへ行くようになる
        assertEquals(List.of(a), r.shared());
        assertTrue(r.interferes());
    }

    @Test
    void 両側にそれぞれ対のゲートがあれば干渉なし() {
        Portal a = gate(0, 64, 0);
        Portal na = gate(0, 64, 0);
        Portal nh = gate(12, 64, 0);
        PortalLinkCalc.Result r = planInOverworld(100, 64, 0, ListSource.of(a), ListSource.of(na, nh));

        assertEquals(nh, r.dest());
        assertTrue(r.returnsToSelf());
        assertTrue(r.shared().isEmpty());
        assertFalse(r.interferes());
        assertEquals(2, r.destCandidates());
    }

    @Test
    void 戻りの探索で別の既存ゲートの方が近ければ片道になる() {
        // ネザーの Q は建てる位置の目安 (0, 64, 0) から 15 離れている
        Portal q = gate(15, 64, 0);
        // OW の E は Q × 8 の近くにあるが、自分用のネザーゲート P3 の方へつながっている
        Portal e = gate(124, 64, 40);
        Portal p3 = gate(15, 64, 4);
        PortalLinkCalc.Result r = planInOverworld(0, 64, 0, ListSource.of(e), ListSource.of(q, p3));

        assertEquals(q, r.dest());
        assertEquals(e, r.returnTo());
        assertFalse(r.returnsToSelf());
        assertTrue(r.shared().isEmpty());
        assertTrue(r.interferes());
    }

    @Test
    void 建てた後の確定の判定では自分自身を実在のゲートとして扱う() {
        Portal a = gate(0, 64, 0);
        Portal na = gate(0, 64, 0);
        Portal self = gate(300, 64, 0);
        ListSource ow = ListSource.of(a, self);
        PortalLinkCalc.Result r = PortalLinkCalc.analyze(OW, ow, NE, ListSource.of(na), self, self.entry());

        assertTrue(r.dest().virtual());
        assertTrue(r.returnsToSelf());
        assertTrue(r.shared().isEmpty());
        assertFalse(r.interferes());
    }

    @Test
    void ネザーへ行くときの探索は16まで含み17は含まない() {
        // 目安は (0, 64, 0)
        PortalLinkCalc.Result in = planInOverworld(0, 64, 0, ListSource.of(), ListSource.of(gate(16, 64, 0)));
        assertFalse(in.dest().virtual());
        PortalLinkCalc.Result out = planInOverworld(0, 64, 0, ListSource.of(), ListSource.of(gate(17, 64, 0)));
        assertTrue(out.dest().virtual());
    }

    @Test
    void 探索範囲は円ではなく正方形で高さは問わない() {
        // 斜めの角 (16, ?, 16) は直線距離では 16 を超えるが範囲に入る。Y が大きく違っても入る
        PortalLinkCalc.Result r = planInOverworld(0, 64, 0, ListSource.of(), ListSource.of(gate(15, 110, 15)));
        assertFalse(r.dest().virtual());
    }

    @Test
    void ネザーからOWへ行くときの探索は128まで() {
        // ネザー (10, 64, 0) に立つ → OW の目安は (84, 64, 4)
        Portal self = Portal.virtualAt(new Pos(10, 64, 0));
        double[] entry = {10.5, 64, 0.5};
        PortalLinkCalc.Result in = PortalLinkCalc.analyze(NE, ListSource.of(), OW, ListSource.of(gate(84 + 128, 64, 4)), self, entry);
        assertEquals(new Pos(84, 64, 4), in.target());
        assertFalse(in.dest().virtual());
        PortalLinkCalc.Result out = PortalLinkCalc.analyze(NE, ListSource.of(), OW, ListSource.of(gate(84 + 129, 64, 4)), self, entry);
        assertTrue(out.dest().virtual());
    }

    @Test
    void 足元のすぐ近くのゲートを目の前のゲートとして見つける() {
        Portal g = gate(818, 77, -712);
        ListSource ow = ListSource.of(g, gate(830, 77, -712));
        // ゲートの 2 ブロック手前に立っている
        assertEquals(g, PortalLinkCalc.findNear(ow, new Pos(818, 77, -710), 2));
        // 3 ブロック離れると見つけない
        assertEquals(null, PortalLinkCalc.findNear(ow, new Pos(818, 77, -709), 2));
        // 真上の高い所に立っていても見つけない
        assertEquals(null, PortalLinkCalc.findNear(ow, new Pos(818, 90, -712), 2));
    }

    @Test
    void 目の前のゲート自身を調べれば自分のゲートを共有相手に数えない() {
        // スクリーンショットの状況: 建てたばかりで、ネザー側にはまだゲートが無い
        Portal g = gate(818, 77, -712);
        ListSource ow = ListSource.of(g);
        Portal near = PortalLinkCalc.findNear(ow, new Pos(818, 77, -710), 2);
        PortalLinkCalc.Result r = PortalLinkCalc.analyze(OW, ow, NE, ListSource.of(), near, near.entry());
        assertTrue(r.dest().virtual());
        assertTrue(r.returnsToSelf());
        assertTrue(r.shared().isEmpty());
        assertFalse(r.interferes());
    }

    @Test
    void 距離が同じならYの低い方を選ぶ() {
        Pos center = new Pos(0, 64, 0);
        Pos upper = new Pos(1, 65, 0);
        Pos lower = new Pos(1, 63, 0);
        assertEquals(lower, PortalLinkCalc.nearest(List.of(upper, lower), center));
        assertEquals(lower, PortalLinkCalc.nearest(List.of(lower, upper), center));
    }

    @Test
    void 行き先の目安はワールドボーダーの内側に丸める() {
        Dim smallNether = new Dim(8.0, Dim.NETHER_RADIUS, -100, 100, -100, 100);
        PortalLinkCalc.Result r = PortalLinkCalc.analyze(OW, ListSource.of(), smallNether, ListSource.of(),
                Portal.virtualAt(new Pos(5000, 64, -5000)), new double[]{5000.5, 64, -5000.5});
        assertEquals(new Pos(99, 64, -100), r.target());
    }
}
