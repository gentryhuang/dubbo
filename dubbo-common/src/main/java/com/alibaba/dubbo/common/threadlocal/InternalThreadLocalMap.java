/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.alibaba.dubbo.common.threadlocal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the threadLocal variables for Netty and all {@link InternalThread}s.
 * Note that this class is for internal use only. Use {@link InternalThread}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap {

    /**
     * 用于存储绑定到当前线程的数据
     */
    private Object[] indexedVariables;

    /**
     * 当使用原生 Thread 的时候，会使用该 ThreadLocal 存储 InternalThreadLocalMap，这是一个降级策略。
     */
    private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();
    /**
     * 自增索引，用于计算下次存储到 indexedVariables 数组中的位置，这是一个静态字段。
     */
    private static final AtomicInteger nextIndex = new AtomicInteger();

    /**
     * 当一个与线程绑定的值被删除之后，会被设置为 UNSET 值
     */
    public static final Object UNSET = new Object();


    /**
     * 构造方法，初始化一个数组
     */
    private InternalThreadLocalMap() {
        indexedVariables = newIndexedVariableTable();
    }

    /**
     * 获取线程关联的 InternalThreadLocalMap
     *
     * @return
     */
    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        // 如果是InternalThread类型，直接获取InternalThreadLocalMap返回
        if (thread instanceof InternalThread) {
            return ((InternalThread) thread).threadLocalMap();
        }

        // 原生Thread则需要通过ThreadLocal获取InternalThreadLocalMap
        return slowThreadLocalMap.get();
    }

    /**
     * 获取线程关联的 InternalThreadLocalMap，没有就创建并关联到当前线程
     *
     * @return
     */
    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            return fastGet((InternalThread) thread);
        }
        return slowGet();
    }

    /**
     * 获取 InternalThread 线程绑定的 InternalThreadLocalMap
     *
     * @param thread
     * @return
     */
    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }


    /**
     * 从本地线程获取 InternalThreadLocalMap
     *
     * @return
     */
    private static InternalThreadLocalMap slowGet() {
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    /**
     * 移除线程绑定的 InternalThreadLocalMap
     */
    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    /**
     * 销毁 InternalThreadLocalMap 中的线程本地变量
     */
    public static void destroy() {
        slowThreadLocalMap = null;
    }

    /**
     * 计算下次存储到 indexedVariables 数组中的位置
     *
     * @return
     */
    public static int nextVariableIndex() {
        int index = nextIndex.getAndIncrement();
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    /**
     * 获取上次使用的数组下标
     *
     * @return
     */
    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }


    /**
     * 读取 index 下标对应的值
     *
     * @param index
     * @return
     */
    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * 在index 位置 设置值
     *
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            // 将value存储到index指定的位置
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            // 当index超过indexedVariables数组的长度时，需要对indexedVariables数组进行扩容
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    /**
     * 移除 index 位置的值
     *
     * @param index
     * @return
     */
    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    /**
     * InternalThreadLocalMap 存储数据大小
     *
     * @return
     */
    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }

        //the fist element in `indexedVariables` is a set to keep all the InternalThreadLocal to remove
        //look at method `addToVariablesToRemove`
        return count - 1;
    }

    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

    /**
     * 扩容并设置值
     *
     * @param index
     * @param value
     */
    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        newCapacity++;

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }
}
