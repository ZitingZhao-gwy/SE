#!/bin/bash
# 自动交易模拟脚本 — 10账户 × 10股票 持续买卖
# 每1~3秒随机发起一笔交易，买单卖单交替，价格在昨收附近浮动，确保撮合成交

TRADE_API="http://localhost:8090/api/client/orders"
STOCKS=("000001" "000002" "000858" "600000" "600016" "600036" "600519" "600900" "601398" "601988")
ACCOUNTS=("2026000000000001" "2026000000000002" "2026000000000003" "2026000000000004" "2026000000000005" "2026000000000006" "2026000000000007" "2026000000000008" "2026000000000009" "2026000000000010")

# 昨天收盘价（参考值，实际价格在附近浮动）
declare -A BASE_PRICE
BASE_PRICE[000001]=12.30
BASE_PRICE[000002]=8.75
BASE_PRICE[000858]=168.50
BASE_PRICE[600000]=7.85
BASE_PRICE[600016]=3.92
BASE_PRICE[600036]=35.50
BASE_PRICE[600519]=1662.00
BASE_PRICE[600900]=25.60
BASE_PRICE[601398]=5.82
BASE_PRICE[601988]=4.15

echo "========================================"
echo "  自动交易模拟器启动"
echo "  账户: ${#ACCOUNTS[@]}个 | 股票: ${#STOCKS[@]}只"
echo "  频率: 每1~3秒一笔 | 按Ctrl+C停止"
echo "  等待Kafka就绪(60s)..."
echo "========================================"
sleep 60

round=0
buy_count=0
sell_count=0

while true; do
  round=$((round + 1))

  # 随机选账户和股票
  ACC=${ACCOUNTS[$((RANDOM % ${#ACCOUNTS[@]}))]}
  SEC="3026${ACC:4}"  # 302600000000000X
  STOCK=${STOCKS[$((RANDOM % ${#STOCKS[@]}))]}
  BASE=${BASE_PRICE[$STOCK]}

  # 随机买卖方向（50/50）
  if [ $((RANDOM % 2)) -eq 0 ]; then
    SIDE="BUY"
    buy_count=$((buy_count + 1))
    # 买单价格在昨收 -2% ~ +1% 浮动
    PCT=$(awk -v r=$RANDOM 'BEGIN { printf "%.4f", -0.02 + r/32767 * 0.03 }')
  else
    SIDE="SELL"
    sell_count=$((sell_count + 1))
    # 卖单价格在昨收 -1% ~ +2% 浮动
    PCT=$(awk -v r=$RANDOM 'BEGIN { printf "%.4f", -0.01 + r/32767 * 0.03 }')
  fi

  PRICE=$(awk -v b=$BASE -v p=$PCT 'BEGIN { printf "%.2f", b * (1 + p) }')
  QTY=$(( (RANDOM % 50 + 1) * 100 ))  # 100~5000股，整百

  # 提交订单
  RESULT=$(curl -s -m 3 -X POST "$TRADE_API" \
    -H "Content-Type: application/json" \
    -d "{
      \"fundAccountNo\":\"$ACC\",
      \"securityAccountNo\":\"$SEC\",
      \"stockCode\":\"$STOCK\",
      \"orderSide\":\"$SIDE\",
      \"orderPrice\":$PRICE,
      \"orderQuantity\":$QTY
    }" 2>&1)

  ORDER_ID=$(echo "$RESULT" | grep -o '"localOrderId":[0-9]*' | grep -o '[0-9]*')
  if [ -n "$ORDER_ID" ]; then
    echo "[#$round] $SIDE $STOCK ${PRICE}×${QTY} | 账户:${ACC:12:4} | 订单ID:$ORDER_ID"
  else
    echo "[#$round] $SIDE $STOCK FAILED: $RESULT"
  fi

  # 随机间隔 1~3 秒
  SLEEP=$(awk -v r=$RANDOM 'BEGIN { printf "%.1f", 1 + r/32767 * 2 }')
  sleep "$SLEEP"
done
