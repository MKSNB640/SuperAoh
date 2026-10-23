#!/bin/bash
# 泛型 implements 触发范围探针
R8=/opt/superaoh/tools/r8.jar
LIB=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/d8p3
rm -rf $W; mkdir -p $W

run_case() {
  name="$1"
  rm -rf $W/$name/cls $W/$name/out
  mkdir -p $W/$name/cls $W/$name/out
  if ! javac --release 8 -nowarn -d $W/$name/cls $W/$name/src/t/*.java 2>$W/$name/javac.err; then
    echo "[$name] JAVAC FAIL: $(head -1 $W/$name/javac.err)"
    return
  fi
  out=$(java -cp $R8 com.android.tools.r8.D8 --min-api 21 --lib $LIB \
        --output $W/$name/out $(find $W/$name/cls -name '*.class') 2>&1)
  if echo "$out" | grep -q 'NullPointerException'; then
    echo "[$name] D8 NPE"
  elif echo "$out" | grep -qi 'error'; then
    echo "[$name] D8 OTHER: $(echo "$out" | head -1)"
  else
    echo "[$name] D8 OK"
  fi
}

mk() { d=$W/$1/src/t; mkdir -p $d; cat > $d/A.java; }

# g1: implements Runnable（非泛型接口）—— 基线
mk g1 <<'JAVA'
package t;
public class A {
    static final class R implements Runnable { public void run() {} }
    public static void go() { new R().run(); }
}
JAVA

# g2: implements 自定义泛型接口
mk g2 <<'JAVA'
package t;
public class A {
    public interface H<T> { void on(T v); }
    static final class I implements H<String> { public void on(String v) {} }
    public static void go() { new I().on("x"); }
}
JAVA

# g3: extends 泛型类
mk g3 <<'JAVA'
package t;
public class A {
    public static class Base<T> { public T v; }
    static final class S extends Base<String> { }
    public static void go() { new S().v = "x"; }
}
JAVA

# g4: implements Comparable<T>
mk g4 <<'JAVA'
package t;
public class A {
    public static final class M implements Comparable<M> {
        public int compareTo(M o) { return 0; }
    }
    public static void go() { new M().compareTo(new M()); }
}
JAVA

# g5: 泛型字段 + 泛型方法，无泛型 implements
mk g5 <<'JAVA'
package t;
import java.util.*;
public class A {
    private List<String> xs = new ArrayList<>();
    public <T> T pick(T a, T b) { return a; }
    public static void go() { A a = new A(); a.xs.add("1"); }
}
JAVA

# g6: 泛型接口 + 顶层实现类
mkdir -p $W/g6/src/t
cat > $W/g6/src/t/H.java <<'JAVA'
package t;
public interface H<T> { void on(T v); }
JAVA
cat > $W/g6/src/t/I.java <<'JAVA'
package t;
public class I implements H<String> { public void on(String v) {} }
JAVA

for n in g1 g2 g3 g4 g5 g6; do run_case $n; done