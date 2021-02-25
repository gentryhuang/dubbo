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
package com.alibaba.dubbo.remoting.exchange.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.remoting.exchange.support.header.HeaderExchangeHandler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 实现 ResponseFuture 接口，默认响应Future 实现类。同时，它也是所有DefaultFuture的管理容器，因为会把自身引用保存进来
 */
public class DefaultFuture implements ResponseFuture {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);

    /**
     * Request 编号到 Dubbo通道的映射
     * key: 请求编号
     * value: Dubbo 抽象的通道，用来发送请求
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<Long, Channel>();

    /**
     * Request 编号到 DefaultFuture 的映射
     * key: 请求编号id
     * value: DefaultFuture
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<Long, DefaultFuture>();

    /**
     * 启动扫描响应超时任务
     */
    static {
        Thread th = new Thread(new RemotingInvocationTimeoutScan(), "DubboResponseTimeoutScanTimer");
        th.setDaemon(true);
        th.start();
    }

    /**
     * 请求
     */
    private final Request request;
    /**
     * invoke id.  请求的编号
     */
    private final long id;
    /**
     * 发送请求的 Channel
     */
    private final Channel channel;
    /**
     * 请求-响应的超时时间
     */
    private final int timeout;
    /**
     * 当前 DefaultFuture 创建的开始时间
     */
    private final long start = System.currentTimeMillis();
    /**
     * 请求发送的时间
     */
    private volatile long sent;
    /**
     * 响应
     */
    private volatile Response response;
    /**
     * 锁
     */
    private final Lock lock = new ReentrantLock();
    /**
     * 锁的选择器
     */
    private final Condition done = lock.newCondition();
    /**
     * 回调，适用于异步请求
     *
     * @see com.alibaba.dubbo.rpc.protocol.dubbo.filter.FutureFilter
     */
    private volatile ResponseCallback callback;

    /**
     * 创建 DefaultFuture 时，会把创建的该实例放入 FUTURES 缓存中
     *
     * @param channel 发送请求的 Channel
     * @param request 请求
     * @param timeout 请求-响应的超时时间
     */
    public DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        // 设置请求id，这个id是request和response映射的依据，非常重要
        this.id = request.getId();
        // 设置超时时间，如果传入的 timeout > 0 就取传入的值，否则取 URL 中timeout的值，默认为 1000
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // Request 编号到 DefaultFuture 的映射，DefaultFuture 管理每个请求关联的DefaultFuture对象。
        FUTURES.put(id, this);
        // Request 编号到 Dubbo通道的映射
        CHANNELS.put(id, channel);
    }

    /**
     * 获取 request 关联的 DefaultFuture 对象
     *
     * @param id 请求id
     * @return
     */
    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    /**
     * 判断通道是否有未响应的请求
     *
     * @param channel 发送请求的 Channel
     * @return
     */
    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    /**
     * 发送请求更新 sent 属性。
     *
     * @param channel
     * @param request
     * @see HeaderExchangeHandler#sent(com.alibaba.dubbo.remoting.Channel, java.lang.Object)
     */
    public static void sent(Channel channel, Request request) {
        // 获取请求关联的 DefaultFuture 对象
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * 更新 sent 属性，记录请求发送时间戳
     */
    private void doSent() {
        sent = System.currentTimeMillis();
    }

    /**
     * 当 Channel 断开连接时，应该对其关联的 request 进行异常结果响应，结束阻塞等待的业务线程。
     *
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel) {
        for (long id : CHANNELS.keySet()) {
            // 取出断开连接的Channel关联的Request对应的 DefaultFuture
            if (channel.equals(CHANNELS.get(id))) {
                DefaultFuture future = getFuture(id);
                // 如果请求结果还没有返回，则返回异常状态的结果
                if (future != null && !future.isDone()) {
                    // 构造响应 Response ，注意响应编号。
                    Response disconnectResponse = new Response(future.getId());
                    disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                    disconnectResponse.setErrorMessage("Channel " +
                            channel +
                            " is inactive. Directly return the unFinished request : " +
                            future.getRequest());

                    // 设置响应结果 response
                    DefaultFuture.received(channel, disconnectResponse);
                }
            }
        }
    }

    /**
     * 收到响应结果
     *
     * @param channel  通道
     * @param response 响应结果
     */
    public static void received(Channel channel, Response response) {
        try {

            // 移除元素并返回key=response.getId()的DefaultFuture，即 请求 对应 响应 ，解决了Request和Response的对应

            // 请求与返回结果进行匹配，匹配成功则移除关联的DefaultFuture对象
            DefaultFuture future = FUTURES.remove(response.getId());

            // 接收结果,更新相关字段标识
            if (future != null) {
                future.doReceived(response);
            } else {
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response " + response
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()));
            }
            // 收到结果后，移除关联的的 Channel，它的使命已完成
        } finally {
            CHANNELS.remove(response.getId());
        }
    }

    /**
     * 设置响应结果
     *
     * @param res
     */
    private void doReceived(Response res) {
        // 加锁
        lock.lock();
        try {
            // 设置结果
            response = res;
            /**
             *  唤醒等待线程 {@link #get()}，然后执行 returnFromResponse 方法返回结果
             */
            if (done != null) {
                done.signal();
            }
        } finally {
            // 释放锁
            lock.unlock();
        }

        // 有事件回调，就执行回调逻辑。
        if (callback != null) {
            invokeCallback(callback);
        }
    }

    @Override
    public Object get() throws RemotingException {
        // 超时时间取 timeout 的值
        return get(timeout);
    }

    @Override
    public Object get(int timeout) throws RemotingException {
        if (timeout <= 0) {
            // 默认 1000
            timeout = Constants.DEFAULT_TIMEOUT;
        }

        /**
         * 若未完成即响应response没有回来，基于 Lock + Condition 方式，实现等待，阻塞当前线程，直到被唤醒或被中断或阻塞时间到时了。
         * 等待唤醒则是通过 ChannelHandler#received(channel, message) 方法，当客户端收到服务端到响应时执行 DefaultFuture#received(channel, response) 方法，具体处理看方法内部处理
         */
        if (!isDone()) {
            // 等待请求结果，计时开始
            long start = System.currentTimeMillis();
            lock.lock();
            try {
                // 等待完成或超时
                while (!isDone()) {

                    // 等待请求结果
                    done.await(timeout, TimeUnit.MILLISECONDS);

                    // 如果调用结果成功返回，或等待超时，则跳出while循环继续执行后面逻辑
                    if (isDone() || System.currentTimeMillis() - start > timeout) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 释放锁
                lock.unlock();
            }

            // 未完成，抛出超时异常
            if (!isDone()) {
                throw new TimeoutException(sent > 0, channel, getTimeoutMessage(false));
            }
        }

        // 返回响应
        return returnFromResponse();
    }

    /**
     * 返回响应
     *
     * @return
     * @throws RemotingException
     */
    private Object returnFromResponse() throws RemotingException {
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        // 正常返回结果
        if (res.getStatus() == Response.OK) {
            return res.getResult();
        }

        // 超时，抛出超时异常
        if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            throw new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
        }

        throw new RemotingException(channel, res.getErrorMessage());
    }

    /**
     * 移除当前请求关联的 Channel、DefaultFuture
     */
    public void cancel() {
        // 创建响应结果对象
        Response errorResult = new Response(id);
        errorResult.setErrorMessage("request future has been canceled.");
        response = errorResult;
        // 移除请求关联的 DefaultFuture，Channel
        FUTURES.remove(id);
        CHANNELS.remove(id);
    }

    /**
     * 判断调用结果是否返回，即判断 response 字段是否为空
     *
     * @return
     */
    @Override
    public boolean isDone() {
        return response != null;
    }

    /**
     * 设置回调
     *
     * @param callback
     * @see com.alibaba.dubbo.rpc.protocol.dubbo.filter.FutureFilter#asyncCallback(com.alibaba.dubbo.rpc.Invoker, com.alibaba.dubbo.rpc.Invocation)
     */
    @Override
    public void setCallback(ResponseCallback callback) {
        // 如果有响应则立即执行回调
        if (isDone()) {
            invokeCallback(callback);
        } else {
            boolean isdone = false;
            lock.lock();
            try {
                // 如果没有响应，则先保存回调，收到结果再执行回调逻辑
                if (!isDone()) {
                    this.callback = callback;
                } else {
                    isdone = true;
                }
            } finally {
                lock.unlock();
            }
            // 再尝试一次
            if (isdone) {
                invokeCallback(callback);
            }
        }
    }

    /**
     * 执行回调逻辑
     *
     * @param c
     */
    private void invokeCallback(ResponseCallback c) {
        ResponseCallback callbackCopy = c;
        if (callbackCopy == null) {
            throw new NullPointerException("callback cannot be null.");
        }
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null. url:" + channel.getUrl());
        }

        // 正常响应
        if (res.getStatus() == Response.OK) {
            try {
                // 执行回调 - 处理执行结果
                callbackCopy.done(res.getResult());
            } catch (Exception e) {
                logger.error("callback invoke error .reasult:" + res.getResult() + ",url:" + channel.getUrl(), e);
            }

            // 超时处理 TimeoutException 异常
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            try {
                TimeoutException te = new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
                // 执行回调 - 处理 TimeoutException 异常
                callbackCopy.caught(te);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }

            // 处理其他异常
        } else {
            try {
                RuntimeException re = new RuntimeException(res.getErrorMessage());
                // 执行回调 - 处理RuntimeException
                callbackCopy.caught(re);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
        }
    }


    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private long getStartTimestamp() {
        return start;
    }


    /**
     * 构建超时信息
     *
     * @param scan
     * @return
     */
    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + request + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    /**
     * 后台扫描调用超时任务
     */
    private static class RemotingInvocationTimeoutScan implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    for (DefaultFuture future : FUTURES.values()) {

                        // 如果已完成就跳过
                        if (future == null || future.isDone()) {
                            continue;
                        }

                        // 是否超时，超时就进入超时处理流程
                        if (System.currentTimeMillis() - future.getStartTimestamp() > future.getTimeout()) {
                            // 创建超时 Response
                            Response timeoutResponse = new Response(future.getId());
                            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
                            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
                            // 响应结果
                            DefaultFuture.received(future.getChannel(), timeoutResponse);
                        }
                    }

                    // 休眠30 ms
                    Thread.sleep(30);
                } catch (Throwable e) {
                    logger.error("Exception when scan the timeout invocation of remoting.", e);
                }
            }
        }
    }

}
