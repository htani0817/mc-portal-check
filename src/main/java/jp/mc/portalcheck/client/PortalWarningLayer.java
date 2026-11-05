package jp.mc.portalcheck.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import jp.mc.portalcheck.logic.Pos;
import jp.mc.portalcheck.net.PortalStatusPayload;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * 画面の右上に出す、干渉チェックの結果のカード。
 * <pre>
 * ┌──────────────────────────────┐
 * │ [ゲート] ゲート干渉チェック  [干渉あり] │ ← 見出し（紫の帯）と状態のバッジ
 * │ ここに建てると  810, 77, -712          │
 * │ → 行き先  ネザー 101, 77, -89  [新規] │
 * │ ← 戻り    ✓ このゲート                 │
 * │ ⚠ 行き先が同じゲート 1 基              │
 * │     818, 77, -712   8 ブロック先       │
 * │ 向こうの探索範囲 ±16 ・ 既存 0 基       │
 * └──────────────────────────────┘
 * </pre>
 * アイコンはすべて 1 ドットを GUI の 1px で塗るドット絵で、テクスチャやフォントの記号に頼らない。
 * ステータス効果のアイコンが出ているときは、その下にずらす。F1 で HUD を隠すと一緒に消える。
 */
public final class PortalWarningLayer implements GuiLayer {
    // 配置
    private static final int MARGIN = 4;
    private static final int PAD_X = 6;
    private static final int MIN_WIDTH = 176;
    private static final int HEADER_H = 18;
    private static final int ROW_H = 12;
    private static final int ICON = 7;
    private static final int ICON_GAP = 4;
    private static final int LABEL_GAP = 6;
    private static final int LIST_INDENT = ICON + ICON_GAP;
    private static final int FADE_MILLIS = 160;
    private static final int SLIDE = 8;

    // 色（黒に近いカードに、ゲートの紫を差し色に 1 色だけ。状態は赤・緑・琥珀）
    private static final int CARD = 0xD8101015;
    private static final int HEADER = 0xF0221433;
    private static final int RULE = 0x22FFFFFF;
    private static final int PURPLE = 0xFFB27CFF;
    private static final int TEXT = 0xFFF1F1F4;
    private static final int LABEL = 0xFF9C9CAA;
    private static final int DIM = 0xFF6F6F7C;
    private static final int OK = 0xFF5BDB8B;
    private static final int BAD = 0xFFFF7A7A;
    private static final int WARN = 0xFFFFC857;
    private static final int OK_BADGE = 0xFF1C5A35;
    private static final int BAD_BADGE = 0xFF7A2020;
    private static final int TAG = 0x33FFFFFF;

    // ドット絵（7×7、ゲートだけ 7×9）
    private static final String[] PORTAL = {
            "ooooooo",
            "opplppo",
            "oplpppo",
            "opppplo",
            "oppplpo",
            "oplpppo",
            "opplppo",
            "opppplo",
            "ooooooo",
    };
    private static final String[] ARROW_RIGHT = {
            "...a...",
            "....a..",
            ".....a.",
            "aaaaaaa",
            ".....a.",
            "....a..",
            "...a...",
    };
    private static final String[] ARROW_LEFT = {
            "...a...",
            "..a....",
            ".a.....",
            "aaaaaaa",
            ".a.....",
            "..a....",
            "...a...",
    };
    private static final String[] CHECK = {
            ".......",
            "......a",
            ".....aa",
            "a...aa.",
            "aa.aa..",
            ".aaa...",
            "..a....",
    };
    private static final String[] CROSS = {
            "a.....a",
            "aa...aa",
            ".aa.aa.",
            "..aaa..",
            ".aa.aa.",
            "aa...aa",
            "a.....a",
    };
    private static final String[] WARNING = {
            "...a...",
            "..aaa..",
            "..a.a..",
            ".aa.aa.",
            ".aaaaa.",
            "aaa.aaa",
            "aaaaaaa",
    };

    /** 1 行分。幅を測ってから描く */
    private interface Row {
        int height();

        int width(Font font);

        void draw(Painter p, int x, int y, int w);
    }

    /** 透明度をまとめて掛けて描く */
    private record Painter(GuiGraphicsExtractor g, Font font, float alpha) {
        int c(int argb) {
            int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
            return (a << 24) | (argb & 0xFFFFFF);
        }

        void fill(int x0, int y0, int x1, int y1, int color) {
            g.fill(x0, y0, x1, y1, c(color));
        }

        void text(Component t, int x, int y, int color) {
            g.text(font, t, x, y, c(color), true);
        }

        void pixels(String[] art, int x, int y, int color) {
            for (int row = 0; row < art.length; row++) {
                String line = art[row];
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    int px = switch (ch) {
                        case 'a' -> color;
                        case 'o' -> 0xFF1A1124;
                        case 'p' -> 0xFF7A3FD6;
                        case 'l' -> 0xFFC9A2FF;
                        default -> 0;
                    };
                    if (px != 0) {
                        fill(x + col, y + row, x + col + 1, y + row + 1, px);
                    }
                }
            }
        }

        /** 角を 1px 落とした札 */
        void badge(int x, int y, int w, int h, int color) {
            fill(x + 1, y, x + w - 1, y + h, color);
            fill(x, y + 1, x + 1, y + h - 1, color);
            fill(x + w - 1, y + 1, x + w, y + h - 1, color);
        }
    }

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.gui.hud.isHidden() || player == null) {
            return;
        }
        PortalStatusPayload p = PortalStatusState.visible();
        if (p == null) {
            return;
        }
        Font font = mc.font;
        List<Row> rows = buildRows(p, player);

        int width = MIN_WIDTH;
        for (Row r : rows) {
            width = Math.max(width, r.width(font) + PAD_X * 2);
        }
        int height = 0;
        for (Row r : rows) {
            height += r.height();
        }
        height += 4;

        // 出始めは右から少し滑り込みながら濃くなる
        float t = Math.min(1f, PortalStatusState.shownMillis() / (float) FADE_MILLIS);
        float ease = 1f - (1f - t) * (1f - t);
        int x = g.guiWidth() - MARGIN - width + Math.round((1f - ease) * SLIDE);
        int y = top(mc, player);
        Painter painter = new Painter(g, font, ease);

        int accent = p.interferes() ? BAD : OK;
        painter.fill(x, y, x + width, y + height, CARD);
        painter.fill(x, y, x + width, y + HEADER_H, HEADER);
        painter.fill(x, y, x + 2, y + height, accent);

        int cy = y;
        for (Row r : rows) {
            r.draw(painter, x + 2, cy, width - 2);
            cy += r.height();
        }
    }

    /**
     * パネルの上端。ステータス効果のアイコンとトースト（進捗などの通知）の下に置き、
     * 位置が変わるときは跳ばずに滑らかに追いかける。
     */
    private static int top(Minecraft mc, LocalPlayer player) {
        int target = Math.max(effectsBottom(player), toastsBottom(mc));
        long now = System.nanoTime();
        if (PortalStatusState.shownMillis() < 50 || Float.isNaN(smoothY)) {
            smoothY = target;
        } else {
            float dt = Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000f);
            smoothY += (target - smoothY) * Math.min(1f, dt * 14f);
        }
        lastFrameNanos = now;
        return Math.round(smoothY);
    }

    private static float smoothY = Float.NaN;
    private static long lastFrameNanos;
    private static Field toastSlotsField;
    private static boolean toastSlotsFailed;

    /**
     * 右上のトーストの下端。ToastManager の使用中の枠（32px ずつ・最大 5 枠）を読む。
     * 26.x は難読化されていないので名前で読めるが、読めなければ 0 にして重なりを許す。
     */
    private static int toastsBottom(Minecraft mc) {
        if (toastSlotsFailed) {
            return 0;
        }
        try {
            if (toastSlotsField == null) {
                toastSlotsField = ToastManager.class.getDeclaredField("occupiedSlots");
                toastSlotsField.setAccessible(true);
            }
            BitSet slots = (BitSet) toastSlotsField.get(mc.gui.toastManager());
            int used = slots.length();
            return used == 0 ? 0 : used * Toast.SLOT_HEIGHT + MARGIN;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            toastSlotsFailed = true;
            return 0;
        }
    }

    /** ステータス効果のアイコン（良い効果 y=1、悪い効果 y=27 から 24px）と重ならない高さ */
    private static int effectsBottom(LocalPlayer player) {
        boolean beneficial = false;
        boolean harmful = false;
        for (MobEffectInstance e : player.getActiveEffects()) {
            if (!e.showIcon()) {
                continue;
            }
            if (e.getEffect().value().isBeneficial()) {
                beneficial = true;
            } else {
                harmful = true;
            }
        }
        if (harmful) {
            return 27 + 24 + MARGIN;
        }
        if (beneficial) {
            return 1 + 24 + MARGIN;
        }
        return MARGIN;
    }

    private static List<Row> buildRows(PortalStatusPayload p, LocalPlayer player) {
        List<Row> rows = new ArrayList<>();
        boolean bad = p.interferes();
        rows.add(header(Component.translatable("mc_portal_check.title"),
                Component.translatable(bad ? "mc_portal_check.ng" : "mc_portal_check.ok"), bad ? BAD_BADGE : OK_BADGE));

        String modeKey = switch (p.mode()) {
            case EXISTING -> "mc_portal_check.mode.existing";
            case PLACED -> "mc_portal_check.mode.placed";
            case UNREGISTERED -> "mc_portal_check.mode.unregistered";
            default -> "mc_portal_check.mode.plan";
        };
        rows.add(pair(Component.translatable(modeKey), LABEL, Component.literal(coords(p.self())), DIM, 4));
        if (p.mode() == PortalStatusPayload.Mode.UNREGISTERED) {
            // ブロックはあるのにサーバーの位置の記録に無い。どのゲートからも戻ってこられない
            rows.add(iconText(WARNING, BAD, Component.translatable("mc_portal_check.unregistered.title"), BAD));
            rows.add(pairIndented(Component.translatable("mc_portal_check.unregistered.fix"), LABEL, Component.empty(), DIM));
        }
        rows.add(rule());

        Component labelDest = Component.translatable("mc_portal_check.label.dest");
        Component labelReturn = Component.translatable("mc_portal_check.label.return");
        Component dim = Component.translatable(p.toNether() ? "mc_portal_check.dim.nether" : "mc_portal_check.dim.overworld");
        Component destValue = Component.translatable("mc_portal_check.dest.value", dim, coords(p.dest()));
        Component destTag = Component.translatable(p.destExists() ? "mc_portal_check.tag.existing" : "mc_portal_check.tag.new");
        rows.add(labeled(ARROW_RIGHT, PURPLE, labelDest, labelReturn, destValue, TEXT, destTag));

        switch (p.returnState()) {
            case SELF -> rows.add(labeled(ARROW_LEFT, PURPLE, labelReturn, labelDest,
                    Component.translatable("mc_portal_check.return.self"), OK, null, CHECK, OK));
            case OTHER -> rows.add(labeled(ARROW_LEFT, PURPLE, labelReturn, labelDest,
                    Component.translatable("mc_portal_check.return.other", coords(p.returnTo()), distance(player, p.returnTo())),
                    BAD, null, CROSS, BAD));
            case NONE -> rows.add(labeled(ARROW_LEFT, PURPLE, labelReturn, labelDest,
                    Component.translatable("mc_portal_check.return.none"), WARN, null, WARNING, WARN));
        }

        if (p.sharedCount() > 0) {
            rows.add(rule());
            rows.add(iconText(WARNING, WARN, Component.translatable("mc_portal_check.shared.title", p.sharedCount()), WARN));
            for (Pos s : p.shared()) {
                rows.add(pairIndented(Component.literal(coords(s)), TEXT,
                        Component.translatable("mc_portal_check.distance", distance(player, s)), DIM));
            }
            if (p.sharedCount() > p.shared().size()) {
                rows.add(pairIndented(Component.translatable("mc_portal_check.shared.more", p.sharedCount() - p.shared().size()), DIM,
                        Component.empty(), DIM));
            }
        }

        rows.add(rule());
        rows.add(pair(Component.translatable("mc_portal_check.footer", p.radius(), p.destCandidates()), DIM, Component.empty(), DIM, 0));
        return rows;
    }

    private static String coords(Pos p) {
        return p.x() + ", " + p.y() + ", " + p.z();
    }

    /** プレイヤーからゲートの中心あたりまでの、ブロック単位の距離 */
    private static int distance(LocalPlayer player, Pos p) {
        double dx = player.getX() - (p.x() + 0.5);
        double dy = player.getY() - p.y();
        double dz = player.getZ() - (p.z() + 0.5);
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    // ---- 行の種類 ----

    /** 見出し: ゲートのアイコン・題・右端に状態のバッジ */
    private static Row header(Component title, Component badge, int badgeColor) {
        return new Row() {
            @Override
            public int height() {
                return HEADER_H + 2;
            }

            @Override
            public int width(Font font) {
                return 7 + ICON_GAP + font.width(title) + 12 + font.width(badge) + 8;
            }

            @Override
            public void draw(Painter p, int x, int y, int w) {
                p.pixels(PORTAL, x + PAD_X - 2, y + 4, 0);
                p.text(title, x + PAD_X - 2 + 7 + ICON_GAP, y + 5, TEXT);
                int bw = p.font().width(badge) + 8;
                int bx = x + w - PAD_X + 2 - bw;
                p.badge(bx, y + 3, bw, 12, badgeColor);
                p.text(badge, bx + 4, y + 5, TEXT);
            }
        };
    }

    /** 左に文字、続けて薄い文字（モードと座標、フッター） */
    private static Row pair(Component left, int leftColor, Component right, int rightColor, int gap) {
        return new Row() {
            @Override
            public int height() {
                return ROW_H;
            }

            @Override
            public int width(Font font) {
                return font.width(left) + gap + font.width(right);
            }

            @Override
            public void draw(Painter p, int x, int y, int w) {
                int tx = x + PAD_X;
                p.text(left, tx, y + 2, leftColor);
                p.text(right, tx + p.font().width(left) + gap, y + 2, rightColor);
            }
        };
    }

    /** 一覧の 1 件（アイコンの幅だけ字下げ） */
    private static Row pairIndented(Component left, int leftColor, Component right, int rightColor) {
        return new Row() {
            @Override
            public int height() {
                return ROW_H - 1;
            }

            @Override
            public int width(Font font) {
                return LIST_INDENT + font.width(left) + 8 + font.width(right);
            }

            @Override
            public void draw(Painter p, int x, int y, int w) {
                int tx = x + PAD_X + LIST_INDENT;
                p.text(left, tx, y + 1, leftColor);
                p.text(right, tx + p.font().width(left) + 8, y + 1, rightColor);
            }
        };
    }

    /** アイコン付きの 1 行 */
    private static Row iconText(String[] icon, int iconColor, Component text, int color) {
        return new Row() {
            @Override
            public int height() {
                return ROW_H;
            }

            @Override
            public int width(Font font) {
                return ICON + ICON_GAP + font.width(text);
            }

            @Override
            public void draw(Painter p, int x, int y, int w) {
                p.pixels(icon, x + PAD_X, y + 2, iconColor);
                p.text(text, x + PAD_X + ICON + ICON_GAP, y + 2, color);
            }
        };
    }

    private static Row labeled(String[] icon, int iconColor, Component label, Component otherLabel,
                               Component value, int valueColor, Component tag) {
        return labeled(icon, iconColor, label, otherLabel, value, valueColor, tag, null, 0);
    }

    /**
     * 「アイコン・見出し・値」の行。見出しの幅は行き先と戻りで揃える（otherLabel と長い方に合わせる）。
     * 値の前に小さなアイコン（✓ など）、後ろに札（新規・既存）を付けられる。
     */
    private static Row labeled(String[] icon, int iconColor, Component label, Component otherLabel,
                               Component value, int valueColor, Component tag, String[] mark, int markColor) {
        return new Row() {
            @Override
            public int height() {
                return ROW_H + 1;
            }

            private int labelWidth(Font font) {
                return Math.max(font.width(label), font.width(otherLabel));
            }

            @Override
            public int width(Font font) {
                int w = ICON + ICON_GAP + labelWidth(font) + LABEL_GAP + font.width(value);
                if (mark != null) {
                    w += ICON + 3;
                }
                if (tag != null) {
                    w += 4 + font.width(tag) + 6;
                }
                return w;
            }

            @Override
            public void draw(Painter p, int x, int y, int w) {
                Font font = p.font();
                int tx = x + PAD_X;
                p.pixels(icon, tx, y + 2, iconColor);
                tx += ICON + ICON_GAP;
                p.text(label, tx, y + 2, LABEL);
                tx += labelWidth(font) + LABEL_GAP;
                if (mark != null) {
                    p.pixels(mark, tx, y + 2, markColor);
                    tx += ICON + 3;
                }
                p.text(value, tx, y + 2, valueColor);
                tx += font.width(value);
                if (tag != null) {
                    int tw = font.width(tag) + 6;
                    p.badge(tx + 4, y, tw, 11, TAG);
                    p.text(tag, tx + 7, y + 2, LABEL);
                }
            }
        };
    }

    /** 区切りの細い線 */
    private static Row rule() {
        return new Row() {
            @Override
            public int height() {
                return 5;
            }

            @Override
            public int width(Font font) {
                return 0;
            }

            @Override
            public void draw(Painter p, int x, int y, int w) {
                p.fill(x + PAD_X - 2, y + 2, x + w - PAD_X + 2, y + 3, RULE);
            }
        };
    }
}
