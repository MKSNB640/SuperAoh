#!/usr/bin/env python3
"""生成文明数据链路的合成测试 APK。

不包含真实游戏数据（版权原因），也不含 DEX：
只按真实 APK 的目录结构与文件格式放几个条目，用来验证
索引解析、名称包读取、行替换、追加幂等这些纯文件层逻辑。

用法: python3 tools/build_civilization_fixture.py [输出路径]
默认输出: build/fixtures/civ.apk
"""

import os
import sys
import zipfile

DEFAULT_OUT = os.path.join("build", "fixtures", "civ.apk")

# 原版索引风格：首尾都有分号；ghost 是故意留的悬空引用（真实 mod 里常见）
INDEX = ";aaa;bbb;ghost;"

# 名称包：带 BOM、注释、空行，模拟真实语言包
BUNDLE_HAN = (
    "\ufeff文明名称（中文）\n"
    "\n"
    "aaa = 测试\n"
    "bbb = 乙\n"
)

BUNDLE_EN = (
    "aaa = Test\n"
    "bbb = Beta\n"
)

# 文明对象在真实 APK 里是 Java 原生序列化字节；这里只需要"存在且非空"，
# 因为本测试验证的是文件层逻辑，不加载游戏类。
CIV_OBJECT = b"\xac\xed\x00\x05" + b"\x00" * 60


def build(path: str) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        base = "assets/game/"
        z.writestr(base + "civilizations/Age_of_Civilizations", INDEX)
        z.writestr(base + "civilizations/aaa", CIV_OBJECT)
        z.writestr(base + "civilizations/bbb", CIV_OBJECT)
        z.writestr(base + "languages/civilizations/Bundle_han.properties", BUNDLE_HAN)
        z.writestr(base + "languages/civilizations/Bundle.properties", BUNDLE_EN)
    print(path)


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT)
