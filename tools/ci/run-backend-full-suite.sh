#!/usr/bin/env bash
# 项4（2026-10-03）：后端全量测试一键固化跑批——三段 profile 编排 + 内存墙预检 +
# JDK21/self-attach 约定 + 逐段落盘日志。把「目标集绿但全量红」的元凶（单 fork
# 184 类堆增长撞 OS 提交墙，9-30~10-03 累计 10 份 hs_err 实证）用分段重启根除。
#
# 用法：bash tools/ci/run-backend-full-suite.sh
# 产物：logs/mvn-full-suite-t1.log / -t2.log / -t3.log（t1=controller+application
#       48 类，t2=domain 74 类，t3=其余 62 类；段间 JVM 重启堆归零）
#
# 独立跑单段：mvn -Pt1 test（先读 pom <profiles> 注释——分段依据与配平在那里）。
set -uo pipefail

cd "$(dirname "$0")/../.."

# 1) JDK21 兜底：本机 JAVA_HOME 默认 18，构建/测试一律 21（env-jdk21-override）。
#    CI/Linux 镜像的 JAVA_HOME 已由环境提供——本机路径不存在时不动它。
if [ -d "/d/JAVA/JDK/jdk-21" ]; then
  export JAVA_HOME="D:\JAVA\JDK\jdk-21"
elif [ -d "$HOME/JDK/jdk-21" ]; then
  export JAVA_HOME="$HOME/JDK/jdk-21"
fi

# 2) byte-buddy 自附加：Windows 上批量跑 @SpringBootTest 偶发 MockMaker 失败的根治位
#    （项目约定不进 pom argLine——CI 不需要）。
export JAVA_TOOL_OPTIONS="-Djdk.attach.allowAttachSelf=true"

# 3) 提交额度预检（每段开跑前都过一遍——段间 fork 退出会还额，动态最准）。
#    阈值实测定标：0.8G 被拒（fork G1 mmap 255MB 失败）、1.2~1.6G 可跑 →
#    1024 硬闸 / 1600 软警。FreeVirtualMemory 单位 KB，/1024 → MB。
preflight() {
  local free_mb
  free_mb=$(powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory/1024)" 2>/dev/null | tr -d '\r')
  if [ -n "$free_mb" ] && [ "$free_mb" -gt 0 ] 2>/dev/null; then
    if [ "$free_mb" -lt 1024 ]; then
      echo "❌ OS 提交额度仅 ${free_mb}MB（<1024）——fork 必撞内存墙。" >&2
      echo "   先停占提交的大户（dev 后端 mvn spring-boot:run / 多余浏览器标签），再重跑。" >&2
      return 2
    fi
    if [ "$free_mb" -lt 1600 ]; then
      echo "⚠️ 提交额度 ${free_mb}MB（灰区）——跑批期间勿再开大件"
    else
      echo "▸ 提交额度 ${free_mb}MB"
    fi
  else
    echo "⚠️ 预检取数失败（非 Windows / powershell 不可达），继续"
  fi
  return 0
}

# 3.5) mvn 父进程自身也吃提交额度（默认 25%×16G 的要价上限 + 元空间不设防）——
#     它只做编排、不跑测试，钉小上限把额度让给 fork（每段实省 150~300M）。
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m -XX:MaxMetaspaceSize=384m}"

# 4) 三段编排：单段失败不中断后续段（一次拿全证据），最终以非零收尾。
SEGMENTS=(t1 t2 t3)
OVERALL=0
declare -a SUMMARY=()

for s in "${SEGMENTS[@]}"; do
  preflight || exit 2
  SEG_LOG="logs/mvn-full-suite-${s}.log"
  echo "▸ [${s}] mvn -P${s} test → ${SEG_LOG}"
  mvn -B -ntp -P"${s}" test 2>&1 | tee "$SEG_LOG"
  rc=${PIPESTATUS[0]}
  line=$(grep -E '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures' "$SEG_LOG" | tail -1)
  SUMMARY+=("[${s}] rc=${rc} | ${line:-NO_SUMMARY}")
  if [ "$rc" -ne 0 ]; then OVERALL=$rc; fi
done

echo "==== 三段汇总 ===="
printf '%s\n' "${SUMMARY[@]}"
echo "OVERALL_RC=${OVERALL}"
exit "$OVERALL"
