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
package com.alibaba.dubbo.remoting.exchange.codec;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.StreamUtils;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec 继承 TelnetCodec类，信息交换编解码器。
 * 说明：
 * ExchangeCodec 只处理了 Dubbo 协议头，而 DubboCodec 则是通过继承的方式在 ExchangeCodec 基础上，添加了解析 Dubbo 消息体的功能。
 */
public class ExchangeCodec extends TelnetCodec {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);


    /*********************** Dubbo Protocol ******************
     *
     * todo 注意:
     * 1 数值在计算机中以补码方式存储，所以位运算以及强制转换，都是操作补码
     * 2 对于请求和响应，会有不同，主要体现在第3个字节上，8位上的值会不同
     *
     *
     *--------- 消息头
     *
     * MAGIC - 2  【固定的魔数数字】
     *  0-7        Magic High
     *  8-15       Magic Low               --    [0,15] Magic Number 魔数
     *
     *FLAG - 1  【标记】
     *  16         数据包类型                --   [16] 数据包类型，0 - Response  1- Request
     *  17         TwoWay                  --   [17] 是否需要响应，即是双向传输还是单向，【双向：有请求有响应。 单向：不需要响应】,在第16位被设为1的情况下有效，0 - 单向调用，1 - 双向调用
     *  18         event                   --   [18] 0 - 当前数据包是请求或响应包，1 - 当前数据包是心跳包
     *  19-23      Serialization id        --   [19,23] Serialization 编号 ，标志序列化方式
     *
     * Status - 1  【响应状态】
     *  24-31      status                  --    [24,31]  status 状态（Request没有，是空的，Response才有）
     *
     * Invoker ID - 8  【请求编号id】
     *  32-95      invoke id               --    [32,95] 请求id编号，是Long型，每个请求的唯一标识（采用异步通讯的方式，通过该id把请求request和响应response对应上）
     *
     *Body Length - 4 【消息体长度】
     *  96-127     data length             --    [96,127] body的长度，int 类型 通过该长度，去读取Body
     *
     *---------  消息体
     *
     *Body Content
     *
     *
     *  总结：
     *  Dubbo 处理传输数据是采用： 固定长度 + 消息头  的方式处理粘包和拆包
     *  1 tcp为了提高性能，发送端会将数据发送到缓冲区，等待缓冲区满了之后，再将缓冲中的数据发送到接收方法。同理，接收方也有缓冲区机制，接收数据式就会出现粘包和拆包：
     *   1.1 应用程序写入的数据大于缓冲区大小，会发生拆包
     *   1.2 应用程序写入的数据小于缓冲区大小，会发生粘包
     *   1.3 接收方不及时读取套接字缓冲区数据，会发生粘包
     *  2 固定长度指 消息头的长度固定 目前使用的是 16字节
     *  3 消息头存储消息开始标识及消息长度信息，服务端获取消息头的时候解析出消息长度，然后就可以向后读取该长度内容
     *  4 使用编解码处理消息头，使用序列化/反序列化处理消息体
     *
     /*********************** Dubbo Protocol  *****************/

    /**
     * 消息 Header总长度 16Bytes = 128 Bits
     */
    protected static final int HEADER_LENGTH = 16;

    /**
     * 协议头前16位，分为 MAGIC_HIGH 和 MAGIC_LOW 2个字节。是个固定值，标志着一个数据包是否是 Dubbo 协议
     * <p>
     * 11111111 11111111  11011010 10111011
     */
    protected static final short MAGIC = (short) 0xdabb; // -9541
    /**
     * 魔数高位
     * <p>
     * 111111111111111111111111 1101 1010
     */
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0]; // -38
    /**
     * 魔数低位
     * <p>
     * 111111111111111111111111 1011 1011
     */
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1]; // -69

    // ----------- flag标志位，1个字节，共8位 三位分别如下，其他五位表示消息体数据用的序列化工具的类型（默认是hessian2） ---------------------------
    /**
     * 标识是请求还是响应 1 为request请求  0 为响应
     * <p>
     * 111111111111111111111111 1000 0000
     */
    protected static final byte FLAG_REQUEST = (byte) 0x80; // -128
    /**
     * 标识是双向传输还是单向传输 1 为双向传输 0 为是单向传输
     * <p>
     * 0100 0000
     */
    protected static final byte FLAG_TWOWAY = (byte) 0x40; // 64
    /**
     * 标示是否为事件： 0 - 当前数据包是请求或响应包，1 - 当前数据包是心跳包
     * <p>
     * 0010 0000
     */
    protected static final byte FLAG_EVENT = (byte) 0x20; // 32


    /**
     * 用于获取序列化类型的标志位的掩码。
     * <p>
     * 0001 1111
     */
    protected static final int SERIALIZATION_MASK = 0x1f; // 31

    /**
     * 获取魔数
     *
     * @return
     */
    public Short getMagicCode() {
        return MAGIC;
    }

    /**
     * 编码 - 请求头
     *
     * @param channel Dubbo 底层 Channel
     * @param buffer  Dubbo 底层 Buffer
     * @param msg     出站消息
     * @throws IOException
     */
    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        // 对请求对象进行编码
        if (msg instanceof Request) {
            encodeRequest(channel, buffer, (Request) msg);

            // 对响应对象进行编码
        } else if (msg instanceof Response) {
            encodeResponse(channel, buffer, (Response) msg);

            // 提交给父类(Telnet)处理， 编码Telnet命令的结果
        } else {
            super.encode(channel, buffer, msg);
        }
    }

    /**
     * 解码
     *
     * @param channel
     * @param buffer
     * @return
     * @throws IOException
     */
    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 从Buffer 中读取字节数
        int readable = buffer.readableBytes();
        // 创建协议头字节数组，优先解析 Dubbo 协议，而不是 Telnet 命令
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        // 从管道中取出header.length个字节，一般是16个，注意这是先处理消息头的，消息体内容会根据消息头进一步处理 ，需要注意的是，可能目前管道中数据不足16个字节
        buffer.readBytes(header);
        // 解码
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {

        // 通过魔数判断是否Dubbo 消息,不是的情况下目前是Telnet 命令行发出的数据包
        if (readable > 0 && header[0] != MAGIC_HIGH || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            // 如果 header.length < readable 成立，说明 buffer 中数据没有读完，因此需要将数据全部读取出来。因为这不是 Dubbo 协议。
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }

            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }

            // 通过telnet命令行发送的数据包不包含消息头，所以这里调用TelnetCodec的decode方法对数据包进行解码
            return super.decode(channel, buffer, readable, header);
        }

        // 检查可读数据字节数是否少于固定长度 ，若小于则返回需要更多的输入。因为Dubbo协议采用 协议头 + payload  的方式
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // 从消息头中获取消息体的长度 - [96 - 127]，通过该长度读取消息体。
        int len = Bytes.bytes2int(header, 12);

        // 检测消息体长度是否超出限制，超出则抛出异常
        checkPayload(channel, len);

        // 检测可读的字节数是否小于实际的字节数【消息头 + 消息体 的字节长度和】，如果是则返回需要更多的输入
        int tt = len + HEADER_LENGTH;
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // ----- 基于消息长度的方式进行后续的解码工作 -------------------/

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // 解析 Header + Body,根据情况，根据具体数据包类型返回 Request 或 Reponse
            return decodeBody(channel, is, header);
        } finally {

            // 跳过未读完的流，并打印错误日志
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 注意，该方法被其子类覆写了，所以运行时执行的是其子类DubboCodec中的decodeBody方法
     *
     * @param channel
     * @param is
     * @param header
     * @return
     * @throws IOException
     */
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        long id = Bytes.bytes2long(header, 4);
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                    } else {
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    data = decodeEventData(channel, in);
                } else {
                    data = decodeRequestData(channel, in);
                }
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null) {
            return null;
        }
        Request req = future.getRequest();
        if (req == null) {
            return null;
        }
        return req.getData();
    }

    /**
     * 编码请求
     * <p>
     * 1 Header 部分，先写入 header数组，再写入Buffer 中
     * 2 Body 部分，使用 Serialization 序列化请求体，然后写入到Buffer中
     * 3 先把Body 写入Buffer，再写入 Header
     *
     * @param channel
     * @param buffer
     * @param req
     * @throws IOException
     */
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        // 获取序列化方式，如果没有配置，默认是 hessian2
        Serialization serialization = getSerialization(channel);
        // 创建消息头字节数组，长度为16
        byte[] header = new byte[HEADER_LENGTH];
        // 设置魔数，占用2个字节: [0-7] -> 魔数高位 -38，【8-15】 -> 魔数低位 -69
        Bytes.short2bytes(MAGIC, header);

        // 设置数据包类型（Request/Response，0 - Response  1- Request）[16]   和序列化器编号 [19,23]
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // 设置通信方式 (twoWay)，即是双向传输还是单向，0 - 单向调用，1 - 双向调用 [17]
        if (req.isTwoWay()) {
            header[2] |= FLAG_TWOWAY;
        }

        // 是否为事件（event）[18]
        if (req.isEvent()) {
            header[2] |= FLAG_EVENT;
        }

        // 设置请求编号，8个字节，从第5个字节开始设置 [32 - 95] 请求 id 编号，Long 型。注意，字节数组的第4个字节[27- 31]没有设置值，因为status 状态，Request没有，是空的，Response才有
        Bytes.long2bytes(req.getId(), header, 4);

        /** 序列化 `Request.data` 到 Body ，并写入到 Buffer */

        // 获取 buffer 当前的写位置
        int savedWriteIndex = buffer.writerIndex();
        // 更新 writerIndex，为消息头预留 16 个字节的空间
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);

        // 获取序列化器，如 Hessian2ObjectOutput 【todo 注意：序列化后的数据最终会存储到 ChannelBuffer 中】
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);

        // 对事件数据进行序列化操作 todo 什么事件？
        if (req.isEvent()) {
            encodeEventData(channel, out, req.getData());
        } else {
            // 对普通请求的数据进行序列化操作，即将req.data（一般是RpcInvocation） 写入到输出流 out中，ChannelBufferOutputStream进行接收，然后存储到ChannelBuffer
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }

        // 刷新
        out.flushBuffer();

        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();

        // 检查Body长度，按照字节计数，是否超过消息上限，默认最大为 8M，可以通过 payload 参数配置
        int len = bos.writtenBytes();
        checkPayload(channel, len);

        /** 为什么 Buffer 先写入了 Body ，再写入 Header 呢？因为 Header 中，里面 [96 - 127] 的 Body 长度需要序列化后才得到，如下： */

        // 将 消息体长度 写入到消息头中 [96 - 127]
        Bytes.int2bytes(len, header, 12);

        // 将 buffer 指针移动到 savedWriteIndex，为写消息头做准备
        buffer.writerIndex(savedWriteIndex);
        // 从 savedWriteIndex 下标处写入消息头
        buffer.writeBytes(header);
        // 设置新的 writerIndex，writerIndex = 原写下标 + 消息头长度 + 消息体长度
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    /**
     * 编码响应
     *
     * @param channel
     * @param buffer
     * @param res
     * @throws IOException
     */
    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {

        // 获取写的位置
        int savedWriteIndex = buffer.writerIndex();
        try {
            // 获取序列化器
            Serialization serialization = getSerialization(channel);

            // 创建消息头字节数组，长度为16
            byte[] header = new byte[HEADER_LENGTH];

            // 设置魔数，占2个字节 [0-15]
            Bytes.short2bytes(MAGIC, header);

            // 设置序列化器编号,占header第3个字节的后5位 [19 -23]
            header[2] = serialization.getContentTypeId();

            // 如果心跳数据包，就设置header第3个字节的第3位 [18]
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }

            // 设置响应状态，占1个字节 ，[24-31]
            byte status = res.getStatus();
            header[3] = status;

            // 设置请求编号，注意Response中的id就是Request的编号，占8个字节 [32-95]
            Bytes.long2bytes(res.getId(), header, 4);

            // 更新 writerIndex，为消息头预留 16 个字节的空间
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);

            // 编码响应数据或错误信息
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    // 对心跳响应结果进行序列化
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    // 对调用结果进行序列化
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else {
                // 对错误信息进行序列化
                out.writeUTF(res.getErrorMessage());
            }

            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            // 获取消息体长度
            int len = bos.writtenBytes();
            // 校验消息长度有没有超出当前设置的上限
            checkPayload(channel, len);

            // 将消息体长度写入到消息头中，占4个字节  [96-127]
            Bytes.int2bytes(len, header, 12);

            // 将 buffer 指针移动到 savedWriteIndex，为写消息头做准备
            buffer.writerIndex(savedWriteIndex);

            // 从 savedWriteIndex 下标处写入消息头
            buffer.writeBytes(header);

            // 设置新的 writerIndex，writerIndex = 原写下标 + 消息头长度 + 消息体长度
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);

            // 处理异常
        } catch (Throwable t) {
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout. // 注意和编码请求的不同
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                // 消息内容过大
                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }

    /**
     * 特殊说明：
     * 1 计算机中对数据的表示是使用二机制
     * 2 原码、反码、补码是为了让计算机计算方便而产生的一种表示方法，由于计算机只认识二进制数，所以我们说的原码、补码都只针对二进制数，和其它进制数没有关系。
     * 3 计算机所有的运算都是通过补码运算的。
     * 4 我们通常人为模拟计算运算，对于负数都会先将其转为补码再运算，而这里的负数是针对10进制的数，并非其它进制。计算机取出 -5（10进制数）就是 1 011 ，0xfa（16进制数）就是 1111 1010 ，也就是已经是补码了，如果没有结合具体数据类型是没有符号位概念。
     *
     * @param args
     */
    public static void main(String[] args) {
        /**
         * 协议头的字节数： 16Bytes = 128 Bits
         */
        final int HEADER_LENGTH = 16;

        /**
         * 协议头前16位，分为 MAGIC_HIGH 和 MAGIC_LOW 2个字节。是个固定值，标志着一个数据包是否是 Dubbo 协议。
         *
         * 11111111 11111111  11011010 10111011
         *                     1011010 10111011
         *无符号位情况下，0xdabb 的对应的二进制数为 1101101010111011 ，供16位，但是使用 short 强转的话只会取低15位，虽然 short 两个字节共16位，但需要保留最高位位符号位，此时符号位取数值最高位，一般数据溢出都为负数吧。。
         */
        final short MAGIC = (short) 0xdabb; // -9541
        /**
         * 魔数高位 101 1010
         *
         * 111111111111111111111111 1101 1010
         */
        final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0]; // -38
        /**
         * 魔数低位
         *
         * 111111111111111111111111 1011 1011
         */
        final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1]; // -69

        // ----------- flag标志位，1个字节，共8位 三位分别如下，其他五位表示消息体数据用的序列化工具的类型（默认是hessian2） ---------------------------
        /**
         * 标识是请求还是响应 1 为request请求  0 为响应
         *
         * 111111111111111111111111 1000 0000
         */
        final byte FLAG_REQUEST = (byte) 0x80; // -128
        /**
         * 标识是双向传输还是单向传输 1 为双向传输 0 为是单向传输
         *
         *  0100 0000
         */
        final byte FLAG_TWOWAY = (byte) 0x40; // 64
        /**
         * 标示是否为事件： 0 - 当前数据包是请求或响应包，1 - 当前数据包是心跳包
         *
         * 0010 0000
         */
        final byte FLAG_EVENT = (byte) 0x20; // 32

        /**
         * 用于获取序列化类型的标志位的掩码。
         *
         * 0001 1111
         */
        final int SERIALIZATION_MASK = 0x1f; // 31


        System.out.println(MAGIC + " : " + Integer.toBinaryString(MAGIC));
        System.out.println(MAGIC_HIGH + " : " + Integer.toBinaryString(MAGIC_HIGH));
        System.out.println(MAGIC_LOW + " : " + Integer.toBinaryString(MAGIC_LOW));
        System.out.println(FLAG_REQUEST + " : " + Integer.toBinaryString(FLAG_REQUEST));
        System.out.println(FLAG_TWOWAY + " : " + Integer.toBinaryString(FLAG_TWOWAY));
        System.out.println(FLAG_EVENT + " : " + Integer.toBinaryString(FLAG_EVENT));
        System.out.println(SERIALIZATION_MASK + " : " + Integer.toBinaryString(SERIALIZATION_MASK));

        System.out.println(-5 + " ：" + Integer.toBinaryString(-5));
        System.out.println((short) 0xfa + " : " + Integer.toBinaryString((short) 0xfa));
        // 结合 short 数据类型，具有了具体符号位，并且溢出了
        System.out.println((short) 0xdabb + " : " + Integer.toBinaryString((short) 0xdabb));
        System.out.println(0xdabb + " : " + Integer.toBinaryString(0xdabb));
        System.out.println(0x7fff + " : " + Integer.toBinaryString(0x7fff));


    }

}
