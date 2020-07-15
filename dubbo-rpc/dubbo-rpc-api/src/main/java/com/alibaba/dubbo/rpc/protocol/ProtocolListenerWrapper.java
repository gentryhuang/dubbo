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
package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.ExporterListener;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.InvokerListener;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.listener.ListenerExporterWrapper;
import com.alibaba.dubbo.rpc.listener.ListenerInvokerWrapper;

import java.util.Collections;
import java.util.List;

/**
 * ListenerProtocol
 */
public class ProtocolListenerWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolListenerWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    /**
     * 用于给Exporter增加ExporterListener，监听Exporter暴露完成和取消暴露完成
     *
     * @param invoker Service invoker
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        // registry协议开头的服务暴露逻辑直接返回
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        /**
         * 1 InjvmProtocol#export(invoker)方法执行之前的切面方法： ProtocolListenerWrapper#export
         * 2 获得ExporterListener的自适应扩展实现
         * 3 创建带 ExporterListener的ListenerExporterWrapper,用来监控服务暴露完毕后的回调操作【实现接口方法：com.alibaba.dubbo.rpc.ExporterListener#exported(com.alibaba.dubbo.rpc.Exporter)，
         * exported 方法会被调用】*/
        Exporter<T> export = protocol.export(invoker);
        List<ExporterListener> exporterListeners = Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)
                .getActivateExtension(invoker.getUrl(), Constants.EXPORTER_LISTENER_KEY));
        return new ListenerExporterWrapper<T>(export, exporterListeners);
    }

    /**
     * @param type Service class
     * @param url  URL address for the remote service
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 如果是注册中心协议，直接进入 ProtocolFilterWrapper#refer方法，本地引用服务不符合这个判断。远程引用服务会符合这个条件
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        // 引用服务
        Invoker<T> invoker = protocol.refer(type, url);
        // 获得 InvokerListener 监听器数组。我们可以自定义 InvokerListener 实现，并配置 @Activate注解或者xml中listener属性
        List<InvokerListener> listeners = Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(InvokerListener.class).getActivateExtension(url, Constants.INVOKER_LISTENER_KEY));
        // 创建带 InvokerListener的 ListenerInvokerWrapper对象。在这个过程会执行 com.alibaba.dubbo.rpc.InvokerListener.referred(invoker)方法
        return new ListenerInvokerWrapper<T>(invoker, listeners);
    }

    /**
     * 关闭，没有实际逻辑，只是包了一层
     */
    @Override
    public void destroy() {
        protocol.destroy();
    }

}
