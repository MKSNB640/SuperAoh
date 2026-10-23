#!/bin/bash
# D8 4.0.48 NPE 触发条件探针
R8=/opt/superaoh/tools/r8.jar
LIB=/usr/lib/android-sdk/platforms/android-23/android.jar
W=/tmp/d8probe
rm -rf $W; mkdir -p $W

run_case() {
  name="$1"
  rm -rf $W/$name; mkdir -p $W/$name/cls $W/$name/out
  javac --release 8 -nowarn -d $W/$name/cls $W/$name/src/t/*.java 2>/dev/null
  if [ $? -ne 0 ]; then echo "[$name] JAVAC FAIL"; return; fi
  res=$(java -cp $R8 com.android.tools.r8.D8 --min-api 21 --lib $LIB \
        --output $W/$name/out $(find $W/$name/cls -name '*.class') 2>&1 | grep -c 'NullPointerException')
  if [ "$res" -gt 0 ]; then echo "[$name] D8 NPE"; else echo "[$name] D8 OK"; fi
}

mk() { d=$W/$1/src/t; mkdir -p $d; cat > $d/A.java; }

# 用例1：Comparator<Meta>，Meta 是嵌套类（预期崩溃）
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

# 用例2：Comparator<Meta>，但 Meta 是独立顶层类
rm -rf $W/c2; mkdir -p $W/c2/src/t
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

# 用例3：Comparator<Object>，擦除泛型，Meta 仍是嵌套类
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

# 用例4：完全没有 Comparator，只用静态嵌套类 + 泛型集合
mk c4 <<'JAVA'
package t;
import java.util.*;
public class A {
    public static final class Meta { public long updatedAt; }
    public static void go(List<Meta> l) { for (Meta m : l) { m.updatedAt = 1; } }
}
JAVA

# 用例5：Comparator<Meta> 嵌套类，但不使用匿名/私有，改 public
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

run_case c1
run_case c2
run_case c3
run_case c4
run_case c5
