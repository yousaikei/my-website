# Quarkusリアクティブ対ブロッキングプログラミングの詳細な探求 - 総括レポート

このドキュメントは、Quarkusにおけるリアクティブおよびブロッキングプログラミングモデルの挙動を探求し理解するために行われた一連の実験、発見、そして最終的な結論を記録したものです。

---

## 実験環境

*   **プロジェクト**: KotlinベースのQuarkusプロジェクト。
*   **主要なテストコード**: `quarkus-blocking-test/src/main/kotlin/org/acme/BlockingResource.kt`
*   **設定ファイル**: `quarkus-blocking-test/src/main/resources/application.properties`
*   **性能テストスクリプト**: `performance_test.sh`

---

## 探求の道のりと発見

私たちの探求は、「Quarkusでブロッキングコードを正しく扱うにはどうすればよいか？」という単純な問いから始まりました。一連の段階的な実験を通じて、私たちは深い結論に達しました。

### 実験一：Quarkusの「スマートな」救済措置

*   **現象**: `String`を返し、`Thread.sleep()`を含むRESTエンドポイントが、`@Blocking`アノテーションなしでI/Oスレッドをブロックしなかった。
*   **発見**: RESTEasy Reactiveフレームワークは、開発者の初歩的なミスを防ぐため、このような単純な同期JAX-RSメソッドを自動的にワーカースレッドにオフロードする「スマートさ」を持っている。
*   **結論**: これは「暗黙のルール」による保護メカニズムであり、依存すべきではありません。コードの明確さ、堅牢性、保守性を保証するため、既知のブロッキング操作はすべて`@Blocking`アノテーションを付けて**明示的に**マークしなければなりません。

### 実験二：リアクティブメソッドにおける「契約」違反

*   **現象**: `Uni`を返すRESTエンドポイントが、その処理チェーン内で直接`Thread.sleep()`を呼び出すと、即座にI/Oスレッドをブロックし、Vert.xの警告がトリガーされた。
*   **発見**: メソッドがリアクティブ型（例：`Uni`）を返す場合、開発者はフレームワークと「非ブロッキング」の契約を結んだことになります。フレームワークは開発者を信頼するため、I/Oスread上でそのメソッドを実行します。その内部でブロッキングコードを実行すると、契約違反となります。
*   **結論**: リアクティブメソッド内では、コードパスの非ブロッキング性を保証する必要があります。ブロッキング操作を実行する必要がある場合は、`@Blocking`を使用するか、手動でタスクをワーカースレッドに切り替える必要があります。

### 実験三：gRPCサービスにおける暗黙的なブロッキング

*   **現象**: 実際のgRPCサービスの分析中に、データベース操作に起因する`Thread blocked`警告が発見された。
*   **発見**: 問題は、gRPCのI/Oスレッド上で`CompletableFuture.join()`を呼び出したことに起因します。gRPCサービスはデフォルトでI/Oスレッドで実行され、`.join()`の待機動作がスレッド全体をブロックしていました。
*   **結論**: gRPCのような非同期フレームワークのメソッド実装は、非ブロッキングのパラダイムに従う必要があります。`.join()`のようなブロッキング呼び出しの代わりに、リアクティブチェーン（例：`Uni.onItem().transformTo(...)`）を使用して非同期フローを編成すべきです。

### 実験四：並行性能比較（低負荷時）

*   **設定**: 5および30の並行リクエスト、Quarkusのデフォルトスレッドプール設定を使用。
*   **現象**: 最適化されたリアクティブエンドポイントと純粋なブロッキングエンドポイントの合計所要時間がほぼ**同じ**だった。
*   **発見**: 並行リクエスト数がワーカースレッドプールのサイズを下回る場合、リソース競合は発生しません。各リクエストが独立したスレッドを取得できるため、リアクティブモデルの利点であるリソースの効率的な再利用が表面化しません。

### 実験五：並行性能比較（高負荷時＆デッドロック）

*   **設定**: ワーカースレッドプールのサイズを意図的に4に制限し（`max-threads=4`）、5つの並行リクエストを送信。
*   **現象**: 
    1.  **リアクティブエンドポイント**: 性能はわずかに低下しましたが、すべてのリクエストを正常に処理しました（合計所要時間約5秒）。システムは安定していました。
    2.  **ブロッキングエンドポイント**: **壊滅的なデッドロックが発生**し、サービスは完全に停止し、応答不能になりました。
*   **発見**: これは今回の探求で**最も価値のある発見**です。ブロッキングエンドポイントは、そのコード内で`.await()`（`Uni`をブロッキングして待つ）と、その`Uni`内部の非同期操作`delayIt()`を混合していたため、ワーカースレッドが枯渇した際に致命的な依存関係のループを引き起こし、デッドロックに至りました。
*   **結論**: 不適切なブロッキングプログラミングは、リソースが制限された状況下で性能を低下させるだけでなく、アプリケーション全体をクラッシュさせる可能性があることを証明しました。一方、設計の優れたリアクティブコードは高負荷下でも安定性と効率を維持できます。

---

## 最終的な核心結論

1.  **明確な契約**: `@Blocking`はブロッキングコードを扱うための明確な契約であり、ベストプラクティスです。フレームワークの「スマートな」救済措置に依存すべきではありません。
2.  **組み合わせの威力**: `Uni`を返す真の価値は、複数の非同期またはブロッキング操作を**組み合わせ（Compose）**、統一された効率的な非ブロッキングのワークフローを構築する能力にあります。
3.  **負荷が真価を問う**: リアクティブプログラミングの優位性（高スループット、高リソース利用率、高安定性）は、**リソースが制限された高並行シナリオ**でのみ完全に現れます。低負荷時には、その性能はブロッキングコードと類似する場合があります。
4.  **デッドロックの警告**: ブロッキングコンテキスト内で非同期操作の結果を強制的に待機する（例：`.await()`, `.join()`）のは非常に危険なパターンであり、高並行下ではデッドロックを容易に引き起こすため、絶対に避けるべきです。

---

## 付録：最終的なコード

### `application.properties`

```properties
quarkus.http.host=0.0.0.0
quarkus.thread-pool.max-threads=4
```

### `performance_test.sh`

```bash
#!/bin/bash

# --- 設定 ---
REACTIVE_URL="http://localhost:8080/hello/composed-greeting"
BLOCKING_URL="http://localhost:8080/hello/composed-greeting-blocking"
REQUESTS=5
CURL_OUTPUT_FILE="/tmp/curl_results.txt"

# 前回の出力ファイルをクリア
> "$CURL_OUTPUT_FILE"

# --- curl出力用のフォーマット ---
# url_effective: 最終的なURL
# time_total: 合計時間
# time_starttransfer: 最初の1バイトを受け取るまでの時間
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
    # -s サイレントモード, -w フォーマット出力を書き込む, -o /dev/null レスポンスボディを破棄
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
echo "並行テストを実行中 ($REQUESTS 個の並列リクエスト)..."
echo ""

run_test "$REACTIVE_URL" "リアクティブエンドポイント (Reactive)"
run_test "$BLOCKING_URL" "ブロッキングエンドポイント (Blocking)"

echo "すべてのテストが完了しました。"
echo "各リクエストの詳細なタイミングデータは次の場所に保存されます: $CURL_OUTPUT_FILE"
cat "$CURL_OUTPUT_FILE"

```

### `BlockingResource.kt`

```kotlin
package org.acme

import io.smallrye.common.annotation.Blocking
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.infrastructure.Infrastructure
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jboss.logging.Logger
import java.time.Duration

@Path("/hello")
class BlockingResource {

    private val log: Logger = Logger.getLogger(BlockingResource::class.java)

    /**
     * Uniの利点を具体的に示す例：ブロッキング呼び出しと非同期呼び出しを組み合わせる
     */
    @GET
    @Path("/composed-greeting") // パスが変更されました
    @Produces(MediaType.TEXT_PLAIN)
    fun getComposedGreeting(): Uni<String> {
        log.infof("--> Endpoint method entered on thread: %s", Thread.currentThread().name)

        return Uni.createFrom().emitter { emitter ->
            // 手動でブロッキングタスクをワーカースレッドに投入する
            Infrastructure.getDefaultWorkerPool().execute {
                try {
                    val username = getBlockingUsername()
                    // ブロッキングタスクが完了したら、同じワーカースレッドで非同期メソッドの呼び出しを続ける
                    log.infof("--> Composing with reactive call on thread: %s", Thread.currentThread().name)
                    getReactiveGreeting(username)
                        .subscribe().with(
                            { greeting -> emitter.complete(greeting) }, // 成功時、手動でemitterを完了させる
                            { error -> emitter.fail(error) }             // 失敗時、手動でemitterを失敗させる
                        )
                } catch (e: Exception) {
                    emitter.fail(e)
                }
            }
        }
    }

    /**
     * 補助メソッド1：2秒間を要するブロッキングDB/HTTP呼び出しをシミュレートする
     */
    private fun getBlockingUsername(): String {
        log.infof("-----> 1. Executing BLOCKING task on thread: %s", Thread.currentThread().name)
        Thread.sleep(2000)
        log.infof("-----> 1. BLOCKING task finished on thread: %s", Thread.currentThread().name)
        return "Alice"
    }

    /**
     * 補助メソッド2：1秒間を要する非ブロッキング非同期呼び出しをシミュレートする
     */
    private fun getReactiveGreeting(name: String): Uni<String> {
        log.infof("-----> 2. Executing NON-BLOCKING task on thread: %s", Thread.currentThread().name)
        // .delayIt() はスレッドをブロックせずに非同期に遅延させる
        return Uni.createFrom().item("Hello, $name! Welcome back.")
            .onItem().delayIt().by(Duration.ofSeconds(1))
    }

    /**
     * 比較用バージョン：@Blockingアノテーションを使用した純粋なブロッキングスタイル、効率は低い
     */
    @GET
    @Path("/composed-greeting-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking // メソッド全体が同期的かつブロッキングであるため、@Blockingが必須
    fun getComposedGreetingBlocking(): String {
        log.infof("--> BLOCKING endpoint method entered on thread: %s", Thread.currentThread().name)

        // 1. ブロッキングメソッドを直接呼び出す（ワーカースレッド上なので安全）
        val username = getBlockingUsername()

        // 2. 非同期メソッドを呼び出すが、その結果をブロッキングして待機せざるを得ない。リアクティブプログラミングの利点はここで失われる
        log.infof("--> BLOCKING endpoint is now waiting for the reactive greeting... on thread: %s", Thread.currentThread().name)
        val greeting = getReactiveGreeting(username)
            .await().indefinitely() // Uniが完了するまでワーカースレッドを強制的にブロックする

        log.infof("--> BLOCKING endpoint finished on thread: %s", Thread.currentThread().name)
        return greeting
    }


    // --- 以下は以前の他の例、変更なし ---

    @GET
    @Path("/wrong-uni-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    fun wrongUniBlocking(): Uni<String> {
        return Uni.createFrom().item { 
            log.infof("Executing WRONG blocking task on thread: %s", Thread.currentThread().name)
            Thread.sleep(10000) // 危険！I/Oスレッドをブロックします
            "Hello from the WRONG Uni + blocking example"
        }
    }

    @GET
    @Path("/simple-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking
    fun simpleBlocking(): String {
        log.infof("Executing simple blocking task on thread: %s", Thread.currentThread().name)
        Thread.sleep(10000) // 安全！ワーカースレッド上でブロックします
        return "Hello from simple blocking API"
    }

    @GET
    @Path("/unannotated-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    fun unannotatedBlocking(): String {
        log.infof("Executing UNANNOTATED blocking task on thread: %s", Thread.currentThread().name)
        Thread.sleep(10000) // 極めて危険！I/Oスレッドをブロックします
        return "Hello from unannotated blocking API"
    }
}

