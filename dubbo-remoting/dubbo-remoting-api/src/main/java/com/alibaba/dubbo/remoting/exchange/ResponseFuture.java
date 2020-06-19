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
package com.alibaba.dubbo.remoting.exchange;

import com.alibaba.dubbo.remoting.RemotingException;

/**
 * Future. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.remoting.exchange.ExchangeChannel#request(Object)
 * @see com.alibaba.dubbo.remoting.exchange.ExchangeChannel#request(Object, int)
 * <p>
 * 响应Future接口
 */
public interface ResponseFuture {

    /**
     * 获取响应结果
     *
     * @return result.
     */
    Object get() throws RemotingException;

    /**
     * 在指定的时间内获取结果
     *
     * @param timeoutInMillis timeout.
     * @return result.
     */
    Object get(int timeoutInMillis) throws RemotingException;

    /**
     * 设置回调
     *
     * @param callback
     */
    void setCallback(ResponseCallback callback);

    /**
     * 是否完成
     *
     * @return done or not.
     */
    boolean isDone();

}