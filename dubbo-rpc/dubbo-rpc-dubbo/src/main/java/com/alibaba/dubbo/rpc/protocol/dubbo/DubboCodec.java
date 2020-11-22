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
 * DubboCodec,实现Codec2接口，继承ExchangeCodec类，Dubbo编解码器实现类
 * 注意：
 * 在ExchangeCodec 中对 Request 和 Response 的通用解析，即只处理了 Dubbo 协议的请求头。但是它是不满足在 dubbo:// 协议中对 RpcInvocation 和 RpcResult 作为 内容体( Body ) 的编解码的需要的。
 * 并且在 dubbo:// 协议中，支持 参数回调 的特性，也是需要在编解码做一些特殊逻辑。这个由DubboCodec来解决
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
     * 异常响应
     */
    public static final byte RESPONSE_WITH_EXCEPTION = 0;
    /**
     * 正常（空返回）响应
     */
    public static final byte RESPONSE_VALUE = 1;
    /**
     * 正常（有返回）响应
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
     * 解码内容体。将数据包解析成 Request/Response模型
     *
     * @param channel
     * @param is
     * @param header
     * @return
     * @throws IOException
     */
    @Override
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {

        /**
         * 获取消息头中第3个字节，并通过逻辑与运算得到序列化器编号
         * 说明：
         * 1 Dubbo协议中，消息头是一个大小为16的字节数组，其中 header[2] 的字节存储的内容为：第1位：request/response，第2位：2Way，第3-8位：Serialization ID
         * 2 SERIALIZATION_MASK 的值位31，对应的二进制补码为 0001 1111
         * 3 由 0001 1111 & header[2]对应的二进制补码，那么由于 0001 1111 后五位全是1，进行&运算后的结果取决与 header[2]对应的二进制补码的3-8位，即序列化器编号
         */
        byte flag = header[2];
        byte proto = (byte) (flag & SERIALIZATION_MASK);

        //  获得调用编号（请求时生成的一个id，用来标识一次调用），它存储在header字节数组的第5-11字节
        long id = Bytes.bytes2long(header, 4);


        /**
         * 通过逻辑与运算得到调用类型：0 - Response , 1 - Request
         * 说明：
         *  1 FLAG_REQUEST 的值为 -128，对应的补码为 1000 0000
         *  2 1000 0000 & flag，那么得到值取决于flag的第1位，我们知道是 header[2]的第1位正是 request/response的标志位
         */

        //----- 解析响应------/
        if ((flag & FLAG_REQUEST) == 0) {

            // 创建 Response 对象
            Response res = new Response(id);   // 响应标志位被设置，创建 Response 对象

            /**
             * 如果是心跳事件，进行设置
             * 说明：
             * 1 FLAG_EVENT 的值为32，对应的补码为 0010 0000
             * 2 header[2]的第3位表示的正是事件标志，0 - 请求/响应包  1 - 心跳包
             */
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }

            // 获取响应状态，header的第4四个字节就是响应状态标志位
            byte status = header[3];

            // 设置响应状态
            res.setStatus(status);

            try {
                // 通过序列化器编号间接获取输入流
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);

                // 调用过程正常
                if (status == Response.OK) {
                    Object data;

                    // 心跳事件
                    if (res.isHeartbeat()) {
                        // 反序列化心跳数据
                        data = decodeHeartbeatData(channel, in);

                        // 反序列化其他事件数据
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);

                        // 解码普通响应
                    } else {

                        DecodeableRpcResult result;

                        // 根据配置决定是否在当前通信框架（如：Netty）的IO线程上解码，默认true
                        if (channel.getUrl().getParameter(Constants.DECODE_IN_IO_THREAD_KEY, Constants.DEFAULT_DECODE_IN_IO_THREAD)) {

                            // 创建 DecodeableRpcResult 对象
                            result = new DecodeableRpcResult(channel, res, is, (Invocation) getRequestData(id), proto);

                            // 进行后续的解码
                            result.decode();

                            // 在 Dubbo ThreadPool 线程，解码。会在DecodeHandler中会调用 DecodeableRpcResult#decode()方法
                        } else {
                            result = new DecodeableRpcResult(channel, res, new UnsafeByteArrayInputStream(readMessageData(is)), (Invocation) getRequestData(id), proto);
                        }
                        data = result;
                    }

                    // 设置 DecodeableRpcResult 对象到 Response 对象中
                    res.setResult(data);

                    // 响应状态非 OK，表明调用过程出现了异常
                } else {

                    // 反序列化异常信息，并设置到 Response 对象中
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode response failed: " + t.getMessage(), t);
                }

                // 解码过程中出现了错误，此时设置 CLIENT_ERROR 状态码到 Response 对象中
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;

            //------------------- 解析请求  ----------------/
        } else {
            // 创建Request
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());

            /**
             * 通过逻辑与运算得到通信方式【是否需要响应】，并设置到Request对象中
             * 说明：
             * 1 FLAG_TWOWAY 的值是64，对应的补码是 0100 0000
             * 2 0100 0000 & flag ，所得结果取决于 header[2]的第2位，而该位表示的就是通信方式： 1 为请求  0 为响应
             */
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);

            /**
             * 通过位运算检测数据包是否为事件类型
             * 说明：
             * 1 FLAG_EVENT 的值为32，对应的补码为 0010 0000
             * 2 0010 0000 & flag，所得结果取决于 header[2]的第3位，而该位表示的正是 是否是事件消息 ：0 - 非事件，是请求或响应包，1 - 是一个事件
             */
            if ((flag & FLAG_EVENT) != 0) {
                // 设置心跳事件到Request对象中
                req.setEvent(Request.HEARTBEAT_EVENT);
            }

            try {
                Object data;

                // 通过序列化器编号间接获取输入流
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);

                // 解码心跳事件
                if (req.isHeartbeat()) {
                    // 对心跳包进行解码，该方法已经废弃
                    data = decodeHeartbeatData(channel, in);

                    // 对其他事件数据解码
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);


                    // 解码普通请求
                } else {

                    DecodeableRpcInvocation inv;

                    // 根据url参数判断，是否在通信框架（如Netty）的IO线程上对消息体进行解码，默认为 true
                    if (channel.getUrl().getParameter(Constants.DECODE_IN_IO_THREAD_KEY, Constants.DEFAULT_DECODE_IN_IO_THREAD)) {

                        inv = new DecodeableRpcInvocation(channel, req, is, proto);

                        // 直接调用decode()方法在当前线程，即IO线程上进行解码工作
                        inv.decode();

                        // 在 Dubbo ThreadPool 线程上解码，使用 DecodeHandler
                    } else {
                        inv = new DecodeableRpcInvocation(channel, req, new UnsafeByteArrayInputStream(readMessageData(is)), proto);
                    }

                    // 并没有解码，延迟到业务线程池中解码
                    data = inv;
                }

                // 设置data 到 Request 对象中,
                req.setData(data);

            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("Decode request failed: " + t.getMessage(), t);
                }


                // 在解码的过程中出现异常，则设置 broken 字段标识请求异常，并将异常对象设置到Request对象中
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
     * 编码内容体 - 请求
     * <p>
     * 按照Dubbo 协议的格式编码Request请求体，即编码RpcInvocation对象，写入需要编码的字段。对应的解码在{@link DecodeableRpcInvocation}
     *
     * @param channel
     * @param out     因配置的序列化的方式不同而不同，如，在xml配置文件中配置 <dubbo:protocol serialization="fastjson"/>,out就是FastJsonObjectOutput。一般使用默认的序列化方式 hessian2 方式 【1 体积小 2 容错性更高】
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

        // 获取方法参数
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

        // 检测当前协议版本是否支持带有 attachments 集合的 Response 对象
        boolean attach = Version.isSupportResponseAttatchment(version);

        Throwable th = result.getException();

        // 响应结果没有异常信息
        if (th == null) {
            // 提取正常返回结果
            Object ret = result.getValue();
            // 调用结果为空
            if (ret == null) {
                // 序列化响应类型
                out.writeByte(attach ? RESPONSE_NULL_VALUE_WITH_ATTACHMENTS : RESPONSE_NULL_VALUE);

                // 调用结果非空
            } else {

                // 序列化响应类型
                out.writeByte(attach ? RESPONSE_VALUE_WITH_ATTACHMENTS : RESPONSE_VALUE);
                // 序列化调用结果
                out.writeObject(ret);
            }

            //响应结果有异常
        } else {

            // 序列化响应类型
            out.writeByte(attach ? RESPONSE_WITH_EXCEPTION_WITH_ATTACHMENTS : RESPONSE_WITH_EXCEPTION);
            // 序列化异常对象
            out.writeObject(th);
        }

        // 当前协议版本支持Response带有attachments集合
        if (attach) {

            // 记录 Dubbo 协议版本，返回给服务消费端
            result.getAttachments().put(Constants.DUBBO_VERSION_KEY, Version.getProtocolVersion());

            // 序列化 attachments 集合
            out.writeObject(result.getAttachments());
        }
    }
}
