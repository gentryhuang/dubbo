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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Parameters;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * dubbo protocol support class.
 * 1 实现 ExchangeClient 接口，是 ExchangeClient 的装饰器，在原始的 ExchangeClient 对象基础上添加了引用计数功能，用于共享连接模式。
 * 2 其目的是在断开连接时使用，每断开一个连接该值就会减少1，为0时说明无连接，这时client就能够被安全销毁。
 */
@SuppressWarnings("deprecation")
final class ReferenceCountExchangeClient implements ExchangeClient {

    /**
     * URL
     */
    private final URL url;
    /**
     * 引用计数变量，用于记录 Client 被应用的次数。 每当该对象被引用一次refenceCount 都会进行自增。 每当close方法被调用时，referenceCount 就会进行自减
     */
    private final AtomicInteger refenceCount = new AtomicInteger(0);

    /**
     * 维护以及close掉的client，用于兜底
     * {@link Protocol#ghostClentMap} 一致
     */
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap;
    /**
     * 客户端 【类型是： HeaderExchangeClient】，被装饰对象
     */
    private ExchangeClient client;

    /**
     * 将HeaderExchangeClient 实例封装为ReferenceCountExchangeClient。
     *
     * @param client
     * @param ghostClientMap
     */
    public ReferenceCountExchangeClient(ExchangeClient client, ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap) {
        this.client = client;
        // 引用计数递增
        refenceCount.incrementAndGet();
        this.url = client.getUrl();
        if (ghostClientMap == null) {
            throw new IllegalStateException("ghostClientMap can not be null, url: " + url);
        }
        this.ghostClientMap = ghostClientMap;
    }

    // -------------------------------------------- 装饰器模式，每个实现方法都是调用client的对应的方法 -------------------------------------/

    @Override
    public void reset(URL url) {
        client.reset(url);
    }

    @Override
    public ResponseFuture request(Object request) throws RemotingException {
        return client.request(request);
    }

    @Override
    public URL getUrl() {
        return client.getUrl();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return client.getRemoteAddress();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return client.getChannelHandler();
    }

    @Override
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        return client.request(request, timeout);
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public void reconnect() throws RemotingException {
        client.reconnect();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return client.getLocalAddress();
    }

    @Override
    public boolean hasAttribute(String key) {
        return client.hasAttribute(key);
    }

    @Override
    public void reset(Parameters parameters) {
        client.reset(parameters);
    }

    @Override
    public void send(Object message) throws RemotingException {
        client.send(message);
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return client.getExchangeHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return client.getAttribute(key);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        client.send(message, sent);
    }

    @Override
    public void setAttribute(String key, Object value) {
        client.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        client.removeAttribute(key);
    }

    /**
     * close() is not idempotent any longer
     */
    @Override
    public void close() {
        close(0);
    }

    /**
     * 引用次数减少到0时 ，ExchangeClient 连接关闭
     *
     * @param timeout
     */
    @Override
    public void close(int timeout) {
        // 引用计数减一，若无指向，进行真正的关闭
        if (refenceCount.decrementAndGet() <= 0) {
            if (timeout == 0) {
                client.close();
            } else {
                client.close(timeout);
            }

            // 关闭ExchangeClient对象之后，会替换 client 为 LazyConnectExchangeClient 对象，即将关闭之后的连接变成一个懒加载的client
            client = replaceWithLazyClient();
        }
    }

    @Override
    public void startClose() {
        client.startClose();
    }

    // ghost client
    private LazyConnectExchangeClient replaceWithLazyClient() {

        // 在原有的URL之上，添加一些LazyConnectExchangeClient特有的参数
        URL lazyUrl = url.addParameter(Constants.LAZY_CONNECT_INITIAL_STATE_KEY, Boolean.FALSE)
                // 关闭重连
                .addParameter(Constants.RECONNECT_KEY, Boolean.FALSE)
                .addParameter(Constants.SEND_RECONNECT_KEY, Boolean.TRUE.toString())
                .addParameter("warning", Boolean.TRUE.toString())
                .addParameter(LazyConnectExchangeClient.REQUEST_WITH_WARNING_KEY, true)
                .addParameter("_client_memo", "referencecounthandler.replacewithlazyclient");

        // 从 ghostClientMap 缓存中查找
        String key = url.getAddress();
        // in worst case there's only one ghost connection.
        LazyConnectExchangeClient gclient = ghostClientMap.get(key);

        // 如果gclient 可用，则直接返回，否则重新创建一个
        if (gclient == null || gclient.isClosed()) {

            // ChannelHandler依旧使用原始ExchangeClient使用的Handler，即DubboProtocol中的requestHandler字段
            gclient = new LazyConnectExchangeClient(lazyUrl, client.getExchangeHandler());
            ghostClientMap.put(key, gclient);
        }
        return gclient;
    }

    @Override
    public boolean isClosed() {
        return client.isClosed();
    }

    /**
     * 该方法一般由外部调用，引用计数递增
     */
    public void incrementAndGetCount() {
        refenceCount.incrementAndGet();
    }
}
