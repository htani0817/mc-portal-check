package jp.mc.portalcheck;

import com.mojang.logging.LogUtils;
import jp.mc.portalcheck.net.PortalStatusPayload;
import jp.mc.portalcheck.server.PortalCheckServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * ネザーゲートの干渉チェック。サーバーとクライアントの両方で読み込まれる入口。
 * 判定はサーバー側（server/）で行い、結果をクライアントへ送る。表示は client/McPortalCheckClient。
 */
@Mod(McPortalCheck.MODID)
public class McPortalCheck {
    public static final String MODID = "mc_portal_check";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 通信の形を変えたら上げる */
    public static final int PROTOCOL = 3;

    public McPortalCheck(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, McPortalCheck::registerPayloads);
        PortalCheckServer.register(NeoForge.EVENT_BUS);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // optional: Mod を入れていないクライアントもサーバーに入れる（表示が出ないだけ）
        PayloadRegistrar registrar = event.registrar(String.valueOf(PROTOCOL)).optional();
        registrar.playToClient(PortalStatusPayload.TYPE, PortalStatusPayload.STREAM_CODEC);
    }
}
