/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.rpc.model.ConsumerMethodModel;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.apache.dubbo.common.utils.ReflectUtils.defaultReturn;

/**
 * 该类代表一个异步的，未完成的RPC调用，它会记录对应的 RPC 调用信息，如 RpcContext 和 Invocation，以便调用完成，结果返回时可以使用。
 * This class represents an unfinished RPC call, it will hold some context information for this call, for example RpcContext and Invocation,
 * so that when the call finishes and the result returns, it can guarantee all the contexts being recovered as the same as when the call was made
 * before any callback is invoked.
 * <p>
 * TODO if it's reasonable or even right to keep a reference to Invocation?
 * <p>
 * As {@link Result} implements CompletionStage, {@link AsyncRpcResult} allows you to easily build a async filter chain whose status will be
 * driven entirely by the state of the underlying RPC call.
 * <p>
 * AsyncRpcResult does not contain any concrete value (except the underlying value bring by CompletableFuture), consider it as a status transfer node.
 * {@link #getValue()} and {@link #getException()} are all inherited from {@link Result} interface, implementing them are mainly
 * for compatibility consideration. Because many legacy {@link Filter} implementation are most possibly to call getValue directly.
 */
// AsyncRpcResult 表示的是一个异步的，未完成的RPC调用，而 AppResponse 是该调用的实际返回类型。理论上 AppResponse 不需要实现 Result 接口，这样做是为了兼容。
public class AsyncRpcResult implements Result {
    private static final Logger logger = LoggerFactory.getLogger(AsyncRpcResult.class);

    // 当回调发生时，RpcContext 可能已经被更改。即执行 AsyncRpcResult 上添加的回调方法的线程可能先后处理过多个不同的 AsyncRpcResult 。
    // 因此，我们应该保留当前RpcContext实例的引用，并在回调执行之前恢复它。
    private RpcContext storedContext;
    private RpcContext storedServerContext;

    // 此次 RPC 调用关联的线程池
    private Executor executor;

    // 此次 RPC 调用关联的 Invocation 对象
    private Invocation invocation;

    // twoway 请求返回的对象
    private CompletableFuture<AppResponse> responseFuture;

    // 在构造方法中除了接收发送请求返回的 CompletableFuture<AppResponse> 对象，还会保存当前的 RPC 上下文
    public AsyncRpcResult(CompletableFuture<AppResponse> future, Invocation invocation) {
        this.responseFuture = future;
        this.invocation = invocation;
        this.storedContext = RpcContext.getContext();
        this.storedServerContext = RpcContext.getServerContext();
    }

    /**
     * Notice the return type of {@link #getValue} is the actual type of the RPC method, not {@link AppResponse}
     *
     * @return
     */
    @Override
    public Object getValue() {
        return getAppResponse().getValue();
    }

    @Override
    public Throwable getException() {
        return getAppResponse().getException();
    }

    public CompletableFuture<AppResponse> getResponseFuture() {
        return responseFuture;
    }

    public Result getAppResponse() {
        try {
            // 如果完成，则获取 AppResponse
            if (responseFuture.isDone()) {
                return responseFuture.get();
            }
        } catch (Exception e) {
            // This should not happen in normal request process;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.");
            throw new RpcException(e);
        }
        // 获取默认的 AppResponse
        return createDefaultValue(invocation);
    }

    /**
     * 该方法将始终在最大 timeout 等待之后返回：
     * 1. 如果value在超时前返回，则正常返回。
     * 2. 如果timeout之后没有返回值，则抛出TimeoutException。
     *
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Override
    public Result get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
            threadlessExecutor.waitAndDrain();
        }
        return responseFuture.get(timeout, unit);
    }

    @Override
    public Result get() throws InterruptedException, ExecutionException {
        // 针对 ThreadlessExecutor 的特殊处理，这里调用 waitAndDrain() 等待响应
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
            threadlessExecutor.waitAndDrain();
        }
        // 获取 AppResponse，非 ThreadlessExecutor 线程池场景中，直接调用 Future（Dubbo 协议时最底层是 DefaultFuture） 的 get() 方法阻塞。
        return responseFuture.get();
    }


    @Override
    public Object recreate() throws Throwable {
        RpcInvocation rpcInvocation = (RpcInvocation) invocation;
        //  1 如果是服务端的异步实现，则从上下文中取。
        // 为什么？ 因为接口返回的结果是 CompletableFuture,属于异步范畴（服务端的异步），和消费端异步类似。
        if (InvokeMode.FUTURE == rpcInvocation.getInvokeMode()) {
            return RpcContext.getContext().getFuture();
        }
        // 2 获取 AppResponse 中的结果
        return getAppResponse().recreate();
    }


    /**
     * CompletableFuture can only be completed once, so try to update the result of one completed CompletableFuture will
     * has no effect. To avoid this problem, we check the complete status of this future before update it's value.
     * <p>
     * But notice that trying to give an uncompleted CompletableFuture a new specified value may face a race condition,
     * because the background thread watching the real result will also change the status of this CompletableFuture.
     * The result is you may lose the value you expected to set.
     *
     * @param value
     */
    @Override
    public void setValue(Object value) {
        try {
            if (responseFuture.isDone()) {
                responseFuture.get().setValue(value);
            } else {
                AppResponse appResponse = new AppResponse();
                appResponse.setValue(value);
                responseFuture.complete(appResponse);
            }
        } catch (Exception e) {
            // This should not happen in normal request process;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.");
            throw new RpcException(e);
        }
    }


    @Override
    public void setException(Throwable t) {
        try {
            if (responseFuture.isDone()) {
                responseFuture.get().setException(t);
            } else {
                AppResponse appResponse = new AppResponse();
                appResponse.setException(t);
                responseFuture.complete(appResponse);
            }
        } catch (Exception e) {
            // This should not happen in normal request process;
            logger.error("Got exception when trying to fetch the underlying result from AsyncRpcResult.");
            throw new RpcException(e);
        }
    }

    @Override
    public boolean hasException() {
        return getAppResponse().hasException();
    }


    public void setResponseFuture(CompletableFuture<AppResponse> responseFuture) {
        this.responseFuture = responseFuture;
    }


    /**
     * 通过该方法可以为 AsyncRpcResult 添加回调方法，而回调方法会被包装一层并作为新的 responseFuture 。
     * <p>
     * 通过 beforeContext 和 afterContext ，AsyncRpcResult 就可以处于不断地添加回调而不丢失 RpcContext 的状态。AsyncRpcResult 整个就是为异步请求设计的。
     *
     * @param fn
     * @return
     */
    public Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn) {
        // 在responseFuture之上注册回调
        this.responseFuture = this.responseFuture.whenComplete((v, t) -> {
            // 将当前线程的 RpcContext 记录到临时属性中，然后将构造函数中存储的 RpcContext 设置到当前线程中，为后面的回调执行做准备
            beforeContext.accept(v, t);
            // 执行回调
            fn.accept(v, t);
            // 恢复线程原有的 RpcContext
            afterContext.accept(v, t);
        });
        return this;
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<Result, ? extends U> fn) {
        return this.responseFuture.thenApply(fn);
    }

    @Override
    @Deprecated
    public Map<String, String> getAttachments() {
        return getAppResponse().getAttachments();
    }

    @Override
    public Map<String, Object> getObjectAttachments() {
        return getAppResponse().getObjectAttachments();
    }

    @Override
    public void setAttachments(Map<String, String> map) {
        getAppResponse().setAttachments(map);
    }

    @Override
    public void setObjectAttachments(Map<String, Object> map) {
        getAppResponse().setObjectAttachments(map);
    }

    @Deprecated
    @Override
    public void addAttachments(Map<String, String> map) {
        getAppResponse().addAttachments(map);
    }

    @Override
    public void addObjectAttachments(Map<String, Object> map) {
        getAppResponse().addObjectAttachments(map);
    }

    @Override
    public String getAttachment(String key) {
        return getAppResponse().getAttachment(key);
    }

    @Override
    public Object getObjectAttachment(String key) {
        return getAppResponse().getObjectAttachment(key);
    }

    @Override
    public String getAttachment(String key, String defaultValue) {
        return getAppResponse().getAttachment(key, defaultValue);
    }

    @Override
    public Object getObjectAttachment(String key, Object defaultValue) {
        return getAppResponse().getObjectAttachment(key, defaultValue);
    }

    @Override
    public void setAttachment(String key, String value) {
        setObjectAttachment(key, value);
    }

    @Override
    public void setAttachment(String key, Object value) {
        setObjectAttachment(key, value);
    }

    @Override
    public void setObjectAttachment(String key, Object value) {
        getAppResponse().setAttachment(key, value);
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * tmp context to use when the thread switch to Dubbo thread.
     */
    private RpcContext tmpContext;
    private RpcContext tmpServerContext;

    private BiConsumer<Result, Throwable> beforeContext = (appResponse, t) -> {
        // 将当前线程的 RpcContext 记录到 tmpContext 中
        tmpContext = RpcContext.getContext();
        tmpServerContext = RpcContext.getServerContext();

        // 将构造函数中存储的 RpcContext (也就是创建 AsyncRpcResult 线程的 RpcContext) 设置到当前线程中
        RpcContext.restoreContext(storedContext);
        RpcContext.restoreServerContext(storedServerContext);
    };

    private BiConsumer<Result, Throwable> afterContext = (appResponse, t) -> {
        // 将当前线程的 RpcContext 恢复到原始值
        RpcContext.restoreContext(tmpContext);
        RpcContext.restoreServerContext(tmpServerContext);
    };

    /**
     * Some utility methods used to quickly generate default AsyncRpcResult instance.
     */
    public static AsyncRpcResult newDefaultAsyncResult(AppResponse appResponse, Invocation invocation) {
        return new AsyncRpcResult(CompletableFuture.completedFuture(appResponse), invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Invocation invocation) {
        return newDefaultAsyncResult(null, null, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Object value, Invocation invocation) {
        return newDefaultAsyncResult(value, null, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Throwable t, Invocation invocation) {
        return newDefaultAsyncResult(null, t, invocation);
    }

    public static AsyncRpcResult newDefaultAsyncResult(Object value, Throwable t, Invocation invocation) {
        CompletableFuture<AppResponse> future = new CompletableFuture<>();
        AppResponse result = new AppResponse();
        if (t != null) {
            result.setException(t);
        } else {
            result.setValue(value);
        }
        future.complete(result);
        return new AsyncRpcResult(future, invocation);
    }

    private static Result createDefaultValue(Invocation invocation) {
        ConsumerMethodModel method = (ConsumerMethodModel) invocation.get(Constants.METHOD_MODEL);
        return method != null ? new AppResponse(defaultReturn(method.getReturnClass())) : new AppResponse();
    }
}

