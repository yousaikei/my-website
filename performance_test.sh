#!/bin/bash

# --- 設定 ---
REACTIVE_URL="http://localhost:8080/hello/composed-greeting"
UNI_BLOCKING_URL="http://localhost:8080/hello/composed-greeting-uni-blocking"
# BLOCKING_URL="http://localhost:8080/hello/composed-greeting-blocking"
REQUESTS=30
CURL_OUTPUT_FILE="/tmp/curl_results.txt"

# 前回の出力ファイルをクリア
> "$CURL_OUTPUT_FILE"

# --- curl出力用のフォーマット ---
CURL_FORMAT="\nURL: %{url_effective}\nTotal Time: %{time_total}s\nTime to First Byte: %{time_starttransfer}s\n--------------------\n"

# --- テスト関数 ---
run_test() {
  local url=$1
  local name=$2
  
  echo "--- テスト開始: $name ($url) ---"
  
  # 開始時間を記録
  start_time=$(date +%s.%N)
  
  # リクエストを並列実行
  for i in $(seq 1 $REQUESTS); do
    curl -s -w "$CURL_FORMAT" -o /dev/null "$url" >> "$CURL_OUTPUT_FILE" &
  done
  
  # すべてのバックグラウンドタスクが完了するのを待つ
  wait
  
  # 終了時間を記録
  end_time=$(date +%s.%N)
  
  # 合計所要時間を計算
  duration=$(echo "$end_time - $start_time" | bc)
  
  echo "--- $name テスト完了 ---"
  echo "$REQUESTS 個の並行リクエストの完了にかかった合計時間: $duration 秒"
  echo ""
}

# --- テスト実行 ---
echo "並行テストを実行中 ($REQUESTS 個の並列リクエスト、スレッドプール=4)..."
echo ""

run_test "$REACTIVE_URL" "【最優】リアクティブエンドポイント (Reactive)"
run_test "$UNI_BLOCKING_URL" "【中間】Uni + @Blocking エンドポイント"
run_test "$BLOCKING_URL" "【最悪】ブロッキングエンドポイント (Blocking with await)"

echo "すべてのテストが完了しました。"
echo "各リクエストの詳細なタイミングデータは次の場所に保存されます: $CURL_OUTPUT_FILE"
cat "$CURL_OUTPUT_FILE"
