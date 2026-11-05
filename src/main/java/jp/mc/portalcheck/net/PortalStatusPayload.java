package jp.mc.portalcheck.net;

import java.util.ArrayList;
import java.util.List;
import jp.mc.portalcheck.McPortalCheck;
import jp.mc.portalcheck.logic.Portal;
import jp.mc.portalcheck.logic.PortalLinkCalc;
import jp.mc.portalcheck.logic.Pos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * サーバー → クライアント。干渉チェックの結果。{@link Mode#NONE} なら表示を消す。
 *
 * @param mode           表示の種類
 * @param toNether       行き先がネザーなら true、オーバーワールドなら false
 * @param radius         行き先の次元で既存ゲートを探す範囲（±ブロック）
 * @param self           調べたゲートの座標（建てる前なら足元）
 * @param destExists     行き先が既存のゲートなら true、新しく作られるなら false
 * @param dest           行き先のゲートの座標（新しく作られるなら目安の座標）
 * @param returnState    戻り先の種類
 * @param returnTo       戻り先が別のゲートのときの座標
 * @param shared         行き先を共有するゲートの座標（最大 {@link #MAX_SHARED} 件）
 * @param sharedCount    行き先を共有するゲートの数（shared に入りきらない分も含む）
 * @param destCandidates 行き先の探索範囲に入っている既存ゲートの数
 */
public record PortalStatusPayload(Mode mode, boolean toNether, int radius, Pos self, boolean destExists, Pos dest,
                                  Return returnState, Pos returnTo, List<Pos> shared, int sharedCount,
                                  int destCandidates) implements CustomPacketPayload {
    public static final Type<PortalStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(McPortalCheck.MODID, "status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PortalStatusPayload> STREAM_CODEC =
            CustomPacketPayload.codec(PortalStatusPayload::write, PortalStatusPayload::read);
    public static final int MAX_SHARED = 3;
    private static final Pos ORIGIN = new Pos(0, 0, 0);

    public enum Mode {
        /** 表示しない */
        NONE,
        /** 建てる前の予測（足元に建てたとき） */
        PLAN,
        /** 目の前にある既存のゲート */
        EXISTING,
        /** 火を付けた直後のゲート */
        PLACED,
        /** 目の前にあるが、サーバーの位置の記録（POI）に無いゲート。どのゲートからの戻り先にもならない */
        UNREGISTERED
    }

    public enum Return {
        /** このゲートに戻る */
        SELF,
        /** 別のゲートに出る（片道） */
        OTHER,
        /** 戻り先が見つからず、また新しく作られる */
        NONE
    }

    public static PortalStatusPayload clear() {
        return new PortalStatusPayload(Mode.NONE, false, 0, ORIGIN, false, ORIGIN, Return.SELF, ORIGIN, List.of(), 0, 0);
    }

    public static PortalStatusPayload of(Mode mode, boolean toNether, int radius, Pos self, PortalLinkCalc.Result r) {
        Return ret;
        Pos returnPos = ORIGIN;
        if (r.returnsToSelf()) {
            ret = Return.SELF;
        } else if (r.returnTo() == null) {
            ret = Return.NONE;
        } else {
            ret = Return.OTHER;
            returnPos = r.returnTo().anchor();
        }
        List<Pos> shared = r.shared().stream().limit(MAX_SHARED).map(Portal::anchor).toList();
        return new PortalStatusPayload(mode, toNether, radius, self, !r.dest().virtual(), r.dest().anchor(), ret, returnPos,
                shared, r.shared().size(), r.destCandidates());
    }

    /** 片道になるか、行き先をほかのゲートと共有するなら干渉あり */
    public boolean interferes() {
        return returnState != Return.SELF || sharedCount > 0;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeEnum(mode);
        buf.writeBoolean(toNether);
        buf.writeVarInt(radius);
        writePos(buf, self);
        buf.writeBoolean(destExists);
        writePos(buf, dest);
        buf.writeEnum(returnState);
        writePos(buf, returnTo);
        buf.writeVarInt(shared.size());
        for (Pos p : shared) {
            writePos(buf, p);
        }
        buf.writeVarInt(sharedCount);
        buf.writeVarInt(destCandidates);
    }

    private static PortalStatusPayload read(RegistryFriendlyByteBuf buf) {
        Mode mode = buf.readEnum(Mode.class);
        boolean toNether = buf.readBoolean();
        int radius = buf.readVarInt();
        Pos self = readPos(buf);
        boolean destExists = buf.readBoolean();
        Pos dest = readPos(buf);
        Return ret = buf.readEnum(Return.class);
        Pos returnTo = readPos(buf);
        int n = Math.min(buf.readVarInt(), MAX_SHARED);
        List<Pos> shared = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            shared.add(readPos(buf));
        }
        int sharedCount = buf.readVarInt();
        int destCandidates = buf.readVarInt();
        return new PortalStatusPayload(mode, toNether, radius, self, destExists, dest, ret, returnTo, List.copyOf(shared),
                sharedCount, destCandidates);
    }

    private static void writePos(RegistryFriendlyByteBuf buf, Pos p) {
        buf.writeInt(p.x());
        buf.writeInt(p.y());
        buf.writeInt(p.z());
    }

    private static Pos readPos(RegistryFriendlyByteBuf buf) {
        return new Pos(buf.readInt(), buf.readInt(), buf.readInt());
    }

    @Override
    public Type<PortalStatusPayload> type() {
        return TYPE;
    }
}
