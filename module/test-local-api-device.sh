#!/usr/bin/env bash
# Local API 真机联调：用 adb 端口转发把设备上的监听端口映射到本机，然后跑协议矩阵。
#
# 用法:
#   ./test-local-api-device.sh [api-key] [port]
#
# 前置条件:
#   1. 模块已装且宿主已启动，Local API 已开启（见 docs/LOCAL_API.md 第 1 节）；
#   2. 设备已通过 adb 连接。
#
# 说明: 每次都重建 adb 转发，因为 adb daemon 重启会丢弃已有 forward，
#       那会让整套测试表现为"连接被拒绝"而掩盖真实的服务端状态。
set -u

KEY="${1:-}"
PORT="${2:-8765}"
ADB="${ADB:-adb}"
BASE="http://127.0.0.1:${PORT}"

if [ -z "$KEY" ]; then
  echo "usage: $0 <api-key> [port]" >&2
  echo "api key 见宿主文件目录下的 dq0_config.json，或模块设置页" >&2
  exit 2
fi

fwd() { "$ADB" forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1; }

# method path [json-body] [extra curl args...]
req() {
  local m="$1" p="$2" d="${3:-}"
  shift 3 2>/dev/null || shift $#
  fwd
  if [ -n "$d" ]; then
    curl -s -m 120 -w "\n<<HTTP:%{http_code}>>\n" -X "$m" \
      -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d "$d" "$@" "${BASE}${p}"
  else
    curl -s -m 120 -w "\n<<HTTP:%{http_code}>>\n" -X "$m" \
      -H "Authorization: Bearer $KEY" "$@" "${BASE}${p}"
  fi
}

fail=0
check() { # label expected actual
  if [ "$2" = "$3" ]; then
    echo "  ok   $1 (HTTP $3)"
  else
    echo "  FAIL $1 (expected HTTP $2, got $3)"; fail=1
  fi
}
status() { sed -n 's/.*<<HTTP:\([0-9]*\)>>.*/\1/p' | tail -1; }

echo "== 1. /health 免鉴权 =="
fwd; check "health" 200 "$(curl -s -m 10 -w "\n<<HTTP:%{http_code}>>\n" "$BASE/health" | status)"

echo "== 2. 错误 Key 必须 401 =="
fwd; check "bad key" 401 \
  "$(curl -s -m 10 -w "\n<<HTTP:%{http_code}>>\n" -H "Authorization: Bearer nope" "$BASE/v1/models" | status)"

echo "== 3. /v1/models =="
out=$(req GET /v1/models); echo "$out" | head -3
check "models" 200 "$(echo "$out" | status)"

echo "== 4. 协议隔离：openai 模式下 /v1/messages 应 404 =="
out=$(req POST /v1/messages '{"model":"deepseek-chat","messages":[{"role":"user","content":"hi"}]}')
check "protocol isolation" 404 "$(echo "$out" | status)"

echo "== 5. 动词不匹配应 405 =="
check "method not allowed" 405 "$(req POST /v1/models '{}' | status)"

echo "== 6. 未知路径应 404 =="
check "unknown endpoint" 404 "$(req GET /v1/nope | status)"

echo "== 7. 非法 JSON 应 400 =="
check "invalid json" 400 "$(req POST /v1/chat/completions '{"model":"deepseek-chat", messages:' | status)"

echo "== 8. 流式请求必须给出 SSE 终结帧（错误也要在带内表达） =="
out=$(req POST /v1/chat/completions \
  '{"model":"deepseek-chat","stream":true,"messages":[{"role":"user","content":"数到三"}]}')
echo "$out" | head -6
if echo "$out" | grep -q "data:"; then
  echo "  ok   stream produced SSE frames"
else
  echo "  FAIL stream produced no SSE frame at all (客户端会看到空答复)"; fail=1
fi

echo "== 9. 统计应随请求变化 =="
fwd; curl -s -m 10 "$BASE/health" | head -c 400; echo

echo
if [ "$fail" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit "$fail"
