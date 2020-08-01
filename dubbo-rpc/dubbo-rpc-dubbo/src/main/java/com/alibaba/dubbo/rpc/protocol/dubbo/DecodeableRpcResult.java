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
package com.alibaba.dubbo.rpc.protocol.dubbo;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.utils.Assert;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * 可解码的 RpcResult 实现类
 * 说明：
 * 当服务提供者者，返回服务消费者调用结果，前者编码的 RpcResult 对象，后者解码成 DecodeableRpcResult 对象
 */
public class DecodeableRpcResult extends RpcResult implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);
    /**
     * Dubbo 通道
     */
    private Channel channel;
    /**
     * Serialization 类型编号
     */
    private byte serializationType;
    /**
     * 输入流
     */
    private InputStream inputStream;
    /**
     * 请求
     */
    private Response response;
    /**
     * Invocation 对象
     */
    private Invocation invocation;

    /**
     * 是否已经解码完成
     */
    private volatile boolean hasDecoded;

    public DecodeableRpcResult(Channel channel, Response response, InputStream is, Invocation invocation, byte id) {
        Assert.notNull(channel, "channel == null");
        Assert.notNull(response, "response == null");
        Assert.notNull(is, "inputStream == null");
        this.channel = channel;
        this.response = response;
        this.inputStream = is;
        this.invocation = invocation;
        this.serializationType = id;
    }

    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * 解码
     *
     * @param channel channel.
     * @param input   input stream.
     * @return
     * @throws IOException
     */
    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {

        // 通过序列化器获取 输入流
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType).deserialize(channel.getUrl(), input);

        // 反序列化响应类型
        byte flag = in.readByte();

        switch (flag) {
            // 无返回值
            case DubboCodec.RESPONSE_NULL_VALUE:
                break;
            // 有返回值
            case DubboCodec.RESPONSE_VALUE:
                try {
                    Type[] returnType = RpcUtils.getReturnTypes(invocation);

                    // 设置结果
                    setValue(returnType == null || returnType.length == 0 ?
                            in.readObject() :
                            // 返回结果:Type[]{method.getReturnType(), method.getGenericReturnType()}
                            (returnType.length == 1 ? in.readObject((Class<?>) returnType[0]) : in.readObject((Class<?>) returnType[0], returnType[1])));

                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;

            // 有异常
            case DubboCodec.RESPONSE_WITH_EXCEPTION:
                try {
                    Object obj = in.readObject();
                    if (obj instanceof Throwable == false) {
                        throw new IOException("Response data error, expect Throwable, but get " + obj);
                    }
                    setException((Throwable) obj);
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;

            // 返回值为空，且携带了 attachments 集合
            case DubboCodec.RESPONSE_NULL_VALUE_WITH_ATTACHMENTS:
                try {

                    // 反序列化 attachments 集合，并存储起来
                    setAttachments((Map<String, String>) in.readObject(Map.class));
                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;

            // 返回值不为空，且携带了 attachments 集合
            case DubboCodec.RESPONSE_VALUE_WITH_ATTACHMENTS:
                try {

                    // 获取返回值类型
                    Type[] returnType = RpcUtils.getReturnTypes(invocation);

                    // 反序列化调用结果，并保存起来
                    setValue(returnType == null || returnType.length == 0 ? in.readObject() : (returnType.length == 1 ? in.readObject((Class<?>) returnType[0]) : in.readObject((Class<?>) returnType[0], returnType[1])));

                    // 反序列化 attachments 集合，并存储起来
                    setAttachments((Map<String, String>) in.readObject(Map.class));

                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;

            // 异常对象不为空，且携带了 attachments 集合
            case DubboCodec.RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS:
                try {
                    // 反序列化异常对象
                    Object obj = in.readObject();
                    if (obj instanceof Throwable == false) {
                        throw new IOException("Response data error, expect Throwable, but get " + obj);
                    }

                    // 设置异常对象
                    setException((Throwable) obj);

                    // 反序列化 attachments 集合，并存储起来
                    setAttachments((Map<String, String>) in.readObject(Map.class));

                } catch (ClassNotFoundException e) {
                    throw new IOException(StringUtils.toString("Read response data failed.", e));
                }
                break;
            default:
                throw new IOException("Unknown result flag, expect '0' '1' '2', get " + flag);
        }
        if (in instanceof Cleanable) {
            ((Cleanable) in).cleanup();
        }
        return this;
    }

    /**
     * 解码
     *
     * @throws Exception
     */
    @Override
    public void decode() throws Exception {
        if (!hasDecoded && channel != null && inputStream != null) {
            try {

                // 执行反序列化操作
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc result failed: " + e.getMessage(), e);
                }

                // 反序列化失败，设置 CLIENT_ERROR 状态到 Response 对象中
                response.setStatus(Response.CLIENT_ERROR);

                // 设置异常信息
                response.setErrorMessage(StringUtils.toString(e));
            } finally {
                hasDecoded = true;
            }
        }
    }

}
