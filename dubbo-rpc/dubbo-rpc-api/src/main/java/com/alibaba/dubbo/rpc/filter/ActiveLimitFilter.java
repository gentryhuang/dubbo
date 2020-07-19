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

/**
 * LimitInvokerFilter，消费者端的过滤器，限制的是客户端的并发数。即限制服务中每个方法（或某个方法）在客户端的最大并发数（占用线程池线程数）
 * 说明：
 * ActiveLimitFilter 基于 RpcStatus.active(调用中的次数)属性，判断当前正在调用中的服务的方法的次数
 * 配置：
 * <dubbo:reference actives=""/> 或者 <dubbo:method actives=""/> 或者 <dubbo:service actives=""/>
 * 注意：
 * 如果 actives 属性在 服务端和消费端都配置了，那么就优先消费端
 */
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获取URL
        URL url = invoker.getUrl();
        // 获取方法名
        String methodName = invocation.getMethodName();

        // 获取服务提供者当前方法的最大并发数 todo 这里取的应该是消费方的，只是在合并url的时候把消费方法的合并到提供者的url中了？？？？
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);

        // 基于服务URL + 方法纬度， 获得 RpcStatus 对象
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());


        if (max > 0) {

            /**
             *  获得服务提供者的超时时间 [注意：这里的超时值不占用调用服务的超时时间] ，是用来控制等待请求释放资源的时间，防止等待时间太久。
             *  在极端情况下，调用服务的时间几乎是 2 * timeout
             */

            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);


            long start = System.currentTimeMillis();
            long remain = timeout;

            // 获取当前并发数
            int active = count.getActive();

            /**
             * 如果达到限流阈值，和服务提供者不一样，并不是直接抛出异常，而是先等待直到超时，因为请求是允许有超时时间的。
             */
            if (active >= max) {

                // 使用锁，只允许仅有一个进入等待，其他的都在代码块外阻塞
                synchronized (count) {
                    /**
                     *
                     * 循环获取当前并发数，如果大于限流阈值则等待
                     * 会有两种结果：
                     * 1 某个Invoker在调用结束后，并发把计数器原子-1并唤醒等待线程，会有一个等待状态的线程被唤醒并继续执行逻辑
                     * 2 wait等待超时都没有被唤醒，此时抛出异常
                     */
                    while ((active = count.getActive()) >= max) {
                        try {
                            // 等待，直到超时，或者被唤醒
                            count.wait(remain);
                        } catch (InterruptedException e) {
                        }

                        // 是否超时，超时则抛出异常
                        long elapsed = System.currentTimeMillis() - start;
                        remain = timeout - elapsed;
                        if (remain <= 0) {
                            throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                    + invoker.getInterface().getName() + ", method: "
                                    + invocation.getMethodName() + ", elapsed: " + elapsed
                                    + ", timeout: " + timeout + ". concurrent invokes: " + active
                                    + ". max concurrent invoke limit: " + max);
                        }
                    }
                }
            }
        }

        try {
            long begin = System.currentTimeMillis();
            // 开始计数，并发原子数 + 1
            RpcStatus.beginCount(url, methodName);
            try {
                // 放行
                Result result = invoker.invoke(invocation);

                // 结束计数（调用成功），并发原子数 - 1
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, true);

                return result;
            } catch (RuntimeException t) {
                // 结束计数（调用失败）
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, false);
                throw t;
            }
        } finally {
            // 唤醒等待的相同服务的相同方法的请求
            if (max > 0) {
                synchronized (count) {
                    count.notify();
                }
            }
        }
    }

}
