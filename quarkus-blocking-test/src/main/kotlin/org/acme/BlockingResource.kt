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

    /**
     * 中間方案：Uni + @Blocking。安全だが効率は最適ではない
     */
    @GET
    @Path("/composed-greeting-uni-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking // メソッド全体をワーカースレッドで実行する
    fun getComposedGreetingUniWithBlocking(): Uni<String> {
        log.infof("--> Uni+@Blocking endpoint method entered on thread: %s", Thread.currentThread().name)

        // 1. ワーカースレッド上でブロッキングメソッドを直接呼び出す
        val username = getBlockingUsername()

        // 2. 非同期メソッドを呼び出し、Uniを直接返す（.await()しない！）
        return getReactiveGreeting(username)
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