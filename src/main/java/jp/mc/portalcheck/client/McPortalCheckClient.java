package jp.mc.portalcheck.client;

import jp.mc.portalcheck.McPortalCheck;
import jp.mc.portalcheck.net.PortalStatusPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** クライアント側の入口。サーバーから届く結果の受け口と、画面右端のパネルを登録する */
@Mod(value = McPortalCheck.MODID, dist = Dist.CLIENT)
public class McPortalCheckClient {
    public McPortalCheckClient(IEventBus modBus) {
        modBus.addListener(RegisterGuiLayersEvent.class, e ->
                e.registerAboveAll(Identifier.fromNamespaceAndPath(McPortalCheck.MODID, "warning"), new PortalWarningLayer()));
        modBus.addListener(RegisterClientPayloadHandlersEvent.class, e ->
                e.register(PortalStatusPayload.TYPE, (p, ctx) -> PortalStatusState.set(p)));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> PortalStatusState.set(PortalStatusPayload.clear()));
    }
}
