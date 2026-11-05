package jp.mc.portalcheck.logic;

/**
 * 判定に使う次元の情報。
 *
 * @param scale         次元の coordinate_scale（オーバーワールド 1、ネザー 8）
 * @param arrivalRadius この次元へ来たときに既存ゲートを探す範囲（ネザー 16、それ以外 128）
 * @param minX          ワールドボーダーの範囲
 */
public record Dim(double scale, int arrivalRadius, double minX, double maxX, double minZ, double maxZ) {
    /** vanilla PortalForcer の探索範囲。ネザーへ行くときは ±16、それ以外へ行くときは ±128 */
    public static final int NETHER_RADIUS = 16;
    public static final int OVERWORLD_RADIUS = 128;
    private static final double BORDER = 2.9999984E7;

    /** ワールドボーダーが既定（±約 3000 万）のオーバーワールド */
    public static final Dim OVERWORLD = new Dim(1.0, OVERWORLD_RADIUS, -BORDER, BORDER, -BORDER, BORDER);
    /** ワールドボーダーが既定のネザー */
    public static final Dim NETHER = new Dim(8.0, NETHER_RADIUS, -BORDER, BORDER, -BORDER, BORDER);

    /**
     * 座標をワールドボーダーの内側に丸めてからブロック座標にする。
     * vanilla の WorldBorder.clampToBounds（→ BlockPos.containing）と同じ。
     */
    public Pos clampToBlock(double x, double y, double z) {
        double cx = Math.clamp(x, minX, maxX - 1.0E-5F);
        double cz = Math.clamp(z, minZ, maxZ - 1.0E-5F);
        return new Pos((int) Math.floor(cx), (int) Math.floor(y), (int) Math.floor(cz));
    }

    /** from からこの次元へ来たときの座標の倍率（vanilla の DimensionType.getTeleportationScale） */
    public double scaleFrom(Dim from) {
        return from.scale / scale;
    }
}
