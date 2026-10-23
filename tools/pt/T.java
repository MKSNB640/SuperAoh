import com.superaoh.ai.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class T {
    public static void main(String[] a) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", "say hi"));

        LlmRequest req = new LlmRequest(null, null, null, 0.7, 64, 1.0, true,
                new java.util.LinkedHashMap<String, String>());
        req.url = a[0];
        req.apiKey = a[1];
        req.model = a[2];
        req.stream = true;

        final StringBuilder c = new StringBuilder();
        final String[] err = new String[1];
        final String[] fin = new String[1];

        OpenAiCompatProvider p = new OpenAiCompatProvider();
        LlmStreamListener l = new LlmStreamListener() {
            public void onContent(String d) { c.append(d); }
            public void onReasoning(String d) { }
            public void onToolCall(String i, String n, String g) {
                System.out.println("TOOL=" + n + " args=" + g);
            }
            public void onUsage(int x, int y) { System.out.println("USAGE=" + x + "/" + y); }
            public void onEnd(String x) { fin[0] = x; }
            public void onError(String m, boolean r) { err[0] = m + " (retryable=" + r + ")"; }
        };

        boolean ok = p.stream(req, msgs.toString(), l);
        System.out.println("provider=" + p.id() + " ok=" + ok);
        System.out.println("content=[" + c + "]");
        System.out.println("finish=" + fin[0]);
        if (err[0] != null) System.out.println("error=" + err[0]);
    }
}