package com.jun.aicodehelper.ai.model;

/**
 * 流式 <think> 块剥离器：MiniMax-M2 等推理模型会先输出 <think>…</think>，
 * 逐 token 透传会把思考过程刷给用户。本类跨 chunk 维护状态机，
 * 可能是标签前缀的尾部字符会暂扣到下一段再判定。
 */
public class ThinkStripper {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder buf = new StringBuilder();
    private boolean inside = false;

    /** 喂入一段流式输出，返回当前可安全透传的文本（可能为空串） */
    public String feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        buf.append(chunk);
        StringBuilder out = new StringBuilder();
        while (true) {
            if (inside) {
                int c = buf.indexOf(CLOSE);
                if (c < 0) {
                    // 只保留可能是 CLOSE 前缀的尾巴，其余思考内容丢弃
                    int keep = Math.min(buf.length(), CLOSE.length() - 1);
                    buf.delete(0, buf.length() - keep);
                    break;
                }
                buf.delete(0, c + CLOSE.length());
                inside = false;
            } else {
                int o = buf.indexOf(OPEN);
                if (o < 0) {
                    int keep = Math.min(buf.length(), OPEN.length() - 1);
                    int emitLen = buf.length() - keep;
                    if (emitLen > 0) {
                        out.append(buf.substring(0, emitLen));
                        buf.delete(0, emitLen);
                    }
                    break;
                }
                out.append(buf.substring(0, o));
                buf.delete(0, o + OPEN.length());
                inside = true;
            }
        }
        return out.toString();
    }

    /** 流结束时调用：吐出残余普通文本；仍在思考块内则全部丢弃 */
    public String flush() {
        if (inside) {
            buf.setLength(0);
            return "";
        }
        String rest = buf.toString();
        buf.setLength(0);
        return rest;
    }
}
