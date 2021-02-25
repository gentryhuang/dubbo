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
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 */
@SPI("dubbo")
public interface Protocol {

    /**
     * 默认端口
     * Get default port when user doesn't config the port.
     *
     * @return default port
     */
    int getDefaultPort();

    /**
     * 将一个 Invoker 暴露出去 <br>
     * 1. 接收到请求后，协议应该记录请求源地址: RpcContext.getContext().setRemoteAddress();<br>
     * 2. export()必须是幂等的，也就是说，在暴露相同的URL时，调用一次和调用两次没有区别 <br>
     * 3. Invoker 是由框架传入的，协议不需要关心 <br>
     *
     * @param <T>     Service type
     * @param invoker Service invoker
     * @return exporter reference for exported service, useful for unexport the service later
     * @throws RpcException thrown when error occurs during export the service, for example: port is occupied
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * 引用一个 Invoker  <br>
     * 1. 协议的责任是根据参数返回一个 Invoker 对象，由 refer() 方法返回。<br>
     * 2. Consumer端可以通过这个Invoker请求到Provider端的服务. <br>
     *
     * @param <T>  Service type
     * @param type Service class
     * @param url  URL address for the remote service
     * @return invoker service's local proxy
     * @throws RpcException when there's any error while connecting to the service provider
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * 释放当前 Protocol 对象底层占用的资源 <br>
     * 1. 销毁export()方法以及refer()方法使用到的Invoker对象 <br>
     * 2. 释放所有占用资源, 如: 连接, 端口, 等等. <br>
     * 3. 协议可以继续导出和引用新的服务，即使它被销毁。
     */
    void destroy();

}