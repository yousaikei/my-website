# Quarkus响应式与阻塞式编程深度探索 - 总结报告

本文档记录了一系列旨在探索和理解Quarkus中响应式与阻塞式编程模型的实验、发现与最终结论。

---

## 实验环境

*   **项目**: 一个基于Kotlin的Quarkus项目。
*   **核心测试代码**: `quarkus-blocking-test/src/main/kotlin/org/acme/BlockingResource.kt`
*   **配置文件**: `quarkus-blocking-test/src/main/resources/application.properties`
*   **性能测试脚本**: `performance_test.sh`

---

## 探索历程与发现

我们的探索始于一个简单的问题：如何在Quarkus中正确地处理阻塞代码？通过一系列循序渐进的实验，我们得出了深刻的结论。

### 实验一：Quarkus的“智能”补救

*   **现象**: 一个返回`String`且包含`Thread.sleep()`的REST端点，在没有`@Blocking`注解的情况下，并未阻塞I/O线程。
*   **发现**: RESTEasy Reactive框架为了防止开发者初级错误，会自动将这类简单的、同步的JAX-RS方法切换到工作线程执行。
*   **结论**: 这是一种“潜规则”的保护机制，不应依赖。为保证代码的清晰、健壮和可维护性，任何已知的阻塞操作都**必须**使用`@Blocking`注解来明确标记。

### 实验二：响应式方法中的“契约破坏”

*   **现象**: 一个返回`Uni`的REST端点，如果在其处理链中直接调用`Thread.sleep()`，会立刻阻塞I/O线程并触发Vert.x警告。
*   **发现**: 当方法返回响应式类型（如`Uni`）时，开发者就与框架签订了“非阻塞”的契约。框架信任开发者，因此在I/O线程上执行该方法。此时在内部执行阻塞代码，就破坏了契约。
*   **结论**: 在响应式方法中，必须保证代码路径的非阻塞性。如需执行阻塞操作，必须使用`@Blocking`或手动将任务切换到工作线程。

### 实验三：gRPC服务中的隐式阻塞

*   **现象**: 在分析一个真实的gRPC服务时，发现了由数据库操作引起的`Thread blocked`警告。
*   **发现**: 问题源于在gRPC的I/O线程上调用了`CompletableFuture.join()`。gRPC服务默认在I/O线程上执行，`.join()`的等待行为阻塞了整个线程。
*   **结论**: 异步框架（如gRPC）的方法实现必须遵循非阻塞范式。应使用响应式链（如`Uni.onItem().transformTo(...)`）来编排异步流程，彻底消灭`.join()`这类阻塞调用。

### 实验四：并发性能对比 (资源充裕)

*   **设置**: 5个和30个并发请求，使用Quarkus默认的线程池配置。
*   **现象**: 优化后的响应式端点与纯阻塞式端点的总耗时几乎**相同**。
*   **发现**: 在并发数低于工作线程池上限时，不会发生资源竞争。每个请求都能获得独立的线程，因此响应式模型在资源周转上的优势无法体现。

### 实验五：并发性能对比 (资源受限与死锁)

*   **设置**: 人为限制工作线程池大小（`max-threads=4`），发起5个并发请求。
*   **现象**: 
    1.  **响应式端点**: 性能符合预期，总耗时约5秒（`(5/4) * 2s_blocking + 1s_async`的粗略估算）。系统保持稳定。
    2.  **阻塞式端点**: **发生灾难性死锁**，服务完全卡住，无法响应。
*   **发现**: 这是本次探索**最有价值的发现**。阻塞式端点由于在代码中混合了`await()`（阻塞等待一个`Uni`）和该`Uni`内部的异步`delayIt()`操作，在工作线程被耗尽时，造成了致命的依赖循环，导致死锁。
*   **结论**: 错误的阻塞/响应式混合编程，在资源受限时，其后果**不仅是性能下降，更可能是整个应用的崩溃**。设计良好的响应式代码则能在高压下保持稳定和高效。

---

## 最终核心结论

1.  **明确契约**: `@Blocking`是处理阻塞代码的明确契约和最佳实践，不应依赖框架的“智能补救”。
2.  **组合的威力**: 返回`Uni`的真正价值在于**组合（Compose）**多个异步或阻塞操作，构建出统一、高效、非阻塞的工作流。
3.  **压力见真章**: 响应式编程的优越性（高吞吐量、高资源利用率、高稳定性）只有在**资源受限的高并发场景**下才能完全展现。在低负载下，其性能表现可能与阻塞式代码相似。
4.  **死锁的警示**: 在阻塞的上下文中强行等待一个异步操作的结果（如`.await()`, `.join()`），是极其危险的模式，在高并发下极易导致死锁，必须绝对避免。

---

## 附录：最终代码

### `application.properties`

```properties
quarkus.http.host=0.0.0.0
quarkus.thread-pool.max-threads=4
```

### `performance_test.sh`

```bash
#!/bin/bash

# --- 配置 ---
REACTIVE_URL="http://localhost:8080/hello/composed-greeting"
BLOCKING_URL="http://localhost:8080/hello/composed-greeting-blocking"
REQUESTS=5
CURL_OUTPUT_FILE="/tmp/curl_results.txt"

# 清空上次的输出文件
> "$CURL_OUTPUT_FILE"

# --- 用于curl输出的格式 ---
# url_effective: 最终URL
# time_total: 总时间
# time_starttransfer: 到第一个字节的时间
CURL_FORMAT="\nURL: %{url_effective}\nTotal Time: %{time_total}s\nTime to First Byte: %{time_starttransfer}s\n--------------------\n"

# --- 测试函数 ---
run_test() {
  local url=$1
  local name=$2
  
  echo "--- 开始测试: $name ($url) ---"
  
  # 记录开始时间
  start_time=$(date +%s.%N)
  
  # 并发执行请求
  for i in $(seq 1 $REQUESTS); do
    # -s 静默模式, -w 写入格式化输出, -o /dev/null 抛弃响应体
    curl -s -w "$CURL_FORMAT" -o /dev/null "$url" >> "$CURL_OUTPUT_FILE" &
  done
  
  # 等待所有后台任务完成
  wait
  
  # 记录结束时间
  end_time=$(date +%s.%N)
  
  # 计算总耗时
  duration=$(echo "$end_time - $start_time" | bc)
  
  echo "--- $name 测试完成 ---"
  echo "完成 $REQUESTS 个并发请求的总耗时: $duration 秒"
  echo ""
}

# --- 执行测试 ---
echo "正在执行并发测试 ($REQUESTS 个并行请求)..."
echo ""

run_test "$REACTIVE_URL" "响应式端点 (Reactive)"
run_test "$BLOCKING_URL" "阻塞式端点 (Blocking)"

echo "所有测试完成。"
echo "每个请求的详细计时数据保存在: $CURL_OUTPUT_FILE"
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
     * 真正体现Uni优点的例子：组合一个阻塞调用和一个异步调用
     */
    @GET
    @Path("/composed-greeting") // 路径已更改
    @Produces(MediaType.TEXT_PLAIN)
    fun getComposedGreeting(): Uni<String> {
        log.infof("--> Endpoint method entered on thread: %s", Thread.currentThread().name)

        return Uni.createFrom().emitter { emitter ->
            // 手动将阻塞任务提交到工作线程
            Infrastructure.getDefaultWorkerPool().execute {
                try {
                    val username = getBlockingUsername()
                    // 当阻塞任务完成后，在同一个工作线程上继续调用异步方法
                    log.infof("--> Composing with reactive call on thread: %s", Thread.currentThread().name)
                    getReactiveGreeting(username)
                        .subscribe().with(
                            { greeting -> emitter.complete(greeting) }, // 成功时，手动完成emitter
                            { error -> emitter.fail(error) }             // 失败时，手动失败emitter
                        )
                } catch (e: Exception) {
                    emitter.fail(e)
                }
            }
        }
    }

    /**
     * 辅助方法1：模拟一个耗时2秒的阻塞数据库/HTTP调用
     */
    private fun getBlockingUsername(): String {
        log.infof("-----> 1. Executing BLOCKING task on thread: %s", Thread.currentThread().name)
        Thread.sleep(2000)
        log.infof("-----> 1. BLOCKING task finished on thread: %s", Thread.currentThread().name)
        return "Alice"
    }

    /**
     * 辅助方法2：模拟一个耗时1秒的非阻塞异步调用
     */
    private fun getReactiveGreeting(name: String): Uni<String> {
        log.infof("-----> 2. Executing NON-BLOCKING task on thread: %s", Thread.currentThread().name)
        // .delayIt() 会异步地延迟，而不会阻塞任何线程
        return Uni.createFrom().item("Hello, $name! Welcome back.")
            .onItem().delayIt().by(Duration.ofSeconds(1))
    }

    /**
     * 对比版本：使用@Blocking注解的纯阻塞风格，效率更低
     */
    @GET
    @Path("/composed-greeting-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking // 必须用@Blocking，因为整个方法是同步阻塞的
    fun getComposedGreetingBlocking(): String {
        log.infof("--> BLOCKING endpoint method entered on thread: %s", Thread.currentThread().name)

        // 1. 直接调用阻塞方法 (因为在工作线程上，所以是安全的)
        val username = getBlockingUsername()

        // 2. 调用异步方法，但被迫阻塞等待其结果，响应式编程的优势在此丧失
        log.infof("--> BLOCKING endpoint is now waiting for the reactive greeting... on thread: %s", Thread.currentThread().name)
        val greeting = getReactiveGreeting(username)
            .await().indefinitely() // 强制阻塞工作线程，直到Uni完成

        log.infof("--> BLOCKING endpoint finished on thread: %s", Thread.currentThread().name)
        return greeting
    }


    // --- 以下是之前的其他例子，保持不变 ---

    @GET
    @Path("/wrong-uni-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    fun wrongUniBlocking(): Uni<String> {
        return Uni.createFrom().item { 
            log.infof("Executing WRONG blocking task on thread: %s", Thread.currentThread().name)
            Thread.sleep(10000) // 危险！这里会阻塞I/O线程
            "Hello from the WRONG Uni + blocking example"
        }
    }

    @GET
    @Path("/simple-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking
    fun simpleBlocking(): String {
        log.infof("Executing simple blocking task on thread: %s", Thread.currentThread().name)
        Thread.sleep(10000) // 安全！这里在工作线程上阻塞
        return "Hello from simple blocking API"
    }

    @GET
    @Path("/unannotated-blocking")
    @Produces(MediaType.TEXT_PLAIN)
    fun unannotatedBlocking(): String {
        log.infof("Executing UNANNOTATED blocking task on thread: %s", Thread.currentThread().name)
        Thread.sleep(10000) // 极度危险！阻塞I/O线程
        return "Hello from unannotated blocking API"
    }
}
