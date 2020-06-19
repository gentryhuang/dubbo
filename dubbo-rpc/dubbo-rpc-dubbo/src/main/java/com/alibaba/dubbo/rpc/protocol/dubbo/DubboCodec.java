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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.UnsafeByteArrayInputStream;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.codec.ExchangeCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcInvocation;

import java.io.IOException;
import java.io.InputStream;

import static com.alibaba.dubbo.rpc.protocol.dubbo.CallbackServiceCodec.encodeInvocationArgument;

/**
 * Dubbo codec.
 */
public class DubboCodec extends ExchangeCodec implements Codec2 {

    /**
     * 协议名
     */
    public static final String NAME = "dubbo";
    /**
     * 协议版本
     */
    public static final String DUBBO_VERSION = Version.getProtocolVersion();
    /**
     * 响应 - 异常
     */
    public static final byte RESPONSE_WITH_EXCEPTION = 0;
    /**
     * 响应 - 正常（空返回）
     */
    public static final byte RESPONSE_VALUE = 1;
    /**
     * 响应 - 正常（有返回）
     */
    public static final byte RESPONSE_NULL_VALUE = 2;
    public static final byte RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS = 3;
    public static final byte RESPONSE_VALUE_WITH_ATTACHMENTS = 4;
    public static final byte RESPONSE_NULL_VALUE_WITH_ATTACHMENTS = 5;
    /**
     * 方法参数
     */
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    /**
     * 方法参数类型
     */
    public static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];
    private static final Logger log = LoggerFactory.getLogger(DubboCodec.class);

    /**
     * 解码内容体
     *
     * @param channel
     * @param is
     * @param header
     * @return
     * @throws IOException
     */
    @Override
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2];

        byte proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.  获得请求或响应编号
        long id = Bytes.bytes2long(header, 4);
        // 解析响应
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            // 如果是心跳事件，进行设置
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            // 设置状态
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                // 正常响应状态
                if (status == Response.OK) {
                    Object data;
                    // 解码心跳事件
                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                        // 解码其它事件
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                        // 解码普通响应
                    } else {
                        DecodeableRpcResult result;
                        // 在当前通信框架（如：Netty）的IO线程，解码。 直接解码
                        if (channel.getUrl().getParameter(Constants.DECODE_IN_IO_THREAD_KEY, Constants.DEFAULT_DECODE_IN_IO_THREAD)) {
                            result = new DecodeableRpcResult(channel, res, is, (Invocation) getRequestData(id), proto);
                            result.decode();
                            // 在 Dubbo ThreadPool 线程，解码。会在DecodeHandler中会调用 DecodeableRpcResult#decode()方法
                        } else {
                            result = new DecodeableRpcResult(channel, res, new UnsafeByteArrayInputStream(readMessageData(is)), (Invocation) getRequestData(id), proto);
                        }
                        data = result;
                    }
                    // 设置结果
                    res.setResult(data);
                    // 异常响应
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode response failed: " + t.getMessage(), t);
                }
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
            // 解析请求
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            // 是否需要响应
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            // 如果是心跳事件，进行设置
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                Object data;
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                // 解码心跳事件
                if (req.isHeartbeat()) {
                    data = decodeHeartbeatData(channel, in);
                    // 解码其它事件
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);
                    // 解码普通请求
                } else {
                    DecodeableRpcInvocation inv;
                    // 在通信框架（如Netty）的IO线程，解码
                    if (channel.getUrl().getParameter(Constants.DECODE_IN_IO_THREAD_KEY, Constants.DEFAULT_DECODE_IN_IO_THREAD)) {
                        inv = new DecodeableRpcInvocation(channel, req, is, proto);
                        inv.decode();
                        // 在 Dubbo ThreadPool 线程，解码，使用 DecodeHandler
                    } else {
                        inv = new DecodeableRpcInvocation(channel, req, new UnsafeByteArrayInputStream(readMessageData(is)), proto);
                    }
                    data = inv;
                }
                req.setData(data);
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode request failed: " + t.getMessage(), t);
                }
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    private byte[] readMessageData(InputStream is) throws IOException {
        if (is.available() > 0) {
            byte[] result = new byte[is.available()];
            is.read(result);
            return result;
        }
        return new byte[]{};
    }

    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data, DUBBO_VERSION);
    }

    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(channel, out, data, DUBBO_VERSION);
    }

    /**
     * 编码内容体。即编码RpcInvocation对象，写入需要编码的字段。对应的解码在{@link DecodeableRpcInvocation}
     *
     * @param channel
     * @param out
     * @param data
     * @param version
     * @throws IOException
     */
    @Override
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        RpcInvocation inv = (RpcInvocation) data;

        // 写入 `dubbo` `path` `version`
        out.writeUTF(version);
        out.writeUTF(inv.getAttachment(Constants.PATH_KEY));
        out.writeUTF(inv.getAttachment(Constants.VERSION_KEY));

        // 写入方法、方法签名、方法参数集合
        out.writeUTF(inv.getMethodName());
        out.writeUTF(ReflectUtils.getDesc(inv.getParameterTypes()));
        Object[] args = inv.getArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                // 调用 CallbackServiceCodec#encodeInvocationArgument(...) 方法，编码参数。主要用于 参数回调 功能
                out.writeObject(encodeInvocationArgument(channel, inv, i));
            }
        }
        // 写入隐式传参集合
        out.writeObject(inv.getAttachments());
    }

    /**
     * 响应，即编码Result对象，写入需要编码的字段。对应的解码在 {@link DecodeableRpcResult}
     *
     * @param channel
     * @param out
     * @param data
     * @param version
     * @throws IOException
     */
    @Override
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        Result result = (Result) data;
        // currently, the version value in Response records the version of Request
        boolean attach = Version.isSupportResponseAttatchment(version);
        Throwable th = result.getException();
        if (th == null) {
            Object ret = result.getValue();
            // 空返回
            if (ret == null) {
                out.writeByte(attach ? RESPONSE_NULL_VALUE_WITH_ATTACHMENTS : RESPONSE_NULL_VALUE);
                // 有返回
            } else {
                out.writeByte(attach ? RESPONSE_VALUE_WITH_ATTACHMENTS : RESPONSE_VALUE);
                out.writeObject(ret);
            }
            // 有异常
        } else {
            out.writeByte(attach ? RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS : RESPONSE_WITH_EXCEPTION);
            out.writeObject(th);
        }

        if (attach) {
            // returns current version of Response to consumer side.
            result.getAttachments().put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());
            out.writeObject(result.getAttachments());
        }
    }
}
