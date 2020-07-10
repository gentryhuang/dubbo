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
package com.alibaba.dubbo.rpc.protocol.dubbo.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.StaticContext;
import com.alibaba.dubbo.rpc.protocol.dubbo.FutureAdapter;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Future;

/**
 * 实现Filter 接口，事件通知过滤器。这里通过 @Activate(group = Constants.CONSUMER)注解指定，只有服务消费者才生效该过滤器
 */
@Activate(group = Constants.CONSUMER)
public class FutureFilter implements Filter {

    protected static final Logger logger = LoggerFactory.getLogger(FutureFilter.class);

    /*
      oninvoke方法：
        必须具有与真实的被调用方法sayHello相同的入参列表：例如，oninvoke(String name)
      onreturn方法：
        至少要有一个入参且第一个入参必须与sayHello的返回类型相同，接收返回结果：例如，onreturnWithoutParam(String result)
        可以有多个参数，多个参数的情况下，第一个后边的所有参数都是用来接收sayHello入参的：例如， onreturn(String result, String name)
      onthrow方法：
        至少要有一个入参且第一个入参类型为Throwable或其子类，接收返回结果；例如，onthrow(Throwable ex)
        可以有多个参数，多个参数的情况下，第一个后边的所有参数都是用来接收sayHello入参的：例如，onthrow(Throwable ex, String name)
     */


    /*
        <!-- 事件通知服务,实现 Notify 接口，实现接口中通知方法：oninvoke（调用之前）、onreturnWithoutParam（调用之后）、onreturn（调用之后）、onthrow（出现异常，只会在provider返回的RpcResult中含有Exception对象时，才会执行） -->
          <bean id="notifyService"  class="com.alibaba.dubbo.demo.consumer.eventnotify.NotifyService"/>

          <!-- 引用服务 -->
          <dubbo:reference id="demoService" check="false" interface="com.alibaba.dubbo.demo.DemoService">
              <!-- 配置事件通知： oninvoke、onreturn、onthrow -->
               <dubbo:method name="sayHello" timeout="60000" oninvoke="notifyService.oninvoke" onreturn="notifyService.onreturnWithoutParam" onthrow="notifyService.onthrow"/>
          </dubbo:reference>
     */

    /**
     * 服务消费方调用拦截 【注意：该Filter 主要用在事件通知中，当然调用是必须走该方法的】
     * <p>
     * 说明：FutureFilter只用在consumer端；不管是同步调用还是异步调用，都会走FutureFilter，执行的过程：
     * <p>
     * 1 走 oninvoke 方法
     * 2 走 真正的目标方法
     * 3 根据同步还是异步走不同的逻辑
     * 3.1 同步的话：目标方法的返回结果RpcResult中是否有exception对象，如果有，执行onthrow(Throwable ex, String name)，如果没有，执行onreturn (String result)
     * 3.2
     *
     * @param invoker    service
     * @param invocation invocation.
     * @return
     * @throws RpcException
     */
    @Override
    public Result invoke(final Invoker<?> invoker, final Invocation invocation) throws RpcException {
        // 获得是否异步调用
        final boolean isAsync = RpcUtils.isAsync(invoker.getUrl(), invocation);

        // 触发前置方法，即 执行 xxxService.oninvoke方法
        fireInvokeCallback(invoker, invocation);

        // 调用服务提供者
        Result result = invoker.invoke(invocation);

        // 调用服务之后

        // 异步回调
        if (isAsync) {
            asyncCallback(invoker, invocation);
            // 同步回调用
        } else {
            syncCallback(invoker, invocation, result);
        }

        // 返回结果，如果是异步调用或单向调用，结果是空的
        return result;
    }

    /**
     * 同步回调
     *
     * @param invoker    Invoker 对象
     * @param invocation Invocation 对象
     * @param result     RPC 调用结果
     */
    private void syncCallback(final Invoker<?> invoker, final Invocation invocation, final Result result) {
        // 有异常，触发异常回调
        if (result.hasException()) {
            //  调用服务之后：如果返回结果异常信息（注意：如果是consumer自己throw的异常，不会走到这里），直接执行xxxService.onthrow方法
            fireThrowCallback(invoker, invocation, result.getException());
            // 正常，触发正常回调
        } else {
            // 调用服务之后：如果返回值正常，执行xxxService.onreturn方法
            fireReturnCallback(invoker, invocation, result.getValue());
        }
    }

    /**
     * 异步回调
     *
     * @param invoker    Invoker 对象
     * @param invocation Invocation 对象
     */
    private void asyncCallback(final Invoker<?> invoker, final Invocation invocation) {

        // 获得 Future 对象 【由于异步不知道服务提供者什么时候会执行完毕，所以要添加回调等待服务提供者返回结果】
        Future<?> f = RpcContext.getContext().getFuture();

        // 调用服务之后：设置回调ResponseCallback对象到DefaultFuture中，当provider返回响应时，执行DefaultFuture.doReceived方法，该方法会调用ResponseCallback对象的done或者caught方法
        if (f instanceof FutureAdapter) {
            ResponseFuture future = ((FutureAdapter<?>) f).getFuture();

            // 设置回调 【这里的future对象是DubboInvoker中创建好的DefaultFuture对象】
            future.setCallback(new ResponseCallback() {

                /**
                 * 正常回调
                 * @param rpcResult
                 */
                @Override
                public void done(Object rpcResult) {
                    if (rpcResult == null) {
                        logger.error(new IllegalStateException("invalid result value : null, expected " + Result.class.getName()));
                        return;
                    }
                    ///must be rpcResult
                    if (!(rpcResult instanceof Result)) {
                        logger.error(new IllegalStateException("invalid result type :" + rpcResult.getClass() + ", expected " + Result.class.getName()));
                        return;
                    }
                    Result result = (Result) rpcResult;
                    if (result.hasException()) {
                        fireThrowCallback(invoker, invocation, result.getException());
                    } else {
                        fireReturnCallback(invoker, invocation, result.getValue());
                    }
                }

                /**
                 * 触发异常回调方法
                 * @param exception
                 */
                @Override
                public void caught(Throwable exception) {
                    fireThrowCallback(invoker, invocation, exception);
                }
            });
        }
    }

    /**
     * 触发前置方法：
     * 反射执行xxxService.oninvoke方法：必须具有与真实的被调用方法相同的入参列表。
     *
     * @param invoker    Invoker 对象
     * @param invocation Invocation 对象
     */
    private void fireInvokeCallback(final Invoker<?> invoker, final Invocation invocation) {
        // 获得前置方法和对象
        final Method onInvokeMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_INVOKE_METHOD_KEY));
        final Object onInvokeInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_INVOKE_INSTANCE_KEY));
        // 没有配置就直接什么都不做
        if (onInvokeMethod == null && onInvokeInst == null) {
            return;
        }
        if (onInvokeMethod == null || onInvokeInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onInvokeMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onInvokeMethod.isAccessible()) {
            onInvokeMethod.setAccessible(true);
        }

        // 调用前置方法 【通过反射调用前置方法】

        // 获取真实方法传入的参数
        Object[] params = invocation.getArguments();
        try {
            // 反射调用
            onInvokeMethod.invoke(onInvokeInst, params);
        } catch (InvocationTargetException e) {
            // 触发异常回调
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            // 触发异常回调
            fireThrowCallback(invoker, invocation, e);
        }
    }

    /**
     * 触发正常回调方法
     * 反射执行xxxService.onreturn方法：至少要有一个入参来接收返回结果
     *
     * @param invoker
     * @param invocation
     * @param result
     */
    private void fireReturnCallback(final Invoker<?> invoker, final Invocation invocation, final Object result) {

        // 获得 onreturn 方法和对象
        final Method onReturnMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_METHOD_KEY));
        final Object onReturnInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_RETURN_INSTANCE_KEY));

        //not set onreturn callback
        if (onReturnMethod == null && onReturnInst == null) {
            return;
        }

        if (onReturnMethod == null || onReturnInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onreturn callback config , but no such " + (onReturnMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onReturnMethod.isAccessible()) {
            onReturnMethod.setAccessible(true);
        }

        // 真实方法传入的参数
        Object[] args = invocation.getArguments();

        Object[] params;
        Class<?>[] rParaTypes = onReturnMethod.getParameterTypes();

        if (rParaTypes.length > 1) {

            // onreturn(xx, Object[]) 两个参数：第一个参数与真实方法返回结果类型相同【用来接收返回结果】，第二个接收所有的真实请求参数
            if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                params = new Object[2];
                params[0] = result;
                params[1] = args;

                // onreturn(xx, Object... args) 多个参数：第一个参数与真实方法的返回结果类型相同，后边几个接收所有的真实请求参数
            } else {
                params = new Object[args.length + 1];
                params[0] = result;
                System.arraycopy(args, 0, params, 1, args.length);
            }

            // onreturn(xx) 只有一个参数：接收返回执行结果
        } else {
            params = new Object[]{result};
        }

        // 调用方法
        try {
            onReturnMethod.invoke(onReturnInst, params);
        } catch (InvocationTargetException e) {
            fireThrowCallback(invoker, invocation, e.getTargetException());
        } catch (Throwable e) {
            fireThrowCallback(invoker, invocation, e);
        }
    }

    /**
     * 触发异常回调用方法
     * 反射执行xxxService.onthrow方法：至少要有一个入参且第一个入参类型为Throwable或其子类，接收返回结果
     *
     * @param invoker
     * @param invocation
     * @param exception
     */
    private void fireThrowCallback(final Invoker<?> invoker, final Invocation invocation, final Throwable exception) {

        // 获得 onthrow 方法和对象
        final Method onthrowMethod = (Method) StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_METHOD_KEY));
        final Object onthrowInst = StaticContext.getSystemContext().get(StaticContext.getKey(invoker.getUrl(), invocation.getMethodName(), Constants.ON_THROW_INSTANCE_KEY));

        //onthrow callback not configured
        if (onthrowMethod == null && onthrowInst == null) {
            return;
        }
        if (onthrowMethod == null || onthrowInst == null) {
            throw new IllegalStateException("service:" + invoker.getUrl().getServiceKey() + " has a onthrow callback config , but no such " + (onthrowMethod == null ? "method" : "instance") + " found. url:" + invoker.getUrl());
        }
        if (!onthrowMethod.isAccessible()) {
            onthrowMethod.setAccessible(true);
        }
        Class<?>[] rParaTypes = onthrowMethod.getParameterTypes();

        // 符合异常
        if (rParaTypes[0].isAssignableFrom(exception.getClass())) {
            try {

                // 真实方法的参数列表
                Object[] args = invocation.getArguments();

                Object[] params;

                if (rParaTypes.length > 1) {

                    // onthrow(xx, Object[]) 两个参数：第一个参数接收exception，第二个接收所有的真实请求参数
                    if (rParaTypes.length == 2 && rParaTypes[1].isAssignableFrom(Object[].class)) {
                        params = new Object[2];
                        params[0] = exception;
                        params[1] = args;
                        // onthrow(xx, Object... args) 多个参数：第一个参数接收exception，后边几个接收所有的真实请求参数
                    } else {
                        params = new Object[args.length + 1];
                        params[0] = exception;
                        System.arraycopy(args, 0, params, 1, args.length);
                    }
                    // onthrow(xx) 只有一个参数：接收exception
                } else {
                    params = new Object[]{exception};
                }

                // 调用方法
                onthrowMethod.invoke(onthrowInst, params);
            } catch (Throwable e) {
                logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), e);
            }
        } else {
            logger.error(invocation.getMethodName() + ".call back method invoke error . callback method :" + onthrowMethod + ", url:" + invoker.getUrl(), exception);
        }
    }
}
