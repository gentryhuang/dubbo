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
package com.alibaba.dubbo.remoting.zookeeper.support;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 实现 ZookeeperClient 接口，Zookeeper 客户端抽象类，实现通用的逻辑。
 *
 * @param <TargetChildListener> 泛型
 */
public abstract class AbstractZookeeperClient<TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    /**
     * 注册中心 URL
     */
    private final URL url;

    /**
     * StateListener 状态监听器集合
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();

    /**
     * ChildListener 集合
     * <p>
     * key1：节点路径
     * key2：ChildListener 对象
     * value ：监听器具体对象。不同 Zookeeper 客户端，实现会不同。CuratorZookeeperClient的是CuratorWatcher;ZkclientZookeeperClient 的是 IZkChildListener
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();

    /**
     * 是否关闭
     */
    private volatile boolean closed = false;

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void create(String path, boolean ephemeral) {
        if (!ephemeral) {
            // 如果要创建的节点类型非临时节点，那么这里要检测节点是否存在。临时节点有序号，不需要考虑覆盖问题
            if (checkExists(path)) {
                return;
            }
        }

        int i = path.lastIndexOf('/');
        if (i > 0) {
            // 递归创建上一级路径
            create(path.substring(0, i), false);
        }
        // 根据 ephemeral 的值创建临时或持久节点
        if (ephemeral) {
            createEphemeral(path);
        } else {
            createPersistent(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    /**
     * 1 根据path从ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners获取ConcurrentMap<ChildListener, TargetChildListener>，没有就创建
     * 2 根据ChildListener获取TargetChildListener，没有就创建，TargetChildListener是真正的监听path的子节点变化的监听器
     * createTargetChildListener(String path, final ChildListener listener)：创建一个真正的用来执行当path节点的子节点发生变化时的逻辑
     * 3 addTargetChildListener(path, targetListener)：将刚刚创建出来的子节点监听器订阅path的变化，这样之后，path的子节点发生了变化时，TargetChildListener才会执行相应的逻辑。
     * 而实际上TargetChildListener又会调用ChildListener的实现类的childChanged(String parentPath, List<String> currentChilds)方法，而该实现类【ChildListener的实现类】正好是ZookeeperRegistry中实现的匿名内部类，
     * 在该匿名内部类的childChanged(String parentPath, List<String> currentChilds)方法中，调用了ZookeeperRegistry.notify(URL url, NotifyListener listener, List<URL> urls)
     */
    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        // 1 一顿获取和创建：ConcurrentMap<categorypath, ConcurrentMap<ZookeeperRegistry的内部类ChildListener实例, TargetChildListener>> childListeners，这里主要是创建TargetChildListener
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        // 获得是否已经有该监听器
        TargetChildListener targetListener = listeners.get(listener);
        // 监听器不存在，进行创建
        if (targetListener == null) {
            /**
             * 1 创建一个监听path子节点的watcher【CuratorZookeeperClient实现】或 IZkChildListener 【ZkclientZookeeperClient实现】
             * 2 当path下有子节点变化时，调用listener（即传入的ZookeeperRegistry的内部类ChildListener实例的childChanged(String parentPath, List<String> currentChilds)方法）
             */
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        // 向 Zookeeper ，真正发起订阅，即为 path添加TargetChildListener监听器实例
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                // 向 Zookeeper ，真正发起取消订阅
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    /**
     * StateListener 数组，回调
     *
     * @param state 状态
     */
    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    /**
     * 关闭Zookeeper连接
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * 关闭 Zookeeper 连接
     */
    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract boolean checkExists(String path);

    /**
     * 抽象方法，创建真正的 ChildListener 对象。因为，每个 Zookeeper 的库，实现不同。
     *
     * @param path
     * @param listener
     * @return
     */
    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    /**
     * 向 Zookeeper ，真正发起订阅
     *
     * @param path
     * @param listener
     * @return
     */
    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    /**
     * 向 Zookeeper ，真正发起取消订阅
     *
     * @param path
     * @param listener
     */
    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

}
