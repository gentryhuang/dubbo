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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 在 2.7.x 中引入了 AsyncRpcResult 来替换 RpcResult ，而 RpcResult 被替换成了 AppResponse 。
 * <ul>
 *     <li>AsyncRpcResult是在调用链中实际传递的对象</li>
 *     <li>appsponse仅仅表示业务结果</li>
 * </ul>
 * <p>
 *  The relationship between them can be described as follow, an abstraction of the definition of AsyncRpcResult:
 *  <pre>
 *  {@code
 *   Public class AsyncRpcResult implements CompletionStage<AppResponse> {
 *       ......
 *  }
 * </pre>
 * AsyncRpcResult 表示的是一个异步的，未完成的RPC调用，而 AppResponse 是该调用的实际返回类型。理论上 AppResponse 不需要实现 Result 接口，这样做是为了兼容。
 */
// AppResponse 表示的是服务端返回的具体响应，其子类是 DecodeableRpcResult 。
public class AppResponse implements Result {
    private static final long serialVersionUID = -6925924956850004727L;

    // 响应结果
    private Object result;

    // 返回的异常信息
    private Throwable exception;

    // 返回的附加信息
    private Map<String, Object> attachments = new HashMap<>();

    public AppResponse() {
    }

    public AppResponse(Object result) {
        this.result = result;
    }

    public AppResponse(Throwable exception) {
        this.exception = exception;
    }

    @Override
    public Object recreate() throws Throwable {
        // 1 异常处理
        if (exception != null) {
            // fix issue#619
            try {
                // get Throwable class
                Class clazz = exception.getClass();
                while (!clazz.getName().equals(Throwable.class.getName())) {
                    clazz = clazz.getSuperclass();
                }
                // get stackTrace value
                Field stackTraceField = clazz.getDeclaredField("stackTrace");
                stackTraceField.setAccessible(true);
                Object stackTrace = stackTraceField.get(exception);
                if (stackTrace == null) {
                    exception.setStackTrace(new StackTraceElement[0]);
                }
            } catch (Exception e) {
                // ignore
            }
            throw exception;
        }

        // 2 结果
        return result;
    }

    @Override
    public Object getValue() {
        return result;
    }

    @Override
    public void setValue(Object value) {
        this.result = value;
    }

    @Override
    public Throwable getException() {
        return exception;
    }

    @Override
    public void setException(Throwable e) {
        this.exception = e;
    }

    @Override
    public boolean hasException() {
        return exception != null;
    }

    @Override
    @Deprecated
    public Map<String, String> getAttachments() {
        return new AttachmentsAdapter.ObjectToStringMap(attachments);
    }

    @Override
    public Map<String, Object> getObjectAttachments() {
        return attachments;
    }

    /**
     * Append all items from the map into the attachment, if map is empty then nothing happens
     *
     * @param map contains all key-value pairs to append
     */
    public void setAttachments(Map<String, String> map) {
        this.attachments = map == null ? new HashMap<>() : new HashMap<>(map);
    }

    @Override
    public void setObjectAttachments(Map<String, Object> map) {
        this.attachments = map == null ? new HashMap<>() : map;
    }

    public void addAttachments(Map<String, String> map) {
        if (map == null) {
            return;
        }
        if (this.attachments == null) {
            this.attachments = new HashMap<>();
        }
        this.attachments.putAll(map);
    }

    @Override
    public void addObjectAttachments(Map<String, Object> map) {
        if (map == null) {
            return;
        }
        if (this.attachments == null) {
            this.attachments = new HashMap<>();
        }
        this.attachments.putAll(map);
    }

    @Override
    public String getAttachment(String key) {
        Object value = attachments.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    @Override
    public Object getObjectAttachment(String key) {
        return attachments.get(key);
    }

    @Override
    public String getAttachment(String key, String defaultValue) {
        Object result = attachments.get(key);
        if (result == null) {
            return defaultValue;
        }
        if (result instanceof String) {
            return (String) result;
        }
        return defaultValue;
    }

    @Override
    public Object getObjectAttachment(String key, Object defaultValue) {
        Object result = attachments.get(key);
        if (result == null) {
            result = defaultValue;
        }
        return result;
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
        attachments.put(key, value);
    }

    @Override
    public Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn) {
        throw new UnsupportedOperationException("AppResponse represents an concrete business response, there will be no status changes, you should get internal values directly.");
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<Result, ? extends U> fn) {
        throw new UnsupportedOperationException("AppResponse represents an concrete business response, there will be no status changes, you should get internal values directly.");
    }

    @Override
    public Result get() throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException("AppResponse represents an concrete business response, there will be no status changes, you should get internal values directly.");
    }

    @Override
    public Result get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        throw new UnsupportedOperationException("AppResponse represents an concrete business response, there will be no status changes, you should get internal values directly.");
    }

    public void clear() {
        this.result = null;
        this.exception = null;
        this.attachments.clear();
    }

    @Override
    public String toString() {
        return "AppResponse [value=" + result + ", exception=" + exception + "]";
    }
}
