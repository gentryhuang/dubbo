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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.beanutil.JavaBeanAccessor;
import com.alibaba.dubbo.common.beanutil.JavaBeanDescriptor;
import com.alibaba.dubbo.common.beanutil.JavaBeanSerializeUtil;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.PojoUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.service.GenericException;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GenericImplInvokerFilter
 * 说明：
 * 服务消费者的泛化调用过滤器
 */
@Activate(group = Constants.CONSUMER, value = Constants.GENERIC_KEY, order = 20000)
public class GenericImplFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(GenericImplFilter.class);

    /**
     * 泛化参数类型
     */
    private static final Class<?>[] GENERIC_PARAMETER_TYPES = new Class<?>[]{String.class, String[].class, Object[].class};

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        // 获得 generic 配置项  ，todo 这里是 invoker-服务提供者的配置信息
        String generic = invoker.getUrl().getParameter(Constants.GENERIC_KEY);

        //-----------------------  泛化实现的调用  ------------------------------/

        if (    // 判断是否开启了泛化引用
                ProtocolUtils.isGeneric(generic)
                        // 方法名是否为 $invoke
                        && !Constants.$INVOKE.equals(invocation.getMethodName())
                        // 调用信息是 RpcInvocation 类型
                        && invocation instanceof RpcInvocation
        ) {

            // 1、 序列化参数
            RpcInvocation invocation2 = (RpcInvocation) invocation;

            // 获取方法名
            String methodName = invocation2.getMethodName();
            // 获取参数类型列表
            Class<?>[] parameterTypes = invocation2.getParameterTypes();
            // 获取参数值列表
            Object[] arguments = invocation2.getArguments();

            // 参数类型名列表
            String[] types = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                types[i] = ReflectUtils.getName(parameterTypes[i]);
            }
            Object[] args;
            // 判断 generic == bean
            if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                args = new Object[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    // 将参数进行转换： POJO -> JavaBeanDescriptor
                    args[i] = JavaBeanSerializeUtil.serialize(arguments[i], JavaBeanAccessor.METHOD);
                }
            } else {
                // 将参数进行转换：POJO -> Map
                args = PojoUtils.generalize(arguments);
            }

            // 2、PRC调用

            // 重新设置 关键的 RPC调用信息，通过新的PpcInvocation就能RPC调用到泛化实现的服务 【todo 关键】

            // 设置调用方法的名字为 $invoke
            invocation2.setMethodName(Constants.$INVOKE);
            // 设置调用方法的参数类型为 GENERIC_PARAMETER_TYPES
            invocation2.setParameterTypes(GENERIC_PARAMETER_TYPES);
            // 设置调用方法的参数数据，分别为方法名，参数类型数组，参数数组
            invocation2.setArguments(new Object[]{methodName, types, args});

            // 方法参数转换完毕，进行调用服务
            Result result = invoker.invoke(invocation2);


            // 3、反序列化结果及异常结果处理

            // 调用结果正常，无异常
            if (!result.hasException()) {

                // 获取调用结果
                Object value = result.getValue();
                try {

                    // 根据方法名和参数类型，获取方法对象
                    Method method = invoker.getInterface().getMethod(methodName, parameterTypes);

                    // generic=bean 的情况，反序列化： JavaBeanDescriptor -> 结果
                    if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                        if (value == null) {
                            return new RpcResult(value);
                        } else if (value instanceof JavaBeanDescriptor) {
                            return new RpcResult(JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) value));
                        } else {
                            throw new RpcException(
                                    "The type of result value is " +
                                            value.getClass().getName() +
                                            " other than " +
                                            JavaBeanDescriptor.class.getName() +
                                            ", and the result is " +
                                            value);
                        }

                        // generic = true，反序列化： Map -> Pojo
                    } else {
                        return new RpcResult(PojoUtils.realize(value, method.getReturnType(), method.getGenericReturnType()));
                    }
                } catch (NoSuchMethodException e) {
                    throw new RpcException(e.getMessage(), e);
                }

                // 调用结果有异常
            } else if (result.getException() instanceof GenericException) {
                GenericException exception = (GenericException) result.getException();
                try {
                    String className = exception.getExceptionClass();
                    Class<?> clazz = ReflectUtils.forName(className);
                    Throwable targetException = null;
                    Throwable lastException = null;
                    try {
                        // 创建异常对象
                        targetException = (Throwable) clazz.newInstance();
                    } catch (Throwable e) {
                        lastException = e;
                        for (Constructor<?> constructor : clazz.getConstructors()) {
                            try {
                                targetException = (Throwable) constructor.newInstance(new Object[constructor.getParameterTypes().length]);
                                break;
                            } catch (Throwable e1) {
                                lastException = e1;
                            }
                        }
                    }

                    if (targetException != null) {
                        try {
                            Field field = Throwable.class.getDeclaredField("detailMessage");
                            if (!field.isAccessible()) {
                                field.setAccessible(true);
                            }
                            field.set(targetException, exception.getExceptionMessage());
                        } catch (Throwable e) {
                            logger.warn(e.getMessage(), e);
                        }
                        result = new RpcResult(targetException);
                    } else if (lastException != null) {
                        throw lastException;
                    }
                } catch (Throwable e) {
                    throw new RpcException("Can not deserialize exception " + exception.getExceptionClass() + ", message: " + exception.getExceptionMessage(), e);
                }
            }
            return result;
        }

        //---------------------- 泛化引用的调用 ------------------------------/

        // 是否符合泛化引用的规则
        if (invocation.getMethodName().equals(Constants.$INVOKE)
                && invocation.getArguments() != null
                && invocation.getArguments().length == 3
                && ProtocolUtils.isGeneric(generic)) {

            // 获取方法参数列表
            Object[] args = (Object[]) invocation.getArguments()[2];

            // genecric = nativejava的情况，校验方法参数是否都为 byte[]
            if (ProtocolUtils.isJavaGenericSerialization(generic)) {

                for (Object arg : args) {
                    if (!(byte[].class == arg.getClass())) {
                        error(generic, byte[].class.getName(), arg.getClass().getName());
                    }
                }

                // generic = bean 的情况，校验方法参数 为 JavaBeanDescriptor
            } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                for (Object arg : args) {
                    if (!(arg instanceof JavaBeanDescriptor)) {
                        error(generic, JavaBeanDescriptor.class.getName(), arg.getClass().getName());
                    }
                }
            }

            // 通过隐式参数，传递 generic 配置项
            ((RpcInvocation) invocation).setAttachment(Constants.GENERIC_KEY, invoker.getUrl().getParameter(Constants.GENERIC_KEY));
        }

        /**
         * @see GenericFilter#invoke(Invoker, Invocation)
         */
        return invoker.invoke(invocation);
    }


    private void error(String generic, String expected, String actual) throws RpcException {
        throw new RpcException(
                "Generic serialization [" +
                        generic +
                        "] only support message type " +
                        expected +
                        " and your message type is " +
                        actual);
    }

}
