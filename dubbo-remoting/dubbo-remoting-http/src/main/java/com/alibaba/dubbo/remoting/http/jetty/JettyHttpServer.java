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
package com.alibaba.dubbo.remoting.http.jetty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.servlet.DispatcherServlet;
import com.alibaba.dubbo.remoting.http.servlet.ServletManager;
import com.alibaba.dubbo.remoting.http.support.AbstractHttpServer;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.log.Log;
import org.mortbay.log.StdErrLog;
import org.mortbay.thread.QueuedThreadPool;

public class JettyHttpServer extends AbstractHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(JettyHttpServer.class);

    /**
     * 内嵌的 Jetty 服务器
     */
    private Server server;

    /**
     * URL 对象
     */
    private URL url;

    public JettyHttpServer(URL url, final HttpHandler handler) {
        // 初始化AbstractHttpServer中的url字段和handler字段
        super(url, handler);
        this.url = url;

        // 日志的配置
        // TODO we should leave this setting to slf4j
        // we must disable the debug logging for production use
        Log.setLog(new StdErrLog());
        Log.getLog().setDebugEnabled(false);

        // 注册 HttpHandler 到 DispatcherServlet 中。注意，HttpHandler会设置到 DispatcherServlet中，通过其 service 方法可知
        // DispatcherServlet收到请求会交给该HttpHandler来处理。即，DispatcherServlet只负责接收请求，具体处理交给 HttpHandler
        DispatcherServlet.addHttpHandler(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()), handler);

        // 创建线程池
        int threads = url.getParameter(Constants.THREADS_KEY, Constants.DEFAULT_THREADS);
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setDaemon(true);
        threadPool.setMaxThreads(threads);
        threadPool.setMinThreads(threads);

        // 创建 Jetty Connector 对象
        SelectChannelConnector connector = new SelectChannelConnector();
        String bindIp = url.getParameter(Constants.BIND_IP_KEY, url.getHost());
        if (!url.isAnyHost() && NetUtils.isValidLocalHost(bindIp)) {
            connector.setHost(bindIp);
        }
        connector.setPort(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()));

        // 创建内嵌的 jetty 对象
        server = new Server();
        server.setThreadPool(threadPool);
        server.addConnector(connector);

        // 创建ServletHandler并与Jetty Server关联，由DispatcherServlet处理全部的请求
        ServletHandler servletHandler = new ServletHandler();
        ServletHolder servletHolder = servletHandler.addServletWithMapping(DispatcherServlet.class, "/*");
        servletHolder.setInitOrder(2);

        // dubbo's original impl can't support the use of ServletContext
//        server.addHandler(servletHandler);
        // TODO Context.SESSIONS is the best option here?
        Context context = new Context(server, "/", Context.SESSIONS);
        context.setServletHandler(servletHandler);
        // 添加 ServletContext 对象到 ServletManager 中
        ServletManager.getInstance().addServletContext(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()), context.getServletContext());

        try {
            // 启动Jetty
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start jetty server on " + url.getParameter(Constants.BIND_IP_KEY) + ":" + url.getParameter(Constants.BIND_PORT_KEY) + ", cause: "
                    + e.getMessage(), e);
        }
    }

    /**
     * 关闭
     * 说明：这里最好调用 {@link DispatcherServlet#removeHttpHandler(int)} 方法，将HttpHandler对象移除
     */
    @Override
    public void close() {

        // 标记关闭
        super.close();

        // 移除ServletContext 对象
        ServletManager.getInstance().removeServletContext(url.getParameter(Constants.BIND_PORT_KEY, url.getPort()));

        if (server != null) {
            try {
                // 关闭Jetty
                server.stop();
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }

}
