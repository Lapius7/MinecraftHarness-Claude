package dev.minecraftharness.review;

/** The text sent to Claude for each review round. */
public final class ReviewPrompt {

    private ReviewPrompt() {}

    public static String build(ReviewLevel level, int round, String maxLabel, String originalRequest, String anchorHint) {
        StringBuilder sb = new StringBuilder(900);
        sb.append("[レビュー ").append(round).append('/').append(maxLabel).append("] いま建てたものを点検して、必要なら直してください。\n");
        sb.append("元の依頼: ").append(originalRequest).append('\n');
        if (anchorHint != null && !anchorHint.isBlank()) sb.append("対象の建築: ").append(anchorHint).append('\n');
        sb.append("点検には mc_take_photo と mc_inspect_world を使い、見ていないことを「問題なし」と書かないでください。\n");
        sb.append("見ること: 依頼との一致、形と比率、窓やドアの穴、浮いているブロック、内装と庭の有無、地形とのなじみ");
        sb.append("、コマンドブロックやミニゲームがあれば動作。\n");
        if (level.strictChecklist()) {
            sb.append("厳格に点検します。屋根と壁のすき間、階段や梯子で上り下りできるか、明かりの数、足元の段差、床と壁の素材の統一も確かめてください。\n");
        }
        sb.append("問題を一覧にして直し、直したあとにもう一度確かめます。直す前の状態を悪くしないよう、広い範囲を作り直すのは避けてください。\n");
        sb.append("返信の最後に、次の 3 行を必ず書いてください(チャットには表示されません)。\n");
        sb.append("REVIEW_FOUND: <見つけた問題の数>\n");
        sb.append("REVIEW_ISSUES: <直したあとに残った問題の数>\n");
        sb.append("REVIEW_FIXED: <直した内容を 40 字以内で>\n");
        sb.append("本文には、見つけた問題と直した内容を短く書いてください。");
        return sb.toString();
    }
}
