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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * InternalThreadLocal
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link InternalThread}.
 * <p></p>
 * Internally, a {@link InternalThread} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * <p></p>
 * This design is learning from {@see io.netty.util.concurrent.FastThreadLocal} which is in Netty.
 */
public class InternalThreadLocal<V> {

    /**
     * 保存待删除集合 Set<InternalThreadLocal> 的位置
     */
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    private final int index;

    /**
     * 存储数据下标位置
     */
    public InternalThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Removes all {@link InternalThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    @SuppressWarnings("unchecked")
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                Set<InternalThreadLocal<?>> variablesToRemove = (Set<InternalThreadLocal<?>>) v;
                InternalThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new InternalThreadLocal[variablesToRemove.size()]);
                for (InternalThreadLocal<?> tlv : variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    /**
     * 将当前InternalThreadLocal记录到InternalThreadLocalMap 中的待删除集合中。这样就可以方便管理对象及内存的释放。
     *
     * @param threadLocalMap
     * @param variable
     */
    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, InternalThreadLocal<?> variable) {
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<InternalThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<InternalThreadLocal<?>, Boolean>());
            // 添加待删除集合
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<InternalThreadLocal<?>>) v;
        }

        variablesToRemove.add(variable);
    }

    /**
     * 从待删除的集合移除指定的 InternalThreadLocal
     *
     * @param threadLocalMap
     * @param variable
     */
    @SuppressWarnings("unchecked")
    private static void removeFromVariablesToRemove(InternalThreadLocalMap threadLocalMap, InternalThreadLocal<?> variable) {

        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        Set<InternalThreadLocal<?>> variablesToRemove = (Set<InternalThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        // 获取当前线程绑定的InternalThreadLocalMap
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 根据当前InternalThreadLocal对象的index字段，从InternalThreadLocalMap中读取相应的数据
        Object v = threadLocalMap.indexedVariable(index);
        // 如果非UNSET，则表示读取到了有效数据，直接返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }
        // 读取到UNSET值，则会调用initialize()方法进行初始化，
        // 其中首先会调用initialValue()方法进行初始化，然后会调用setIndexedVariable()方法和addToVariablesToRemove()方法存储初始化得到的值
        return initialize(threadLocalMap);
    }

    /**
     * 在 RpcContext 中，LOCAL 和 SERVER_LOCAL 两个 InternalThreadLocal 类型的字段都实现了 initialValue() 方法，它们的实现都是创建并返回 RpcContext 对象。
     *
     * @param threadLocalMap
     * @return
     */
    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 设置 数据
        threadLocalMap.setIndexedVariable(index, v);
        // 加入到待删除集合中
        addToVariablesToRemove(threadLocalMap, this);

        return v;
    }

    /**
     * 为当前线程绑定的 InternalThreadLocalMap 设置 值
     * Sets the value for the current thread.
     */
    public final void set(V value) {
        // 如果要存储的值为null或是UNSERT，则直接清除
        if (value == null || value == InternalThreadLocalMap.UNSET) {
            remove();
        } else {
            // 获取当前线程绑定的InternalThreadLocalMap
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            // 将value存储到InternalThreadLocalMap.indexedVariables集合中
            if (threadLocalMap.setIndexedVariable(index, value)) {
                // 将当前InternalThreadLocal记录到待删除集合中
                addToVariablesToRemove(threadLocalMap, this);
            }
        }
    }

    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    @SuppressWarnings("unchecked")
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        Object v = threadLocalMap.removeIndexedVariable(index);
        removeFromVariablesToRemove(threadLocalMap, this);

        if (v != InternalThreadLocalMap.UNSET) {
            try {
                onRemoval((V) v);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}.
     */
    protected void onRemoval(@SuppressWarnings("unused") V value) throws Exception {
    }
}
