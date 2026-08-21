#!/usr/bin/env bash
#
# Channex staging 실측 스크립트 — docs/experiments/channex-unknowns.md 의 U1~U3
#
# 공개 문서에 없는 3가지를 실측한다.
#   U1  GET /availability 와 규칙 갱신이 ARI 리밋을 소비하는가
#   U2  같은 룸타입에 규칙이 여러 개 걸릴 때 공존 가능한가
#   U3  규칙의 날짜 granularity 와 갱신 비용
#
# 쓰기가 필요한 항목은 --confirm 없이는 요청 내용만 출력하고 멈춘다.
# 생성한 규칙 ID 는 .probe-created 에 기록되며 cleanup 이 지운다.

set -euo pipefail

BASE="${CHANNEX_BASE:-https://staging.channex.io}"
CREATED_FILE="${CREATED_FILE:-.probe-created}"

# 규칙 엔드포인트 경로는 공개 문서에서 요청 본문 키(channel_availability_rule)만
# 확인했다. 경로 자체는 미검증이므로 환경 변수로 덮을 수 있게 둔다.
RULES_PATH="${CHANNEX_RULES_PATH:-/api/v1/channel_availability_rules}"

CONFIRM=0
for arg in "$@"; do
  [ "$arg" = "--confirm" ] && CONFIRM=1
done
CMD="${1:-}"

die() { printf '%s\n' "$*" >&2; exit 1; }

require_env() {
  for name in "$@"; do
    eval "v=\${$name:-}"
    [ -n "$v" ] || die "환경 변수 $name 가 필요합니다. docs/experiments/channex-unknowns.md 참고"
  done
}

# $1=METHOD $2=PATH $3=BODY(옵션) → "<http_status> <body>"
call() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -o /tmp/channex-probe-body -w '%{http_code}'
              -X "$method" "$BASE$path"
              -H "user-api-key: $CHANNEX_API_KEY"
              -H 'Content-Type: application/json')
  [ -n "$body" ] && args+=(-d "$body")
  local code
  code="$(curl "${args[@]}")"
  printf '%s\n' "$code"
}

show_body() { head -c 600 /tmp/channex-probe-body; echo; }

guard_write() {
  if [ "$CONFIRM" -ne 1 ]; then
    echo
    echo "  쓰기 요청입니다. --confirm 없이는 실행하지 않습니다."
    echo "  대상: $BASE"
    exit 2
  fi
}

today_plus() { date -u -v+"$1"d +%Y-%m-%d 2>/dev/null || date -u -d "+$1 days" +%Y-%m-%d; }

# ---------------------------------------------------------------- U1 (읽기만)
u1_read() {
  require_env CHANNEX_API_KEY CHANNEX_PROPERTY_ID
  local from to path
  from="$(today_plus 1)"; to="$(today_plus 7)"
  path="/api/v1/availability?filter[property_id]=$CHANNEX_PROPERTY_ID"
  path="$path&filter[date][gte]=$from&filter[date][lte]=$to"

  echo "U1-read  GET /availability 를 15회 연속 호출합니다 (읽기만)"
  echo "         429 가 처음 나온 회차가 읽기 리밋입니다"
  echo
  local i code
  for i in $(seq 1 15); do
    code="$(call GET "$path")"
    printf '  %2d회  HTTP %s\n' "$i" "$code"
    if [ "$code" = "429" ]; then
      echo
      echo "  → $i 회에서 429. 읽기에 리밋이 있습니다."
      show_body
      return 0
    fi
  done
  echo
  echo "  → 15회까지 429 없음. GET 은 availability 리밋 밖일 가능성이 높습니다."
  echo "    확정하려면 u1-write 로 쓰기 예산 잠식 여부를 봐야 합니다."
}

# --------------------------------------------------------------- U1 (쓰기 포함)
u1_write() {
  require_env CHANNEX_API_KEY CHANNEX_PROPERTY_ID CHANNEX_ROOM_TYPE_ID
  local from to path body
  from="$(today_plus 1)"; to="$(today_plus 7)"
  path="/api/v1/availability?filter[property_id]=$CHANNEX_PROPERTY_ID"
  path="$path&filter[date][gte]=$from&filter[date][lte]=$to"
  body="$(printf '{"values":[{"property_id":"%s","room_type_id":"%s","date":"%s","availability":1}]}' \
          "$CHANNEX_PROPERTY_ID" "$CHANNEX_ROOM_TYPE_ID" "$from")"

  echo "U1-write GET 10회로 예산을 소진시킨 뒤 POST /availability 1회를 시도합니다"
  echo "         POST 가 429 → 읽기와 쓰기가 같은 예산을 공유합니다"
  echo
  echo "  POST 본문: $body"
  guard_write

  local i code
  for i in $(seq 1 10); do
    code="$(call GET "$path")"
    printf '  GET %2d회  HTTP %s\n' "$i" "$code"
  done
  code="$(call POST "/api/v1/availability" "$body")"
  echo
  echo "  POST /availability  HTTP $code"
  show_body
  case "$code" in
    429) echo "  → 읽기가 쓰기 예산을 잠식합니다. 04-capacity-and-limits.md 예산 표를 개정하십시오." ;;
    2*)  echo "  → 읽기는 별도 예산입니다. drift 검출 주기를 자유롭게 정할 수 있습니다." ;;
    *)   echo "  → 예상 밖 응답입니다. 본문을 확인하십시오." ;;
  esac
}

# ------------------------------------------------------------------------- U2
rule_body() { # $1=title $2=type $3=value(옵션)
  local extra=""
  [ -n "${3:-}" ] && extra=",\"value\":$3"
  printf '{"channel_availability_rule":{"title":"%s","type":"%s"%s,
"affected_channels":["%s"],"affected_room_types":["%s"],
"days":["mo","tu","we","th","fr","sa","su"],
"start_date":"%s","end_date":"%s","property_id":"%s"}}' \
    "$1" "$2" "$extra" "$CHANNEX_CHANNEL_ID" "$CHANNEX_ROOM_TYPE_ID" \
    "$(today_plus 1)" "$(today_plus 7)" "$CHANNEX_PROPERTY_ID" | tr -d '\n'
}

record_created() {
  local id
  id="$(sed -n 's/.*"id":"\([0-9a-f-]\{36\}\)".*/\1/p' /tmp/channex-probe-body | head -1)"
  if [ -n "$id" ]; then
    echo "$id" >> "$CREATED_FILE"
    echo "  기록: $id → $CREATED_FILE"
  else
    echo "  경고: 응답에서 id 를 찾지 못했습니다. 수동 정리가 필요할 수 있습니다."
  fi
}

u2() {
  require_env CHANNEX_API_KEY CHANNEX_PROPERTY_ID CHANNEX_ROOM_TYPE_ID CHANNEX_CHANNEL_ID
  echo "U2  같은 (룸타입, 채널) 에 offset 과 max 를 동시에 걸어 공존 여부를 봅니다"
  echo
  echo "  경로: $RULES_PATH   (공개 문서 미검증. CHANNEX_RULES_PATH 로 덮을 수 있습니다)"
  echo "  요청 1: $(rule_body 'probe-offset' 'availability_offset' 2)"
  echo "  요청 2: $(rule_body 'probe-max' 'max_availability' 5)"
  guard_write

  local code
  code="$(call POST "$RULES_PATH" "$(rule_body 'probe-offset' 'availability_offset' 2)")"
  echo "  offset 규칙 생성  HTTP $code"; show_body
  [ "${code:0:1}" = "2" ] && record_created

  code="$(call POST "$RULES_PATH" "$(rule_body 'probe-max' 'max_availability' 5)")"
  echo "  max 규칙 생성     HTTP $code"; show_body
  [ "${code:0:1}" = "2" ] && record_created

  echo
  case "$code" in
    2*) echo "  → 공존 가능. 우선순위는 관측되지 않으므로 Channex 지원에 문의해 확정하십시오." ;;
    4*) echo "  → 두 번째 규칙이 거부됐습니다. 캡형과 보장형은 상호 배타입니다." ;;
    *)  echo "  → 예상 밖 응답입니다." ;;
  esac
  echo "  정리: ./scripts/channex-probe.sh cleanup --confirm"
}

# ------------------------------------------------------------------------- U3
u3() {
  require_env CHANNEX_API_KEY CHANNEX_PROPERTY_ID CHANNEX_ROOM_TYPE_ID CHANNEX_CHANNEL_ID
  local d body
  d="$(today_plus 1)"
  body="$(printf '{"channel_availability_rule":{"title":"probe-single-day","type":"availability_offset","value":1,
"affected_channels":["%s"],"affected_room_types":["%s"],
"days":["mo","tu","we","th","fr","sa","su"],
"start_date":"%s","end_date":"%s","property_id":"%s"}}' \
    "$CHANNEX_CHANNEL_ID" "$CHANNEX_ROOM_TYPE_ID" "$d" "$d" "$CHANNEX_PROPERTY_ID" | tr -d '\n')"

  echo "U3  단일 날짜(start_date == end_date) 규칙이 생성되는지 확인합니다"
  echo "    생성되면 날짜별 offset 은 날짜당 규칙 1개를 뜻하며,"
  echo "    30일 구간에서 최악 30개가 필요합니다 (연속 구간 병합으로 줄어듭니다)"
  echo
  echo "  요청: $body"
  guard_write

  local code
  code="$(call POST "$RULES_PATH" "$body")"
  echo "  HTTP $code"; show_body
  [ "${code:0:1}" = "2" ] && record_created
  echo
  case "$code" in
    2*) echo "  → 단일 날짜 규칙이 됩니다. 날짜당 규칙 1개. 병합 로직이 필요합니다." ;;
    4*) echo "  → 단일 날짜가 거부됐습니다. 최소 범위를 응답 본문에서 확인하십시오." ;;
  esac
  echo "  정리: ./scripts/channex-probe.sh cleanup --confirm"
}

# -------------------------------------------------------------------- cleanup
cleanup() {
  require_env CHANNEX_API_KEY
  [ -f "$CREATED_FILE" ] || { echo "정리할 것이 없습니다 ($CREATED_FILE 없음)"; return 0; }
  echo "cleanup  $CREATED_FILE 에 기록된 규칙을 삭제합니다"
  cat "$CREATED_FILE" | sed 's/^/  /'
  guard_write
  local id code
  while read -r id; do
    [ -n "$id" ] || continue
    code="$(call DELETE "$RULES_PATH/$id")"
    printf '  DELETE %s  HTTP %s\n' "$id" "$code"
  done < "$CREATED_FILE"
  rm -f "$CREATED_FILE"
  echo "  완료. $CREATED_FILE 를 삭제했습니다."
}

case "$CMD" in
  u1-read)  u1_read ;;
  u1-write) u1_write ;;
  u2)       u2 ;;
  u3)       u3 ;;
  cleanup)  cleanup ;;
  *)
    cat <<USAGE
사용법: $0 <명령> [--confirm]

  u1-read    GET /availability 15회. 읽기만 하므로 안전합니다
  u1-write   GET 10회로 예산 소진 후 POST 1회. 쓰기 포함
  u2         offset + max 규칙 공존 여부. 쓰기 포함
  u3         단일 날짜 규칙 생성 가능 여부. 쓰기 포함
  cleanup    생성한 규칙 삭제

필요한 환경 변수:
  CHANNEX_API_KEY        (필수)
  CHANNEX_PROPERTY_ID    (필수)
  CHANNEX_ROOM_TYPE_ID   (u1-write, u2, u3)
  CHANNEX_CHANNEL_ID     (u2, u3)

선택:
  CHANNEX_BASE           기본 https://staging.channex.io
  CHANNEX_RULES_PATH     기본 /api/v1/channel_availability_rules (경로 미검증)

배경과 판정 기준: docs/experiments/channex-unknowns.md
USAGE
    exit 1 ;;
esac
