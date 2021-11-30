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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

public class DecodeableRpcResult extends AppResponse implements Codec, Decodeable {

    private static final Logger log = LoggerFactory.getLogger(DecodeableRpcResult.class);
    // 通道
    private Channel channel;
    // 序列化类型
    private byte serializationType;
    // 序列化相关的输入流
    private InputStream inputStream;
    // 响应对象
    private Response response;
    // 调用信息
    private Invocation invocation;
    // 标志是否已经解码
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

    /**
     * 编码不支持
     *
     * @param channel channel.
     * @param output  output stream.
     * @param message message.
     * @throws IOException
     */
    @Override
    public void encode(Channel channel, OutputStream output, Object message) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object decode(Channel channel, InputStream input) throws IOException {
        if (log.isDebugEnabled()) {
            Thread thread = Thread.currentThread();
            log.debug("Decoding in thread -- [" + thread.getName() + "#" + thread.getId() + "]");
        }

        // 1 确定序列化方式，用于反序列化
        ObjectInput in = CodecSupport.getSerialization(channel.getUrl(), serializationType)
                .deserialize(channel.getUrl(), input);

        // 2 读取一个 byte 的标志位，其值可能有 6 种
        byte flag = in.readByte();

        // 3 根据标志位判断当前结果中包含的信息，并调用不同的方法进行处理
        switch (flag) {
            case DubboCodec.RESPONSE_NULL_VALUE:
                break;
            case DubboCodec.RESPONSE_VALUE:
                handleValue(in);
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION:
                handleException(in);
                break;
            case DubboCodec.RESPONSE_NULL_VALUE_WITH_ATTACHMENTS:
                handleAttachment(in);
                break;
            case DubboCodec.RESPONSE_VALUE_WITH_ATTACHMENTS:
                // 根据 RpcInvocation 中记录的返回值类型读取返回结果，并设置到当前类的 result 字段
                handleValue(in);
                // 读取附加信息并设置到当前类的 attachmetns 中
                handleAttachment(in);
                break;
            case DubboCodec.RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS:
                handleException(in);
                handleAttachment(in);
                break;
            default:
                throw new IOException("Unknown result flag, expect '0' '1' '2' '3' '4' '5', but received: " + flag);
        }
        if (in instanceof Cleanable) {
            ((Cleanable) in).cleanup();
        }
        return this;
    }

    @Override
    public void decode() throws Exception {
        // 没有解码，则进行解码
        if (!hasDecoded && channel != null && inputStream != null) {
            try {
                // 解码
                decode(channel, inputStream);
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode rpc result failed: " + e.getMessage(), e);
                }
                response.setStatus(Response.CLIENT_ERROR);
                response.setErrorMessage(StringUtils.toString(e));
            } finally {
                hasDecoded = true;
            }
        }
    }

    /**
     * 处理结果
     *
     * @param in
     * @throws IOException
     */
    private void handleValue(ObjectInput in) throws IOException {
        try {
            // 1 返回结果类型
            Type[] returnTypes;
            if (invocation instanceof RpcInvocation) {
                returnTypes = ((RpcInvocation) invocation).getReturnTypes();
            } else {
                returnTypes = RpcUtils.getReturnTypes(invocation);
            }
            // 2 根据返回结果类型获取结果
            Object value = null;
            if (ArrayUtils.isEmpty(returnTypes)) {
                // This almost never happens?
                value = in.readObject();
            } else if (returnTypes.length == 1) {
                value = in.readObject((Class<?>) returnTypes[0]);
            } else {
                value = in.readObject((Class<?>) returnTypes[0], returnTypes[1]);
            }
            // 3 设置结果 result
            setValue(value);
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleException(ObjectInput in) throws IOException {
        try {
            setException(in.readThrowable());
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void handleAttachment(ObjectInput in) throws IOException {
        try {
            setObjectAttachments(in.readAttachments());
        } catch (ClassNotFoundException e) {
            rethrow(e);
        }
    }

    private void rethrow(Exception e) throws IOException {
        throw new IOException(StringUtils.toString("Read response data failed.", e));
    }
}
