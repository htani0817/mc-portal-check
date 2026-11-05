package jp.mc.portalcheck.logic;

import java.util.List;

/** 1 つの次元にあるゲートブロックの問い合わせ口。サーバーでは POI、テストでは座標の一覧で作る */
public interface PortalSource {
    /** X・Z が中心から ±radius（高さは全域）のゲートブロック。ワールドボーダーの外のものは含めない */
    List<Pos> blocksInSquare(Pos center, int radius);

    /** その座標がゲートブロックか */
    boolean isPortal(Pos pos);
}
