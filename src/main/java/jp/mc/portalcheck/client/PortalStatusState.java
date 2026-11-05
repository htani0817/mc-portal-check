package jp.mc.portalcheck.client;

import jp.mc.portalcheck.net.PortalStatusPayload;

/** サーバーから最後に届いた結果。受け取りも描画もメインスレッドで行う */
final class PortalStatusState {
    /** 火を付けた直後の結果を見せておく時間（ミリ秒）。サーバーから消去が届かなくてもこれで消す */
    private static final long PLACED_MILLIS = 10_000;

    private static PortalStatusPayload current = PortalStatusPayload.clear();
    private static long receivedAt;
    /** パネルが出始めた時刻。出ていなければ 0 */
    private static long shownSince;

    private PortalStatusState() {
    }

    static void set(PortalStatusPayload payload) {
        current = payload;
        receivedAt = System.currentTimeMillis();
    }

    /** いま表示するもの。無ければ null */
    static PortalStatusPayload visible() {
        PortalStatusPayload p = current;
        long now = System.currentTimeMillis();
        boolean show = p.mode() != PortalStatusPayload.Mode.NONE
                && !(p.mode() == PortalStatusPayload.Mode.PLACED && now - receivedAt > PLACED_MILLIS);
        if (!show) {
            shownSince = 0;
            return null;
        }
        if (shownSince == 0) {
            shownSince = now;
        }
        return p;
    }

    /** パネルが出始めてからのミリ秒 */
    static long shownMillis() {
        return shownSince == 0 ? 0 : System.currentTimeMillis() - shownSince;
    }
}
