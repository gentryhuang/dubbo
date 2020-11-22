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

import com.alibaba.dubbo.remoting.http.HttpHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service dispatcher Servlet. 服务请求调度Servlet
 * 说明：
 * 用于调度请求，选择对应的HttpHandler处理请求
 */
public class DispatcherServlet extends HttpServlet {

    private static final long serialVersionUID = 5766349180380479888L;
    /**
     * 处理器集合
     */
    private static final Map<Integer, HttpHandler> handlers = new ConcurrentHashMap<Integer, HttpHandler>();

    /**
     * 单例
     */
    private static DispatcherServlet INSTANCE;

    public DispatcherServlet() {
        DispatcherServlet.INSTANCE = this;
    }

    /**
     * 添加处理器
     *
     * @param port      服务器端口
     * @param processor 处理器
     */
    public static void addHttpHandler(int port, HttpHandler processor) {
        handlers.put(port, processor);
    }

    public static DispatcherServlet getInstance() {
        return INSTANCE;
    }

    /**
     * 移除处理器
     *
     * @param port 服务器端口
     */
    public static void removeHttpHandler(int port) {
        handlers.remove(port);
    }


    /**
     * 调度请求，根据请求的端口，选择对应的处理器来处理请求
     *
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 从HANDLERS集合中查询端口对应的HttpHandler对象
        HttpHandler handler = handlers.get(request.getLocalPort());
        // 没有处理器就报错
        if (handler == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Service not found.");
        } else {
            // 将请求委托给HttpHandler对象处理
            handler.handle(request, response);
        }
    }

}
