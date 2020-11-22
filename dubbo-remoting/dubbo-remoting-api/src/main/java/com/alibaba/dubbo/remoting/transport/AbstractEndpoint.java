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
package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Resetable;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.transport.codec.CodecAdapter;

/**
 * AbstractEndpoint 实现 Resetable 接口，继承 AbstractPeer 抽象类，端点抽象类。
 */
public abstract class AbstractEndpoint extends AbstractPeer implements Resetable {

    private static final Logger logger = LoggerFactory.getLogger(AbstractEndpoint.class);

    /**
     * 编解码器
     */
    private Codec2 codec;
    /**
     * 超时时间
     */
    private int timeout;
    /**
     * 连接超时时间
     */
    private int connectTimeout;

    public AbstractEndpoint(URL url, ChannelHandler handler) {
        // 调用父类 AbstractPeer 的构造方法
        super(url, handler);
        // 根据URL中的 codec 参数值 获取Codec2的实现类
        this.codec = getChannelCodec(url);
        // 根据 URL 中的 timeout 参数确定 timeout 字段的值，默认 1000
        this.timeout = url.getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // 根据URL中的connect.timeout 参数确定connectTimeout 字段值，默认 3000
        this.connectTimeout = url.getPositiveParameter(Constants.CONNECT_TIMEOUT_KEY, Constants.DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * 基于Dubbo SPI机制，加载对应的Codec实现对象，如：在DubboProtocol中会获得DubboCodec对象
     *
     * @param url
     * @return
     */
    protected static Codec2 getChannelCodec(URL url) {
        String codecName = url.getParameter(Constants.CODEC_KEY, "telnet");
        if (ExtensionLoader.getExtensionLoader(Codec2.class).hasExtension(codecName)) {
            return ExtensionLoader.getExtensionLoader(Codec2.class).getExtension(codecName);
        } else {
            // 注意： Codec接口已经废弃了
            return new CodecAdapter(ExtensionLoader.getExtensionLoader(Codec.class).getExtension(codecName));
        }
    }

    /**
     * 重置属性 即 使用新的 url 属性，可重置 codec timeout connectTimeout 属
     *
     * @param url
     */
    @Override
    public void reset(URL url) {
        if (isClosed()) {
            throw new IllegalStateException("Failed to reset parameters "
                    + url + ", cause: Channel closed. channel: " + getLocalAddress());
        }
        try {
            if (url.hasParameter(Constants.TIMEOUT_KEY)) {
                int t = url.getParameter(Constants.TIMEOUT_KEY, 0);
                if (t > 0) {
                    this.timeout = t;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            if (url.hasParameter(Constants.CONNECT_TIMEOUT_KEY)) {
                int t = url.getParameter(Constants.CONNECT_TIMEOUT_KEY, 0);
                if (t > 0) {
                    this.connectTimeout = t;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            if (url.hasParameter(Constants.CODEC_KEY)) {
                this.codec = getChannelCodec(url);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Deprecated
    public void reset(com.alibaba.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    protected Codec2 getCodec() {
        return codec;
    }

    protected int getTimeout() {
        return timeout;
    }

    protected int getConnectTimeout() {
        return connectTimeout;
    }

}
