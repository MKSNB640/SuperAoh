#!/bin/bash
# D8 4.0.48 NPE 触发条件探针（修正版：不再删除源码目录）
R8=/opt/superaoh/tools/r8.jar
LIB=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/d8p2
rm -rf $W; mkdir -p $W

mk() { d=$W/$1/src/t; mkdir -p $d; cat > $d/A.java; }

run_case() {
  name="$1"
  rm -rf $W/$name/cls $W/$name/out
  mkdir -p $W/$name/cls $W/$name/out
  if ! javac --release 8 -nowarn -d $W/$name/cls $W/$name/src/t/*.java 2>$W/$name/javac.err; then
    echo "[$name] JAVAC FAIL: $(head -2 $W/$name/javac.err | tr '\n' ' ')"
    return
  fi
  out=$(java -cp $R8 com.android.tools.r8.D8 --min-api 21 --lib $LIB \
        --output $W/$name/out $(find $W/$name/cls -name '*.class') 2>&1)
  if echo "$out" | grep -q 'NullPointerException'; then
    echo "[$name] D8 NPE"
  elif echo "$out" | grep -qi 'error'; then
    echo "[$name] D8 OTHER: $(echo "$out" | head -2 | tr '\n' ' ')"
  else
    echo "[$name] D8 OK ($(ls $W/$name/out | tr '\n' ' '))"
  fi
}

# c1: Comparator<Meta>，Meta 为嵌套类
mk c1 <<'JAVA'
package t;
import java.util.*;
public class A {
    public static final class Meta { public long updatedAt; }
    static final class Cmp implements Comparator<Meta> {
        public int compare(Meta a, Meta b) {
            if (a.updatedAt == b.updatedAt) return 0;
            return a.updatedAt > b.updatedAt ? -1 : 1;
        }
    }
    public static void go(List<Meta> l) { Collections.sort(l, new Cmp()); }
}
JAVA

# c2: Comparator<Meta>，Meta 为独立顶层类
mkdir -p $W/c2/src/t
cat > $W/c2/src/t/Meta.java <<'JAVA'
package t;
public class Meta { public long updatedAt; }
JAVA
cat > $W/c2/src/t/A.java <<'JAVA'
package t;
import java.util.*;
public class A {
    static final class Cmp implements Comparator<Meta> {
        public int compare(Meta a, Meta b) {
            if (a.updatedAt == b.updatedAt) return 0;
            return a.updatedAt > b.updatedAt ? -1 : 1;
        }
    }
    public static void go(List<Meta> l) { Collections.sort(l, new Cmp()); }
}
JAVA

# c3: Comparator<Object> 擦除泛型，Meta 仍为嵌套类
mk c3 <<'JAVA'
package t;
import java.util.*;
public class A {
    public static final class Meta { public long updatedAt; }
    static final class Cmp implements Comparator<Object> {
        public int compare(Object oa, Object ob) {
            Meta a = (Meta) oa; Meta b = (Meta) ob;
            if (a.updatedAt == b.updatedAt) return 0;
            return a.updatedAt > b.updatedAt ? -1 : 1;
        }
    }
    public static void go(List<Meta> l) { Collections.sort(l, new Cmp()); }
}
JAVA

# c4: 无 Comparator，仅嵌套类 + 泛型集合
mk c4 <<'JAVA'
package t;
import java.util.*;
public class A {
    public static final class Meta { public long updatedAt; }
    public static void go(List<Meta> l) { for (Meta m : l) { m.updatedAt = 1; } }
}
JAVA

# c5: Comparator<Meta>，Cmp 为 public
mk c5 <<'JAVA'
package t;
import java.util.*;
public class A {
    public static final class Meta { public long updatedAt; }
    public static final class Cmp implements Comparator<Meta> {
        public int compare(Meta a, Meta b) {
            if (a.updatedAt == b.updatedAt) return 0;
            return a.updatedAt > b.updatedAt ? -1 : 1;
        }
    }
    public static void go(List<Meta> l) { Collections.sort(l, new Cmp()); }
}
JAVA

# c6: Comparator<Meta> 顶层类（无外部类），Meta 为顶层类
mkdir -p $W/c6/src/t
cat > $W/c6/src/t/Meta.java <<'JAVA'
package t;
public class Meta { public long updatedAt; }
JAVA
cat > $W/c6/src/t/Cmp.java <<'JAVA'
package t;
import java.util.*;
public class Cmp implements Comparator<Meta> {
    public int compare(Meta a, Meta b) {
        if (a.updatedAt == b.updatedAt) return 0;
        return a.updatedAt > b.updatedAt ? -1 : 1;
    }
}
JAVA

for n in c1 c2 c3 c4 c5 c6; do run_case $n; done