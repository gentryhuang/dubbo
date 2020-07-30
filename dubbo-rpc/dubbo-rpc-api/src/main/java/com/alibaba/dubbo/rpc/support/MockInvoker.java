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
package com.alibaba.dubbo.rpc.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.PojoUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.fastjson.JSON;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实现Invoker接口，MockInvoker 实现类
 *
 * @param <T>
 */
final public class MockInvoker<T> implements Invoker<T> {
    /**
     * 代理工厂扩展实现
     */
    private final static ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    /**
     * mock与Invoker 的映射缓存
     */
    private final static Map<String, Invoker<?>> mocks = new ConcurrentHashMap<String, Invoker<?>>();
    /**
     * mock与 Throwable 对象的映射缓存
     */
    private final static Map<String, Throwable> throwables = new ConcurrentHashMap<String, Throwable>();

    /**
     * URL对象
     */
    private final URL url;

    public MockInvoker(URL url) {
        this.url = url;
    }


    /**
     * 执行Mock逻辑
     *
     * @param invocation
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(Invocation invocation) throws RpcException {

        // 获得 'mock' 配置项， 优先级：方法 > 接口
        String mock = getUrl().getParameter(invocation.getMethodName() + "." + Constants.MOCK_KEY);

        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(this);
        }

        if (StringUtils.isBlank(mock)) {
            mock = getUrl().getParameter(Constants.MOCK_KEY);
        }

        // mock 不允许为空
        if (StringUtils.isBlank(mock)) {
            throw new RpcException(new IllegalAccessException("mock can not be null. url :" + url));
        }

        // 对 mock 标准化处理
        mock = normallizeMock(URL.decode(mock));

        // 1 如果mock为 'return',直接返回 值为空的RpcResult对象
        if (Constants.RETURN_PREFIX.trim().equalsIgnoreCase(mock.trim())) {
            RpcResult result = new RpcResult();
            // 值置空
            result.setValue(null);
            return result;

            //2 如果mock 以 'return'开头，就返回对应值的RpcResult对象
        } else if (mock.startsWith(Constants.RETURN_PREFIX)) {

            // 获取返回的值
            mock = mock.substring(Constants.RETURN_PREFIX.length()).trim();
            mock = mock.replace('`', '"');

            try {

                // 解析返回类型
                Type[] returnTypes = RpcUtils.getReturnTypes(invocation);
                // 解析返回值，转换成returnType类型的值
                Object value = parseMockValue(mock, returnTypes);

                // 创建对应值的RpcResult对象，并返回
                return new RpcResult(value);
            } catch (Exception ew) {
                throw new RpcException("mock return invoke error. method :" + invocation.getMethodName() + ", mock:" + mock + ", url: " + url, ew);
            }

            // 3 如果mock 以 'throw'开头，则抛出 RpcException 异常
        } else if (mock.startsWith(Constants.THROW_PREFIX)) {

            mock = mock.substring(Constants.THROW_PREFIX.length()).trim();
            mock = mock.replace('`', '"');

            // 如果throw 没有指定值，则抛出异常
            if (StringUtils.isBlank(mock)) {
                throw new RpcException(" mocked exception for Service degradation. ");
            } else {

                // 创建自定义的异常，即 throw后的异常
                Throwable t = getThrowable(mock);
                throw new RpcException(RpcException.BIZ_EXCEPTION, t);
            }

            // 4 自定义Mock类，执行自定义逻辑
        } else { //impl mock
            try {

                // 根据mock创建自定义MockInvoker 对象
                Invoker<T> invoker = getInvoker(mock);

                // 执行自定义Mock逻辑[执行invoke方法最终会调用Mock对象的方法]
                return invoker.invoke(invocation);

            } catch (Throwable t) {
                throw new RpcException("Failed to create mock implemention class " + mock, t);
            }
        }
    }

    /**
     * 解析值，并转换成对应的返回类型
     *
     * @param mock
     * @return
     * @throws Exception
     */
    public static Object parseMockValue(String mock) throws Exception {
        return parseMockValue(mock, null);
    }

    /**
     * 解析值，并转换成对应的返回类型
     *
     * @param mock
     * @param returnTypes
     * @return
     * @throws Exception
     */
    public static Object parseMockValue(String mock, Type[] returnTypes) throws Exception {
        Object value = null;

        // 创建对象
        if ("empty".equals(mock)) {
            value = ReflectUtils.getEmptyObject(returnTypes != null && returnTypes.length > 0 ? (Class<?>) returnTypes[0] : null);

            // null
        } else if ("null".equals(mock)) {
            value = null;

            // true
        } else if ("true".equals(mock)) {
            value = true;

            // false
        } else if ("false".equals(mock)) {
            value = false;

            // 使用 "" 或 ''的字符串，截取引号
        } else if (mock.length() >= 2 && (mock.startsWith("\"") && mock.endsWith("\"") || mock.startsWith("\'") && mock.endsWith("\'"))) {
            value = mock.subSequence(1, mock.length() - 1);

            // 字符串
        } else if (returnTypes != null && returnTypes.length > 0 && returnTypes[0] == String.class) {
            value = mock;

            // 数字
        } else if (StringUtils.isNumeric(mock)) {
            value = JSON.parse(mock);

            // Map
        } else if (mock.startsWith("{")) {
            value = JSON.parseObject(mock, Map.class);

            // List
        } else if (mock.startsWith("[")) {
            value = JSON.parseObject(mock, List.class);
        } else {
            value = mock;
        }

        // 转换成对应的返回类型
        if (returnTypes != null && returnTypes.length > 0) {
            value = PojoUtils.realize(value, (Class<?>) returnTypes[0], returnTypes.length > 1 ? returnTypes[1] : null);
        }
        return value;
    }

    /**
     * 获取异常对象
     *
     * @param throwstr
     * @return
     */
    private Throwable getThrowable(String throwstr) {
        // 从缓存中获得Throwable对象
        Throwable throwable = (Throwable) throwables.get(throwstr);

        // 存在即返回
        if (throwable != null) {
            return throwable;

            // 不存在，则创建Throwable对象
        } else {
            Throwable t = null;
            try {
                // 获得异常类
                Class<?> bizException = ReflectUtils.forName(throwstr);

                // 获得构造方法
                Constructor<?> constructor;
                constructor = ReflectUtils.findConstructor(bizException, String.class);

                // 创建Throwable 对象
                t = (Throwable) constructor.newInstance(new Object[]{" mocked exception for Service degradation. "});

                // 放入缓存
                if (throwables.size() < 1000) {
                    throwables.put(throwstr, t);
                }
            } catch (Exception e) {
                throw new RpcException("mock throw error :" + throwstr + " argument error.", e);
            }
            return t;
        }
    }

    /**
     * 获得Invoker
     *
     * @param mockService
     * @return
     */
    @SuppressWarnings("unchecked")
    private Invoker<T> getInvoker(String mockService) {
        // 从缓存中获取Invoker对象
        Invoker<T> invoker = (Invoker<T>) mocks.get(mockService);

        // 存在即返回
        if (invoker != null) {
            return invoker;

            // 不存在，则创建Invoker对象
        } else {

            //  从URL中获得接口
            Class<T> serviceType = (Class<T>) ReflectUtils.forName(url.getServiceInterface());

            // 如果mock为true/default，则拼接重置mock: 接口名 + "Mock"
            if (ConfigUtils.isDefault(mockService)) {
                mockService = serviceType.getName() + "Mock";
            }

            // 根据Mock名称获得Mock类
            Class<?> mockClass = ReflectUtils.forName(mockService);

            // Mock类必须实现接口，否抛出异常
            if (!serviceType.isAssignableFrom(mockClass)) {
                throw new IllegalArgumentException("The mock implemention class " + mockClass.getName() + " not implement interface " + serviceType.getName());
            }

            try {

                // 创建Mock对象
                T mockObject = (T) mockClass.newInstance();

                // 通过代理工厂创建Mock对应的Invoker对象 【执行该invoker#invoke方法时，会调用Mock对象的方法】
                invoker = proxyFactory.getInvoker(mockObject, (Class<T>) serviceType, url);

                // 加入到缓存
                if (mocks.size() < 10000) {
                    mocks.put(mockService, invoker);
                }

                // 返回Mock对象的Invoker
                return invoker;
            } catch (InstantiationException e) {
                throw new IllegalStateException("No such empty constructor \"public " + mockClass.getSimpleName() + "()\" in mock implemention class " + mockClass.getName(), e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * 标准化Mock
     * <p>
     * mock=fail:throw
     * mock=fail:return
     * mock=xx.Service
     *
     * @param mock
     * @return
     */
    private String normallizeMock(String mock) {
        // 如果mock为空，直接返回
        if (mock == null || mock.trim().length() == 0) {
            return mock;

            // 如果mock 为 true/default/fail/force 四种的一个，就拼接mock为全名： 接口 + "Mock"
        } else if (ConfigUtils.isDefault(mock) || "fail".equalsIgnoreCase(mock.trim()) || "force".equalsIgnoreCase(mock.trim())) {
            mock = url.getServiceInterface() + "Mock";
        }

        //----------- 'force:' / 'fail:' 开头仅用来表示Mock类型，是强制还是失败 ---------------/

        // 如果mock 以 "fail:" 开头，则去除 前缀fail:
        if (mock.startsWith(Constants.FAIL_PREFIX)) {
            mock = mock.substring(Constants.FAIL_PREFIX.length()).trim();

            // 如果mock 以 "force:" 开头，则去除 force:
        } else if (mock.startsWith(Constants.FORCE_PREFIX)) {
            mock = mock.substring(Constants.FORCE_PREFIX.length()).trim();
        }

        // 返回 mock
        return mock;
    }

    @Override
    public URL getUrl() {
        return this.url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
        //do nothing
    }

    @Override
    public Class<T> getInterface() {
        //FIXME
        return null;
    }
}
