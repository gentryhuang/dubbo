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
package com.alibaba.dubbo.rpc.protocol.hessian;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.http.HttpBinder;
import com.alibaba.dubbo.remoting.http.HttpHandler;
import com.alibaba.dubbo.remoting.http.HttpServer;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.AbstractProxyProtocol;

import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;
import com.caucho.hessian.HessianException;
import com.caucho.hessian.client.HessianConnectionException;
import com.caucho.hessian.client.HessianConnectionFactory;
import com.caucho.hessian.client.HessianProxyFactory;
import com.caucho.hessian.io.HessianMethodSerializationException;
import com.caucho.hessian.server.HessianSkeleton;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * http rpc support。继承 AbstractProxyProtocol 抽象类， hessian:// 协议实现类
 * 说明：
 * 整体实现和 http:// 协议实现 差不多,并且 hessian:// 协议实现类是基于 dubbo-remoting-http项目 作为 通信服务器
 */
public class HessianProtocol extends AbstractProxyProtocol {

    /**
     * Http 服务器集合
     * key: ip:port
     * value: HttpServer
     */
    private final Map<String, HttpServer> serverMap = new ConcurrentHashMap<String, HttpServer>();

    /**
     * HessianSkeleton 集合
     * key: 服务名
     * value: HessianSkeleton
     * 说明：
     * 请求处理过程： HttpServer -> DispatcherServlet -> HessianHandler -> HessianSkeleton
     */
    private final Map<String, HessianSkeleton> skeletonMap = new ConcurrentHashMap<String, HessianSkeleton>();

    /**
     * HttpBinder$Adaptive 对象,Dubbo SPI IOC设置
     */
    private HttpBinder httpBinder;

    public HessianProtocol() {
        super(HessianException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return 80;
    }

    @Override
    protected <T> Runnable doExport(T impl, Class<T> type, URL url) throws RpcException {
        // 获得服务器地址
        String addr = getAddr(url);
        // 从缓存中 根据服务地址 获取 HttpServer 对象，若不存在则创建
        HttpServer server = serverMap.get(addr);
        if (server == null) {
            // 基于 dubbo-remoting-http项目 作为 通信服务器
            server = httpBinder.bind(url, new HessianHandler());
            serverMap.put(addr, server);
        }

        // 创建 HessianSkeleton对象
        final String path = url.getAbsolutePath();
        final HessianSkeleton skeleton = new HessianSkeleton(impl, type);
        skeletonMap.put(path, skeleton);

        // 泛化处理
        final String genericPath = path + "/" + Constants.GENERIC_KEY;
        skeletonMap.put(genericPath, new HessianSkeleton(impl, GenericService.class));

        // 返回取消服务暴露的回调 Runnable
        return new Runnable() {
            @Override
            public void run() {
                skeletonMap.remove(path);
                skeletonMap.remove(genericPath);
            }
        };
    }

    /**
     * todo 怎么和服务暴露挂钩的？？？？
     * @param serviceType
     * @param url  URL
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    @SuppressWarnings("unchecked")
    protected <T> T doRefer(Class<T> serviceType, URL url) throws RpcException {

        // 处理泛化
        String generic = url.getParameter(Constants.GENERIC_KEY);
        boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);
        if (isGeneric) {
            RpcContext.getContext().setAttachment(Constants.GENERIC_KEY, generic);
            url = url.setPath(url.getPath() + "/" + Constants.GENERIC_KEY);
        }

        // 创建 HessianProxyFactory 对象
        HessianProxyFactory hessianProxyFactory = new HessianProxyFactory();
        boolean isHessian2Request = url.getParameter(Constants.HESSIAN2_REQUEST_KEY, Constants.DEFAULT_HESSIAN2_REQUEST);
        hessianProxyFactory.setHessian2Request(isHessian2Request);
        boolean isOverloadEnabled = url.getParameter(Constants.HESSIAN_OVERLOAD_METHOD_KEY, Constants.DEFAULT_HESSIAN_OVERLOAD_METHOD);
        hessianProxyFactory.setOverloadEnabled(isOverloadEnabled);

        // 获取客户端 client 类型，默认为jdk
        String client = url.getParameter(Constants.CLIENT_KEY, Constants.DEFAULT_HTTP_CLIENT);

        // 创建连接器工厂为 HttpClientConnectionFactory, 即 产生的连接为Apache HttpClient
        if ("httpclient".equals(client)) {
            hessianProxyFactory.setConnectionFactory(new HttpClientConnectionFactory());
        } else if (client != null && client.length() > 0 && !Constants.DEFAULT_HTTP_CLIENT.equals(client)) {
            throw new IllegalStateException("Unsupported http protocol client=\"" + client + "\"!");
        } else {
            HessianConnectionFactory factory = new DubboHessianURLConnectionFactory();
            factory.setHessianProxyFactory(hessianProxyFactory);
            hessianProxyFactory.setConnectionFactory(factory);
        }

        // 设置超时时间
        int timeout = url.getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        hessianProxyFactory.setConnectTimeout(timeout);
        hessianProxyFactory.setReadTimeout(timeout);

        // 创建 Service Proxy 对象
        return (T) hessianProxyFactory.create(serviceType, url.setProtocol("http").toJavaURL(), Thread.currentThread().getContextClassLoader());
    }

    /**
     * 将异常处理为Dubbo 异常
     * @param e 异常
     * @return
     */
    @Override
    protected int getErrorCode(Throwable e) {
        if (e instanceof HessianConnectionException) {
            if (e.getCause() != null) {
                Class<?> cls = e.getCause().getClass();
                if (SocketTimeoutException.class.equals(cls)) {
                    return RpcException.TIMEOUT_EXCEPTION;
                }
            }
            return RpcException.NETWORK_EXCEPTION;
        } else if (e instanceof HessianMethodSerializationException) {
            return RpcException.SERIALIZATION_EXCEPTION;
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        // 销毁
        super.destroy();
        // 销毁 HttpServer
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            HttpServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close hessian server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    /**
     * 请求处理器
     */
    private class HessianHandler implements HttpHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            // 请求uri
            String uri = request.getRequestURI();
            // 获得 HessianSkeleton 对象
            HessianSkeleton skeleton = skeletonMap.get(uri);

            // 必须是 POST 请求
            if (!request.getMethod().equalsIgnoreCase("POST")) {
                response.setStatus(500);
            } else {

                // 设置 RpcContext 信息
                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());

                Enumeration<String> enumeration = request.getHeaderNames();
                while (enumeration.hasMoreElements()) {
                    String key = enumeration.nextElement();
                    if (key.startsWith(Constants.DEFAULT_EXCHANGER)) {
                        RpcContext.getContext().setAttachment(key.substring(Constants.DEFAULT_EXCHANGER.length()),
                                request.getHeader(key));
                    }
                }

                // 执行调用
                try {
                    skeleton.invoke(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }
            }
        }

    }

}
