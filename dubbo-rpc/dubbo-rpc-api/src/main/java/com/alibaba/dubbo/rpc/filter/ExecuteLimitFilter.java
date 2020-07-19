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
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.concurrent.Semaphore;

/**
 * ThreadLimitInvokerFilter，用于限制每个服务中每个方法（或某个方法）的最大并发数（占用线程池线程数），有接口级别和方法级别的配置方式
 * 说明：
 * ExecuteLimitFilter基于RpcStatus.semaphore（信号量属性），判断若超过最大可并发数，则抛出异常
 * 配置：
 * <dubbo:service executes=""/>或<dubbo:method executes=""/>
 */
@Activate(group = Constants.PROVIDER, value = Constants.EXECUTES_KEY)
public class ExecuteLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得URL
        URL url = invoker.getUrl();
        // 获得方法名
        String methodName = invocation.getMethodName();

        // 信号量
        Semaphore executesLimit = null;

        // 是否获得信号量
        boolean acquireResult = false;

        // 获得服务提供者当前方法最大可并发请求数
        int max = url.getMethodParameter(methodName, Constants.EXECUTES_KEY, 0);

        // 最大可并发请求数大于0
        if (max > 0) {

            // 基于服务URL + 方法纬度，获得RpcStatus
            RpcStatus count = RpcStatus.getStatus(url, invocation.getMethodName());
          /**  如果当前使用的线程数已经大于等于设置的阈值，就直接抛出异常
            * if (count.getActive() >= max) {
             *
             * http://manzhizhen.iteye.com/blog/2386408
             * use semaphore for concurrency control (to limit thread number) // 用户semaphore 将比较和+1做成原子的，控制并发
             */
            executesLimit = count.getSemaphore(max);

            // 获取不到信号量，则抛出异常
            if (executesLimit != null && !(acquireResult = executesLimit.tryAcquire())) {
                throw new RpcException("Failed to invoke method " + invocation.getMethodName() + " in provider " + url + ", cause: The service using threads greater than <dubbo:service executes=\"" + max + "\" /> limited.");
            }
        }

        long begin = System.currentTimeMillis();
        boolean isSuccess = true;

        // 调用开始计数
        RpcStatus.beginCount(url, methodName);

        try {
            // 放行
            Result result = invoker.invoke(invocation);
            return result;
        } catch (Throwable t) {
            // 标记失败
            isSuccess = false;
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RpcException("unexpected exception when ExecuteLimitFilter", t);
            }
        } finally {

            // 调用结束的计数 [调用失败/成功，看isSuccess的值]
            RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, isSuccess);

            // 释放信号量
            if (acquireResult) {
                executesLimit.release();
            }
        }
    }

}
