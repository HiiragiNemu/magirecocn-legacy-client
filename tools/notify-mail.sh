#!/usr/bin/env bash
# 红灯/黄灯邮件通知（宝塔 webhook 格式），last-green.yml 与 main-checks.yml 共用。
#
# 用法（全部走环境变量）：
#   TITLE="🔴 ..." MSG="..." NOTIFY_URL=... NOTIFY_TOKEN=... NOTIFY_TO=... \
#     bash tools/notify-mail.sh
#
# 环境变量：
#   NOTIFY_URL        通知端点（仓库变量）
#   NOTIFY_TOKEN      Bearer 令牌（仓库机密）
#   NOTIFY_TO         收件人，逗号/分号/空格分隔多个，逐人各发一封
#   NOTIFY_FROM       发件地址（仓库变量）
#   NOTIFY_FROM_NAME  发件人显示名（仓库变量）
#   TITLE / MSG       邮件标题 / 正文
#
# 原则：通知是附属动作，**永不阻断**——变量没配齐、网络断了、服务端 4xx/5xx，
# 一律打 warning 后退出 0，不改变 job 自己的结论。证书校验保持开启
# （curl 默认）。
set -uo pipefail

if [ -z "${NOTIFY_URL:-}" ] || [ -z "${NOTIFY_TOKEN:-}" ] || [ -z "${NOTIFY_TO:-}" ]; then
  echo "（NOTIFY_URL / NOTIFY_TOKEN / NOTIFY_TO 未配齐，跳过邮件通知）"
  exit 0
fi

BODY_FILE=$(mktemp); RESP_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE" "$RESP_FILE"' EXIT

for TO in $(printf '%s' "${NOTIFY_TO}" | tr ',; ' '\n' | sed '/^$/d'); do
  TO="$TO" python3 -c 'import json,os; print(json.dumps({"from": os.environ.get("NOTIFY_FROM", ""), "fromName": os.environ.get("NOTIFY_FROM_NAME", ""), "to": os.environ["TO"], "title": os.environ.get("TITLE", ""), "msg": os.environ.get("MSG", ""), "type": "text"}, ensure_ascii=False))' \
    > "$BODY_FILE"
  CODE=$(curl -sS -o "$RESP_FILE" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer ${NOTIFY_TOKEN}" \
    -H "Content-Type: application/json" \
    -d @"$BODY_FILE" "$NOTIFY_URL" || echo "curl-failed")
  case "$CODE" in
    2*) echo "已发邮件通知 → ${TO}（HTTP ${CODE}）" ;;
    *)  echo "::warning::邮件通知失败 → ${TO}（HTTP ${CODE}）：$(head -c 300 "$RESP_FILE" 2>/dev/null)" ;;
  esac
done
exit 0
