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
package com.alibaba.dubbo.remoting.http.servlet;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.support.AbstractHttpServer;

/**
 * 基于Servlet 的服务器实现类，这样方式不怎么用了，更多的是jar包形式，而不是配置web.xml。
 */
public class ServletHttpServer extends AbstractHttpServer {

    public ServletHttpServer(URL url, HttpHandler handler) {
        super(url, handler);

        // 注册HttpHandler 到 DispatcherServlet 中
        DispatcherServlet.addHttpHandler(url.getParameter(Constants.BIND_PORT_KEY, 8080), handler);
    }

    /**
     * 说明，基于Servlet的服务器实现类：
     * 1 在 <dubbo:protocol/> 配置的端口，要和外部的Servlet容器的端口保持一致
     * 2 需要配置 DispatcherServlet到web.xml中，通过这样的方式，让外部的Servlet容器，可以进行转发
     */

}