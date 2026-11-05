package jp.mc.portalcheck.logic;

/** ブロックの座標。Minecraft の BlockPos に依存しないようにするための最小限の型 */
public record Pos(int x, int y, int z) {
    /** vanilla の BlockPos.distSqr と同じく、ブロックの角どうしの距離の 2 乗 */
    public double distSqr(Pos o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** X・Z の正方形（±radius、高さは問わない）に入っているか。vanilla の PoiManager.getInSquare と同じ判定 */
    public boolean inSquare(Pos center, int radius) {
        return Math.abs(x - center.x) <= radius && Math.abs(z - center.z) <= radius;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
