package com.superaoh.core.tool.builtin;

import android.content.Context;
import android.view.View;

import com.superaoh.container.GameInputBridge;
import com.superaoh.container.GameRuntime;
import com.superaoh.core.Workspace;
import com.superaoh.core.tool.ToolRegistry;
import com.superaoh.core.tool.ToolResult;
import com.superaoh.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restricted game control tools; no arbitrary reflection or method invocation. */
public final class GameTools {
    private GameTools() {}

    public static void installAll(ToolRegistry registry) {
        if (registry == null) return;
        registry.register(new Status());
        registry.register(new Tap());
        registry.register(new OpenEditor());
    }

    private abstract static class Base implements ToolSpec {
        @Override public String source() { return ToolRegistry.SRC_BUILTIN; }
        @Override public boolean needsWorkspace() { return false; }
        @Override public List<String> required() { return new ArrayList<>(); }
        @Override public Map<String, String> params() { return new LinkedHashMap<>(); }
    }

    private static final class Status extends Base {
        @Override public String name() { return "game_status"; }
        @Override public String description() {
            return "读取当前嵌入式游戏的就绪状态、当前菜单 viewID、是否在编辑器、AI 点击权限开关。只读操作。";
        }
        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            return ToolResult.ok("游戏状态：" + GameInputBridge.status() + "\n" + GameRuntime.probeLine());
        }
    }

    private static final class Tap extends Base {
        @Override public String name() { return "game_tap"; }
        @Override public String description() {
            return "在用户已通过游戏悬浮 AI 面板中的「AI 控制」开关显式授权后，向游戏画面发送一次单击。x/y 为游戏可视区域 0~1 归一化坐标，左上角为(0,0)。每次只发单击，不长按、不连点；只有用户明确要求执行该操作时调用。坐标需要基于用户提供的截图或明确界面信息，不要猜坐标。";
        }
        @Override public Map<String, String> params() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("x", "归一化横坐标，范围 0 到 1");
            p.put("y", "归一化纵坐标，范围 0 到 1");
            return p;
        }
        @Override public List<String> required() {
            List<String> r = new ArrayList<>(); r.add("x"); r.add("y"); return r;
        }
        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            float x = number(args.get("x"), Float.NaN);
            float y = number(args.get("y"), Float.NaN);
            if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
                return ToolResult.fail("坐标必须是 0~1 之间的有效数字。");
            }
            if (!GameInputBridge.isControlsEnabled()) {
                return ToolResult.fail("AI 游戏控制未授权。请用户在游戏内 AI 面板手动打开「AI 控制」开关后再尝试。");
            }
            if (!GameRuntime.isGameReady()) return ToolResult.fail("游戏尚未就绪，未发送点击。");
            boolean ok = GameInputBridge.tap(x, y);
            return ok ? ToolResult.ok(String.format(java.util.Locale.US,
                    "已发送一次游戏点击（x=%.3f, y=%.3f）。此结果仅表示触摸事件已派发，不代表游戏状态已改变。", x, y))
                    : ToolResult.fail("点击未派发；可能游戏画面不可用或触摸目标不是 GLSurfaceView。");
        }
    }

    private static final class OpenEditor extends Base {
        @Override public String name() { return "game_open_editor"; }
        @Override public String description() {
            return "在用户明确要求进入游戏编辑器且游戏已就绪、AI 控制已由用户手动授权时，打开游戏内置编辑器。不是任意菜单反射工具，只允许固定的编辑器入口。";
        }
        @Override public ToolResult execute(Map<String, Object> args, Workspace ws, Context ctx) {
            if (!GameInputBridge.isControlsEnabled()) {
                return ToolResult.fail("AI 游戏控制尚未由用户手动授权，请在游戏内 AI 面板打开「AI 控制」。");
            }
            if (!GameRuntime.isGameReady()) return ToolResult.fail("游戏尚未就绪。");
            return GameInputBridge.openEditor()
                    ? ToolResult.ok("已请求打开游戏编辑器。")
                    : ToolResult.fail("打开编辑器失败：" + GameRuntime.diagnose());
        }
    }

    private static float number(Object value, float fallback) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value == null) return fallback;
        try { return Float.parseFloat(String.valueOf(value)); }
        catch (Throwable ignored) { return fallback; }
    }
}