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
package com.alibaba.dubbo.common.bytecode;

import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.ReflectUtils;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proxy.
 */

public abstract class Proxy {
    public static final InvocationHandler RETURN_NULL_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return null;
        }
    };
    public static final InvocationHandler THROW_UNSUPPORTED_INVOKER = new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            throw new UnsupportedOperationException("Method [" + ReflectUtils.getName(method) + "] unimplemented.");
        }
    };
    private static final AtomicLong PROXY_CLASS_COUNTER = new AtomicLong(0);
    private static final String PACKAGE_NAME = Proxy.class.getPackage().getName();
    private static final Map<ClassLoader, Map<String, Object>> ProxyCacheMap = new WeakHashMap<ClassLoader, Map<String, Object>>();

    private static final Object PendingGenerationMarker = new Object();

    protected Proxy() {
    }

    /**
     * Get proxy.
     *
     * @param ics interface class array.
     * @return Proxy instance.
     */
    public static Proxy getProxy(Class<?>... ics) {
        return getProxy(ClassHelper.getClassLoader(Proxy.class), ics);
    }

    /**
     * 为接口生成代理：
     * <p>
     * 1 ccp 用于为服务接口生成代理类，比如我们有一个 DemoService 接口，这个接口代理类就是由 ccp 生成的。
     * 2 ccm 则是用于为 org.apache.dubbo.common.bytecode.Proxy 抽象类生成子类，主要是实现 Proxy 的抽象方法。
     * 3 下面以 org.apache.dubbo.demo.DemoService 这个接口为例，来看一下该接口代理类代码大致是怎样的（忽略 EchoService 接口）:
     * <p>
     * package org.apache.dubbo.common.bytecode;
     * <p>
     * public class proxy0 implements org.apache.dubbo.demo.DemoService {
     * <p>
     * public static java.lang.reflect.Method[] methods;
     * <p>
     * private java.lang.reflect.InvocationHandler handler;
     * <p>
     * public proxy0() {
     * }
     * <p>
     * public proxy0(java.lang.reflect.InvocationHandler arg0) {
     * handler = $1;
     * }
     * <p>
     * public java.lang.String sayHello(java.lang.String arg0) {
     * Object[] args = new Object[1];
     * args[0] = ($w) $1;
     * Object ret = handler.invoke(this, methods[0], args);
     * return (java.lang.String) ret;
     * }
     * }
     * 4 最终返回的代理对象其实是一个proxy0对象，当调用服务接口中的方法时（代理类中的方法），该方法会调用内部的InvocationHandler#invoke方法
     *
     * @param cl  类加载器
     * @param ics 接口类数组
     * @return Proxy 接口代理对象
     */
    public static Proxy getProxy(ClassLoader cl, Class<?>... ics) {
        if (ics.length > 65535) {
            throw new IllegalArgumentException("interface limit exceeded");
        }

        StringBuilder sb = new StringBuilder();
        // 遍历接口列表
        for (int i = 0; i < ics.length; i++) {
            String itf = ics[i].getName();
            // 检测类型是否为接口
            if (!ics[i].isInterface()) {
                throw new RuntimeException(itf + " is not a interface.");
            }

            Class<?> tmp = null;
            try {
                // 利用反射获取接口对应的Class
                tmp = Class.forName(itf, false, cl);
            } catch (ClassNotFoundException e) {
            }

            // 检测接口是否相同，这里tmp有可能为空
            if (tmp != ics[i]) {
                throw new IllegalArgumentException(ics[i] + " is not visible from class loader");
            }

            // 拼接接口全限定性名，分隔符为 `;`
            sb.append(itf).append(';');
        }

        // use interface class name list as key.
        String key = sb.toString();

        // get cache by class loader.
        Map<String, Object> cache;
        synchronized (ProxyCacheMap) {
            cache = ProxyCacheMap.get(cl);
            if (cache == null) {
                cache = new HashMap<String, Object>();
                ProxyCacheMap.put(cl, cache);
            }
        }

        Proxy proxy = null;
        synchronized (cache) {
            do {
                // 从缓存中获取 Reference<Proxy> 实例
                Object value = cache.get(key);
                if (value instanceof Reference<?>) {
                    proxy = (Proxy) ((Reference<?>) value).get();
                    if (proxy != null) {
                        return proxy;
                    }
                }

                // 多线程控制，保证只有一个线程可以进行后续操作
                if (value == PendingGenerationMarker) {
                    try {
                        // 其他线程要等待
                        cache.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    // 放置标志位到缓存中，并跳出while循环进行后续操作
                    cache.put(key, PendingGenerationMarker);
                    break;
                }
            }
            while (true);
        }

        long id = PROXY_CLASS_COUNTER.getAndIncrement();
        String pkg = null;
        ClassGenerator ccp = null, ccm = null;
        try {
            // 创建ClassGenerator对象
            ccp = ClassGenerator.newInstance(cl);

            Set<String> worked = new HashSet<String>();
            List<Method> methods = new ArrayList<Method>();

            for (int i = 0; i < ics.length; i++) {
                // 检测接口访问级别是否为protected或private
                if (!Modifier.isPublic(ics[i].getModifiers())) {
                    // 获取接口包名
                    String npkg = ics[i].getPackage().getName();
                    if (pkg == null) {
                        pkg = npkg;
                    } else {
                        if (!pkg.equals(npkg)) {
                            // 非public 级别的接口必须在同一个包下，否则报错
                            throw new IllegalArgumentException("non-public interfaces from different packages");
                        }
                    }
                }
                // 添加接口到 ClassGenerator
                ccp.addInterface(ics[i]);

                // 遍历接口方法
                for (Method method : ics[i].getMethods()) {
                    // 获取方法签名
                    String desc = ReflectUtils.getDesc(method);
                    // 如果已经包含在worked中，则忽略。可能会出现，A接口和B接口中包含一个完全相同的方法
                    if (worked.contains(desc)) {
                        continue;
                    }
                    worked.add(desc);

                    int ix = methods.size();
                    // 获取方法返回值类型
                    Class<?> rt = method.getReturnType();
                    // 获取参数列表
                    Class<?>[] pts = method.getParameterTypes();

                    // 生成 Object[] args = new Object[1...N]
                    StringBuilder code = new StringBuilder("Object[] args = new Object[").append(pts.length).append("];");
                    for (int j = 0; j < pts.length; j++) {
                        code.append(" args[").append(j).append("] = ($w)$").append(j + 1).append(";");
                    }
                    // 生成InvokerHandler接口的invoker 方法调用语句，如： Object ret = handler.invoke(this,methods[1...N],args);
                    code.append(" Object ret = handler.invoke(this, methods[" + ix + "], args);");
                    // 返回值不为void
                    if (!Void.TYPE.equals(rt)) {
                        // 生成返回语句，形如 return (java.lang.String) ret;
                        code.append(" return ").append(asArgument(rt, "ret")).append(";");
                    }

                    methods.add(method);
                    // 添加方法名、访问控制符、参数列表、方法代码等信息到 ClassGenerator 中
                    ccp.addMethod(method.getName(), method.getModifiers(), rt, pts, method.getExceptionTypes(), code.toString());
                }
            }

            if (pkg == null) {
                pkg = PACKAGE_NAME;
            }

            // 构建接口代理类名称：pkg + ".proxy" + id，比如 com.tianxiaobo.proxy0
            String pcn = pkg + ".proxy" + id;
            ccp.setClassName(pcn);
            ccp.addField("public static java.lang.reflect.Method[] methods;");
            // 生成 private java.lang.reflect.InvocationHandler handler;
            ccp.addField("private " + InvocationHandler.class.getName() + " handler;");
            /**
             * 为接口代理类添加带有 InvocationHandler 参数的构造方法，比如：
             *       porxy0(java.lang.reflect.InvocationHandler arg0) {
             *           handler=$1;
             *      }
             */
            ccp.addConstructor(Modifier.PUBLIC, new Class<?>[]{InvocationHandler.class}, new Class<?>[0], "handler=$1;");
            // 为接口代理类添加默认构造方法
            ccp.addDefaultConstructor();

            // 生成接口代理
            Class<?> clazz = ccp.toClass();
            clazz.getField("methods").set(null, methods.toArray(new Method[0]));

            // 构建Proxy子类名称，比如：Proxy1,Proxy2
            String fcn = Proxy.class.getName() + id;
            ccm = ClassGenerator.newInstance(cl);
            ccm.setClassName(fcn);
            ccm.addDefaultConstructor();
            ccm.setSuperClass(Proxy.class);

            /**
             * 为 Proxy 的抽象方法 newInstance 生成实现代码，形如：
             *     public Object newInstance(java.lang.reflect.InvocationHandler h) {
             *          return new com.alibaba.demo.proxy0($1);
             *      }
             */
            ccm.addMethod("public Object newInstance(" + InvocationHandler.class.getName() + " h){ return new " + pcn + "($1); }");

            // 生成Proxy实现类
            Class<?> pc = ccm.toClass();
            /**
             * 通过反射创建Proxy 实例
             */
            proxy = (Proxy) pc.newInstance();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            // release ClassGenerator
            if (ccp != null) {
                // 释放资源
                ccp.release();
            }
            if (ccm != null) {
                ccm.release();
            }
            synchronized (cache) {
                if (proxy == null) {
                    cache.remove(key);
                } else {
                    // 写缓存，注意若引用
                    cache.put(key, new WeakReference<Proxy>(proxy));
                }
                // 唤醒其他等待线程
                cache.notifyAll();
            }
        }
        return proxy;
    }

    private static String asArgument(Class<?> cl, String name) {
        if (cl.isPrimitive()) {
            if (Boolean.TYPE == cl)
                return name + "==null?false:((Boolean)" + name + ").booleanValue()";
            if (Byte.TYPE == cl)
                return name + "==null?(byte)0:((Byte)" + name + ").byteValue()";
            if (Character.TYPE == cl)
                return name + "==null?(char)0:((Character)" + name + ").charValue()";
            if (Double.TYPE == cl)
                return name + "==null?(double)0:((Double)" + name + ").doubleValue()";
            if (Float.TYPE == cl)
                return name + "==null?(float)0:((Float)" + name + ").floatValue()";
            if (Integer.TYPE == cl)
                return name + "==null?(int)0:((Integer)" + name + ").intValue()";
            if (Long.TYPE == cl)
                return name + "==null?(long)0:((Long)" + name + ").longValue()";
            if (Short.TYPE == cl)
                return name + "==null?(short)0:((Short)" + name + ").shortValue()";
            throw new RuntimeException(name + " is unknown primitive type.");
        }
        return "(" + ReflectUtils.getName(cl) + ")" + name;
    }

    /**
     * get instance with default handler.
     *
     * @return instance.
     */
    public Object newInstance() {
        return newInstance(THROW_UNSUPPORTED_INVOKER);
    }

    /**
     * get instance with special handler.
     *
     * @return instance.
     */
    abstract public Object newInstance(InvocationHandler handler);
}
