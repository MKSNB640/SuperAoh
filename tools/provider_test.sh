#!/bin/bash
# 用真实 API 验证 OpenAiCompatProvider 的 SSE 解析能力
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
P=/storage/emulated/0/SuperAoh项目
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/provtest
rm -rf $W; mkdir -p $W

# 构造测试程序：直接用 Provider 发一次真实请求
cat > $W/T.java <<'JAVA'
import com.superaoh.ai.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class T {
    public static void main(String[] a) throws Exception {
        String base = a[0];
        String key  = a[1];
        String model = a[2];

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role","user").put("content","只回复两个字：收到"));

        LlmRequest req = new LlmRequest();
        req.setUrl(base);
        req.setApiKey(key);
        req.setModel(model);
        req.setMaxTokens(64);
        req.setStream(true);

        final StringBuilder content = new StringBuilder();
        final int[] usage = new int[2];
        final String[] end = new String[1];
        final String[] err = new String[1];

        OpenAiCompatProvider p = new OpenAiCompatProvider();
        LlmStreamListener l = new LlmStreamListener() {
            public void onContent(String d) { content.append(d); }
            public void onReasoning(String d) { }
            public void onToolCall(String id, String n, String args) { }
            public void onUsage(int pi, int co) { usage[0]=pi; usage[1]=co; }
            public void onEnd(String fr) { end[0]=fr; }
            public void onError(String m, boolean r) { err[0]=m; }
        };

        boolean ok = p.stream(req, msgs.toString(), l);
        System.out.println("PROVIDER=" + p.id() + " ok=" + ok);
        System.out.println("CONTENT=[" + content + "]");
        System.out.println("FINISH=" + end[0]);
        System.out.println("USAGE=" + usage[0] + "/" + usage[1]);
        if (err[0] != null) System.out.println("ERROR=" + err[0]);
    }
}
JAVA

echo "=== 编译测试程序 ==="
javac --release 8 -nowarn -cp "$AJ:$P/build/classes:$P/libs/gdx-game.jar:/opt/kotlinc/lib/kotlin-stdlib.jar" -d $W $W/T.java 2>&1 | head -8
echo "javac done"

echo
echo "=== 发起真实请求 ==="
java -cp "$W:$AJ:$P/build/classes:/opt/kotlinc/lib/kotlin-stdlib.jar" T "$1" "$2" "$3" 2>&1 | head -20