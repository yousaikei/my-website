# gRPC服务重构指导手册 (@Blocking 方案)

## 1. 目标 (Objective)

本文档旨在指导开发者将项目中现存的、基于`.join()`阻塞模式的gRPC服务，重构为使用`@Blocking`注解的安全模式，以从根本上解决性能瓶颈和死锁问题。

---

## 2. 问题诊断 (Problem Diagnosis)

当前实现中，gRPC服务方法（默认在I/O线程上执行）内部通过`CompletableFuture.join()`来阻塞地等待数据库查询结果。这种模式在高并发下会阻塞关键的I/O线程，我们已经通过实验证明，在资源受限时，它会导致灾难性的**死锁**。

---

## 3. 重构策略 (`@Blocking` 注解模式)

本方案是解决此问题的最直接、最易于理解的方法。

*   **策略**: 在每一个需要执行阻塞操作的gRPC方法上，添加`@Blocking`注解。

*   **原理**: 该注解会告诉Quarkus框架，将整个方法的执行从敏感的I/O线程，切换到一个安全的工作线程（Worker Thread）上。

*   **优点**: 
    1.  **绝对安全**: I/O线程被完全保护，死锁风险被消除。
    2.  **代码直观**: 方法体内部可以继续使用传统的、从上到下的同步代码风格，无需复杂的响应式链，非常易于编写和维护。

---

## 4. 执行计划 (Execution Plan)

以`PurchaseService.kt`为例，对所有存在问题的gRPC服务（包括`OrderService.kt`）执行以下改造步骤。

### 步骤一: 为gRPC方法添加`@Blocking`注解

在`getProductDelivery`和`getProductWaste`等所有需要执行阻塞调用的方法上，添加`@io.smallrye.common.annotation.Blocking`注解。

### 步骤二: 修改方法体为同步风格

因为整个方法已经运行在安全的工作线程上，所以不再需要`CompletableFuture`和`.join()`。直接以同步方式调用阻塞方法。

### 步骤三 (关键): 正确处理`Uni`的返回结果

在方法的最后，当调用返回`Uni`的`handle`方法时，我们**必须**使用非阻塞的`.subscribe()`来处理结果。这与我们实验中导致死锁的`.await()`形成鲜明对比。

`.subscribe()`通过注册“成功时做什么”和“失败时做什么”的回调函数来处理未来才到达的结果，从而**立即释放当前的工作线程**，保证了系统的稳定和高效。

### 最终代码示例

用以下内容**完全替换**`PurchaseService.kt`文件的现有代码，即可完成该服务的改造。

```kotlin
package jp.trial.shinise.legacy.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import io.quarkus.grpc.GrpcService
import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking // 1. 确保引入此注解
import io.smallrye.config.common.utils.StringUtil.isNumeric
import jp.trial.protobuf.shukei.purchase.v1.*
import jp.trial.shinise.access.StoreAccess
import jp.trial.shinise.kakun.util.grpc.BaseGrpcService
import jp.trial.shinise.legacy.manager.ProductPurchaseHandler
import java.time.LocalDate
import java.time.format.DateTimeParseException

@GrpcService
class PurchaseService(
    private val productPurchaseHandler: ProductPurchaseHandler,
    private val baseGrpcService: BaseGrpcService,
    private val storeAccess: StoreAccess,
) : PurchaseServiceGrpc.PurchaseServiceImplBase() {

    @Override
    @Blocking // 2. 添加注解
    fun getProductDelivery(
        request: GetProductDeliveryRequest,
        responseObserver: StreamObserver<GetProductDeliveryResponse>,
    ) {
        if (request.storeCode !in 1..9999) {
            responseObserver.onError(Status.OUT_OF_RANGE.withDescription("`store_code` must be between 1 and 9999.").asRuntimeException())
            return
        }

        try {
            // 3. 直接以同步方式调用阻塞方法
            val dbIndex = storeAccess.findDbIndex(request.storeCode)

            if (dbIndex == null) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("`store_code` not found.").asRuntimeException())
                return
            }
            
            validateRequest(request.startDate, request.endDate, request.productCodeList, request.storeCode)
            
            // 4. (关键) 使用非阻塞的 .subscribe() 处理Uni结果
            baseGrpcService.handle(
                request.storeCode,
                productPurchaseHandler::productDeliveryHandle,
                request,
            ).subscribe().with(
                { response ->
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                },
                { error -> responseObserver.onError(error) },
            )

        } catch (ex: Exception) {
            // 5. 统一处理所有异常
            val statusException = when (ex) {
                is StatusException -> ex
                else -> Status.INTERNAL.withDescription("Unexpected error: ${ex.message}").asRuntimeException()
            }
            Log.error("Error in getProductDelivery: ${ex.message}", ex)
            responseObserver.onError(statusException)
        }
    }

    @Override
    @Blocking // 2. 添加注解
    fun getProductWaste(
        request: GetProductWasteRequest,
        responseObserver: StreamObserver<GetProductWasteResponse>,
    ) {
        if (request.storeCode !in 1..9999) {
            responseObserver.onError(Status.OUT_OF_RANGE.withDescription("`store_code` must be between 1 and 9999.").asRuntimeException())
            return
        }

        try {
            // 3. 直接以同步方式调用阻塞方法
            val dbIndex = storeAccess.findDbIndex(request.storeCode)

            if (dbIndex == null) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("`store_code` not found.").asRuntimeException())
                return
            }

            validateRequest(request.startDate, request.endDate, request.productCodeList, request.storeCode)

            // 4. (关键) 使用非阻塞的 .subscribe() 处理Uni结果
            baseGrpcService.handle(
                request.storeCode,
                productPurchaseHandler::productWasteHandle,
                request,
            ).subscribe().with(
                { response ->
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                },
                { error -> responseObserver.onError(error) },
            )

        } catch (ex: Exception) {
            // 5. 统一处理所有异常
            val statusException = when (ex) {
                is StatusException -> ex
                else -> Status.INTERNAL.withDescription("Unexpected error: ${ex.message}").asRuntimeException()
            }
            Log.error("Error in getProductWaste: ${ex.message}", ex)
            responseObserver.onError(statusException)
        }
    }

    // validateRequest 方法保持不变...
}
```

---

## 5. 验证

请对 `OrderService.kt` 执行同样的改造。重构完成后，重新编译并运行应用。之前发现的`Thread blocked`警告和死锁问题应该都已解决。应用在处理并发请求时，将表现出安全、稳定的特性。