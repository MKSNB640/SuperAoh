import com.superaoh.ai.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 流式验证：统计增量回调次数，验证 SSE 逐块解析。 */
public class TS {
    public static void main(String[] a) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", "从1数到10，用逗号分隔"));

        LlmRequest req = new LlmRequest(null, null, null, 0.7, 200, 1.0, true,
                new java.util.LinkedHashMap<String, String>());
        req.url = a[0];
        req.apiKey = a[1];
        req.model = a[2];
        req.stream = true;

        final StringBuilder c = new StringBuilder();
        final int[] chunks = new int[1];
        final String[] err = new String[1];
        final String[] fin = new String[1];
        final int[] usage = new int[2];

        OpenAiCompatProvider p = new OpenAiCompatProvider();
        LlmStreamListener l = new LlmStreamListener() {
            public void onContent(String d) { chunks[0]++; c.append(d); }
            public void onReasoning(String d) { System.out.println("[reasoning chunk] " + d.length() + " chars"); }
            public void onToolCall(String i, String n, String g) { System.out.println("TOOL=" + n); }
            public void onUsage(int x, int y) { usage[0] = x; usage[1] = y; }
            public void onEnd(String x) { fin[0] = x; }
            public void onError(String m, boolean r) { err[0] = m; }
        };

        long t0 = System.currentTimeMillis();
        boolean ok = p.stream(req, msgs.toString(), l);
        long ms = System.currentTimeMillis() - t0;

        System.out.println("ok=" + ok + "  耗时=" + ms + "ms");
        System.out.println("增量回调次数=" + chunks[0] + "  （>1 说明确实是流式逐块到达）");
        System.out.println("全文=[" + c + "]");
        System.out.println("finish=" + fin[0] + "  usage=" + usage[0] + "/" + usage[1]);
        if (err[0] != null) System.out.println("error=" + err[0]);
    }
}