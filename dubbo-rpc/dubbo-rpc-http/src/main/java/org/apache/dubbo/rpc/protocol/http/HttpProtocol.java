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
package org.apache.dubbo.rpc.protocol.http;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.http.HttpBinder;
import org.apache.dubbo.remoting.http.HttpHandler;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProxyProtocol;

import com.googlecode.jsonrpc4j.HttpException;
import com.googlecode.jsonrpc4j.JsonRpcClientException;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.dubbo.rpc.support.ProtocolUtils;
import org.springframework.remoting.RemoteAccessException;
import org.springframework.remoting.support.RemoteInvocation;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;

public class HttpProtocol extends AbstractProxyProtocol {
    // 跨域支持
    public static final String ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "Access-Control-Allow-Origin";
    public static final String ACCESS_CONTROL_ALLOW_METHODS_HEADER = "Access-Control-Allow-Methods";
    public static final String ACCESS_CONTROL_ALLOW_HEADERS_HEADER = "Access-Control-Allow-Headers";


    /**
     * 服务路径（path）到 JsonRpcServer 的映射
     * 请求处理过程说明：HttpServer -> DispatcherServlet -> InternalHandler -> JsonRpcServer
     */
    private final Map<String, JsonRpcServer> skeletonMap = new ConcurrentHashMap<>();

    // HTTP绑定器
    private HttpBinder httpBinder;

    // HttpBinder$Adaptive 对象,通过 {@link #setHttpBinder(HttpBinder)}方法，Dubbo SPI IOC注入
    public HttpProtocol() {
        super(HttpException.class, JsonRpcClientException.class);
    }

    public void setHttpBinder(HttpBinder httpBinder) {
        this.httpBinder = httpBinder;
    }

    @Override
    public int getDefaultPort() {
        return 80;
    }

    private class InternalHandler implements HttpHandler {
        /**
         * 是否跨域支持
         */
        private boolean cors;

        public InternalHandler(boolean cors) {
            this.cors = cors;
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response)
                throws ServletException {

            // 1 获取请求的uri
            String uri = request.getRequestURI();
            // 2 从缓存中取出请求uri 对应的 JsonRpcServer

            JsonRpcServer skeleton = skeletonMap.get(uri);

            // 3 处理跨域

            if (cors) {
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_METHODS_HEADER, "POST");
                response.setHeader(ACCESS_CONTROL_ALLOW_HEADERS_HEADER, "*");
            }

            // 4 响应跨域探测请求

            if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
                response.setStatus(200);

                // 5 必须是 POST 请求

            } else if (request.getMethod().equalsIgnoreCase("POST")) {

                // 设置远程调用地址

                RpcContext.getContext().setRemoteAddress(request.getRemoteAddr(), request.getRemotePort());
                try {
                    // 处理请求

                    skeleton.handle(request.getInputStream(), response.getOutputStream());
                } catch (Throwable e) {
                    throw new ServletException(e);
                }

                // 请求方法不匹配直接抛出 500

            } else {
                response.setStatus(500);
            }
        }

    }

    @Override
    protected <T> Runnable doExport(final T impl, Class<T> type, URL url) throws RpcException {
        // 1 获取服务器地址 ip:port
        String addr = getAddr(url);
        // 2 根据地址从缓存中获得 HTTP 服务，若不存在，进行创建

        ProtocolServer protocolServer = serverMap.get(addr);
        if (protocolServer == null) {
            /**
             * 1 通过SPI机制获取具体的 HttpBinder的拓展实现
             * 2 具体的HttpBinder实现调用bind方法：
             *   1）启动服务
             *   2）为服务设置请求处理器(InternalHandler对象)，支持设置跨域参数
             * 3 缓存 HTTP 服务
             */
            RemotingServer remotingServer = httpBinder.bind(url, new InternalHandler(url.getParameter("cors", false)));
            serverMap.put(addr, new ProxyProtocolServer(remotingServer));
        }

        // 3 获取 url 的 path ，以此为 key 缓存 JsonRpcServer。如：/org.apache.dubbo.demo.GreetingService

        final String path = url.getAbsolutePath();
        // 4 支持泛化，如：/org.apache.dubbo.demo.GreetingService/generic

        final String genericPath = path + "/" + GENERIC_KEY;
        // 5 创建 JsonRpcServer，暴露服务

        JsonRpcServer skeleton = new JsonRpcServer(impl, type);
        JsonRpcServer genericServer = new JsonRpcServer(impl, GenericService.class);

        // 6 分别缓存服务和泛化服务的 JsonRpcServer

        skeletonMap.put(path, skeleton);
        skeletonMap.put(genericPath, genericServer);
        return () -> {
            skeletonMap.remove(path);
            skeletonMap.remove(genericPath);
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <T> T doRefer(final Class<T> serviceType, URL url) throws RpcException {
        // 1 判断是否是泛化调用

        final String generic = url.getParameter(GENERIC_KEY);
        final boolean isGeneric = ProtocolUtils.isGeneric(generic) || serviceType.equals(GenericService.class);

        // 2 工厂对象

        JsonProxyFactoryBean jsonProxyFactoryBean = new JsonProxyFactoryBean();
        JsonRpcProxyFactoryBean jsonRpcProxyFactoryBean = new JsonRpcProxyFactoryBean(jsonProxyFactoryBean);

        // 3 附加属性和泛化调用支持

        jsonRpcProxyFactoryBean.setRemoteInvocationFactory((methodInvocation) -> {
            RemoteInvocation invocation = new JsonRemoteInvocation(methodInvocation);

            // 泛化调用

            if (isGeneric) {
                invocation.addAttribute(GENERIC_KEY, generic);
            }
            return invocation;
        });

        // 4 服务访问路径，如 http://10.1.31.48:80/org.apache.dubbo.demo.DemoService

        String key = url.setProtocol("http").toIdentityString();
        // 5 泛化调用服务访问路径，如: http://10.1.31.48:80/org.apache.dubbo.demo.DemoService/generic

        if (isGeneric) {
            key = key + "/" + GENERIC_KEY;
        }

        // 6.1 设置服务访问路径，设置到 jsonProxyFactoryBean 中
        jsonRpcProxyFactoryBean.setServiceUrl(key);
        // 6.2 设置服务接口，设置到 jsonProxyFactoryBean 中
        jsonRpcProxyFactoryBean.setServiceInterface(serviceType);

        // 6.3 执行Spring的InitializingBean方法， 创建 JsonRpcHttpClient & 接口代理对象
        jsonProxyFactoryBean.afterPropertiesSet();

        // 7 返回接口的代理对象，拦截功能是 MethodInterceptor，基于aopalliance提供AOP的拦截处理机制。
        // 在执行接口的目标方法时，会进行拦截，执行 com.googlecode.jsonrpc4j.spring.JsonProxyFactoryBean.invoke 方法，进而使用 JsonRpcHttpClient 进行远程调用
        return (T) jsonProxyFactoryBean.getObject();
    }

    protected int getErrorCode(Throwable e) {
        if (e instanceof RemoteAccessException) {
            e = e.getCause();
        }
        if (e != null) {
            Class<?> cls = e.getClass();
            if (SocketTimeoutException.class.equals(cls)) {
                return RpcException.TIMEOUT_EXCEPTION;
            } else if (IOException.class.isAssignableFrom(cls)) {
                return RpcException.NETWORK_EXCEPTION;
            } else if (ClassNotFoundException.class.isAssignableFrom(cls)) {
                return RpcException.SERIALIZATION_EXCEPTION;
            }

            if (e instanceof HttpProtocolErrorCode) {
                return ((HttpProtocolErrorCode) e).getErrorCode();
            }
        }
        return super.getErrorCode(e);
    }

    @Override
    public void destroy() {
        super.destroy();
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close jsonrpc server " + server.getUrl());
                    }
                    server.close();
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }


}