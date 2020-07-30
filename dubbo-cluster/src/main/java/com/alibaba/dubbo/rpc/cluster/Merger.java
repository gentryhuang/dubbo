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
package com.alibaba.dubbo.rpc.cluster;

import com.alibaba.dubbo.common.extension.SPI;

/**
 * 将对象数组合并成一个对象，是Dubbo的一个扩展点
 * 注意：
 * 没有默认的扩展点。内置的Merger大致分为四类： Array、Set、List、Map
 * 说明：
 * 当一个接口有多种实现时，消费者需要同时引用不同的实现时，可以用group引用多个不同实现。如果我们需要并行调用不同group的服务，并且要把结果集合起来，
 * 则就需要用到Merger特性，它实现了多个服务调用后结果的合并逻辑。合并结果其实在业务层可以自己实现，不过dubbo直接把这个逻辑封装在了Merger中，
 * 作为了一个扩展点，这简化了业务开放的复杂度。
 *
 * @param <T>
 */
@SPI
public interface Merger<T> {

    /**
     * 合并T数组
     *
     * @param items
     * @return
     */
    T merge(T... items);

}
