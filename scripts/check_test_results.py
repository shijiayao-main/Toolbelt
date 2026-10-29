#!/usr/bin/env python3
"""测试结果 XML 新鲜度校验 + 只读汇总。

机械防线（对齐 AGENTS.md 验收纪律「结果文件时间戳核对非陈旧」）：陈旧/部分在盘的
TEST-*.xml 被当验收依据，是已实锤的假绿通道（各模块上次跑不同任务会留下混合旧快照）。
消费 XML 前跑本脚本，把「人工记得看时间戳」变成机械判据。

用法：
    python3 scripts/check_test_results.py                     # 全仓扫描，30 分钟新鲜度门槛
    python3 scripts/check_test_results.py core build.gradle.kts 无关目录...   # 指定模块目录
    python3 scripts/check_test_results.py --max-age-minutes 60
    python3 scripts/check_test_results.py --allow-failures    # 只校验新鲜度，不拦红（事后分析用）

退出码：0 = 全部 XML 新鲜且无失败；1 = 存在陈旧 XML 或 failures/errors > 0（未传
--allow-failures 时）；2 = 没找到任何 XML（扫描面空 = 可能任务根本没跑）。
"""

import argparse
import glob
import os
import sys
import time
import xml.etree.ElementTree as ET


def main() -> int:
    parser = argparse.ArgumentParser(description="测试结果 XML 新鲜度校验 + 只读汇总")
    parser.add_argument("dirs", nargs="*", help="模块根目录（默认全仓含 build/ 的目录）")
    parser.add_argument("--max-age-minutes", type=float, default=30.0,
                        help="XML mtime 距今的最大分钟数（默认 30）")
    parser.add_argument("--allow-failures", action="store_true",
                        help="不因 failures/errors>0 退出非零（只校验新鲜度）")
    args = parser.parse_args()

    if args.dirs:
        roots = args.dirs
    else:
        roots = [d for d in sorted(glob.glob("*/")) if os.path.isdir(os.path.join(d, "build", "test-results"))]

    max_age = args.max_age_minutes * 60
    now = time.time()
    stale = []
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    per_module = []
    found_any = False

    for root in roots:
        module_total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "files": 0, "stale": 0}
        pattern = os.path.join(root, "build", "test-results", "**", "TEST-*.xml")
        for path in glob.glob(pattern, recursive=True):
            found_any = True
            module_total["files"] += 1
            age = now - os.path.getmtime(path)
            if age > max_age:
                stale.append(path)
                module_total["stale"] += 1
            try:
                suite = ET.parse(path).getroot()
                for key in module_total:
                    if key in ("files", "stale"):
                        continue
                    value = int(suite.get(key, "0"))
                    module_total[key] += value
                    total[key] += value
            except (ET.ParseError, OSError) as exc:
                print(f"  [解析失败] {path}: {exc}", file=sys.stderr)
                stale.append(path)
        if module_total["files"]:
            per_module.append((root.rstrip("/"), module_total))

    if not found_any:
        print("未找到任何 TEST-*.xml——扫描面为空：验收前先确认测试任务真的跑过。")
        return 2

    print(f"{'模块':<28} {'files':>6} {'tests':>6} {'fail':>6} {'err':>5} {'skip':>5} {'stale':>6}")
    for name, m in per_module:
        print(f"{name:<28} {m['files']:>6} {m['tests']:>6} {m['failures']:>6} "
              f"{m['errors']:>5} {m['skipped']:>5} {m['stale']:>6}")
    print(f"{'合计':<28} {'':>6} {total['tests']:>6} {total['failures']:>6} "
          f"{total['errors']:>5} {total['skipped']:>5} {len(stale):>6}")

    if stale:
        print(f"\n🔴 陈旧 XML {len(stale)} 个（mtime 超 {args.max_age_minutes} 分钟）——非本次运行产物，当验收依据即假绿：")
        for path in stale:
            print(f"  {path}")
    if total["failures"] or total["errors"]:
        print(f"\n失败/错误非零：failures={total['failures']} errors={total['errors']}"
              + ("（--allow-failures：不拦红）" if args.allow_failures else ""))

    if stale:
        return 1
    if (total["failures"] or total["errors"]) and not args.allow_failures:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
