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
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.io.UnsafeByteArrayInputStream;
import com.alibaba.dubbo.common.io.UnsafeByteArrayOutputStream;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.PojoUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.service.GenericException;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.dubbo.rpc.support.ProtocolUtils;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * GenericInvokerFilter.
 * 服务提供者的泛化调用过滤器
 */
@Activate(group = Constants.PROVIDER, order = -20000)
public class GenericFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {

        // 是否是泛化引用的调用
        if (
            // 调用方法是否是 $invoke
                inv.getMethodName().equals(Constants.$INVOKE)
                        // 调用参数不为空且参数必须是3个
                        && inv.getArguments() != null && inv.getArguments().length == 3
                        // 非泛化实现的调用   todo 注意，这是泛化引用的调用，即调用的是正常的服务提供者
                        && !invoker.getInterface().equals(GenericService.class)
        ) {
            // 获得对应的方法名
            String name = ((String) inv.getArguments()[0]).trim();
            // 获得方法参数类型和方法参数列表
            String[] types = (String[]) inv.getArguments()[1];
            Object[] args = (Object[]) inv.getArguments()[2];

            try {

                // 通过反射获得提供方的方法对象
                Method method = ReflectUtils.findMethodByMethodSignature(invoker.getInterface(), name, types);
                // 获取服务提供方的目标方法的参数类型
                Class<?>[] params = method.getParameterTypes();

                if (args == null) {
                    args = new Object[params.length];
                }

                // 获得 generic 配置项
                String generic = inv.getAttachment(Constants.GENERIC_KEY);

                // 为空就从上下文中继续找
                if (StringUtils.isBlank(generic)) {
                    generic = RpcContext.getContext().getAttachment(Constants.GENERIC_KEY);
                }

                //-------------------- 反序列化参数，即方法参数转换，需要注意的是，普通类型以及集合不需要在泛化引用的时候特被处理，见官方文档 -----------------------/

                // 如果没有设置 generic 或者 generic = true，反序列化参数，Map->Pojo
                if (StringUtils.isEmpty(generic) || ProtocolUtils.isDefaultGenericSerialization(generic)) {
                    args = PojoUtils.realize(args, params, method.getGenericParameterTypes());

                    // 如果 generic = nativejava,反序列化参数， byte[]-> 方法参数
                } else if (ProtocolUtils.isJavaGenericSerialization(generic)) {
                    for (int i = 0; i < args.length; i++) {
                        if (byte[].class == args[i].getClass()) {
                            try {
                                UnsafeByteArrayInputStream is = new UnsafeByteArrayInputStream((byte[]) args[i]);
                                args[i] = ExtensionLoader.getExtensionLoader(Serialization.class)
                                        .getExtension(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA)
                                        .deserialize(null, is).readObject();
                            } catch (Exception e) {
                                throw new RpcException("Deserialize argument [" + (i + 1) + "] failed.", e);
                            }
                        } else {
                            throw new RpcException(
                                    "Generic serialization [" +
                                            Constants.GENERIC_SERIALIZATION_NATIVE_JAVA +
                                            "] only support message type " +
                                            byte[].class +
                                            " and your message type is " +
                                            args[i].getClass());
                        }
                    }

                    // 如果 generic = bean ，反序列化参数   JavaBeanDescriptor -> 方法参数
                } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof JavaBeanDescriptor) {
                            args[i] = JavaBeanSerializeUtil.deserialize((JavaBeanDescriptor) args[i]);
                        } else {
                            throw new RpcException(
                                    "Generic serialization [" +
                                            Constants.GENERIC_SERIALIZATION_BEAN +
                                            "] only support message type " +
                                            JavaBeanDescriptor.class.getName() +
                                            " and your message type is " +
                                            args[i].getClass().getName());
                        }
                    }
                }

                //------------------------- 方法调用及调用结果处理 -------------------------------------/

                // 方法参数转换完毕，进行方法调用，注意此时创建了一个新的 RpcInvocation 对象。$invoke 泛化调用被转为具体的普通调用
                Result result = invoker.invoke(new RpcInvocation(method, args, inv.getAttachments()));

                // 如果调用结果有异常，并且非GenericException异常，则使用 GenericException 包装
                if (result.hasException() && !(result.getException() instanceof GenericException)) {
                    return new RpcResult(new GenericException(result.getException()));
                }

                // generic=nativejava的情况下，序列化结果， 结果 -> btyp[]
                if (ProtocolUtils.isJavaGenericSerialization(generic)) {
                    try {
                        UnsafeByteArrayOutputStream os = new UnsafeByteArrayOutputStream(512);
                        ExtensionLoader.getExtensionLoader(Serialization.class)
                                .getExtension(Constants.GENERIC_SERIALIZATION_NATIVE_JAVA)
                                .serialize(null, os).writeObject(result.getValue());
                        return new RpcResult(os.toByteArray());
                    } catch (IOException e) {
                        throw new RpcException("Serialize result failed.", e);
                    }

                    // generic=bean 的情况下，序列化结果， 结果 -> JavaBeanDescriptor
                } else if (ProtocolUtils.isBeanGenericSerialization(generic)) {
                    return new RpcResult(JavaBeanSerializeUtil.serialize(result.getValue(), JavaBeanAccessor.METHOD));

                    // generic=true 的情况下，序列化结果，Pojo -> Map
                } else {
                    return new RpcResult(PojoUtils.generalize(result.getValue()));
                }
            } catch (NoSuchMethodException e) {
                throw new RpcException(e.getMessage(), e);
            } catch (ClassNotFoundException e) {
                throw new RpcException(e.getMessage(), e);
            }
        }

        // 普通调用
        return invoker.invoke(inv);
    }

}
