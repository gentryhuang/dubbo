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
package com.alibaba.dubbo.remoting.exchange;

import com.alibaba.dubbo.common.utils.StringUtils;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求
 */
public class Request {

    /**
     * 心跳事件
     */
    public static final String HEARTBEAT_EVENT = null;
    /**
     * 只读事件
     */
    public static final String READONLY_EVENT = "R";
    /**
     * 请求编号自增序列
     */
    private static final AtomicLong INVOKE_ID = new AtomicLong(0);
    /**
     * 请求编号 ，注意这个编号用来和该请求对应的响应Response关联，Response中的mId就是该请求的mId
     */
    private final long mId;
    /**
     * Dubbo 版本
     */
    private String mVersion;
    /**
     * 请求是否需要响应： true-> 需要  false-> 不需要
     */
    private boolean mTwoWay = true;
    /**
     * 是否是事件，如心跳事件。 【内置了两种事件： 心跳事件和只读事件】
     */
    private boolean mEvent = false;
    /**
     * 是否异常的请求。
     * 请求发送到Server之后，由Decoder将二进制数据解码成Request对象，如果解码环节出现异常，就会设置该标志，然后
     * 交给其它 ChannelHandler 根据该标志做进一步处理。
     */
    private boolean mBroken = false;
    /**
     * 请求体，可以是任何类型的数据，也可以是null
     */
    private Object mData;

    /**
     *
     */
    public Request() {
        mId = newId();
    }

    public Request(long id) {
        mId = id;
    }

    /**
     * JVM进程内唯一，原子增
     *
     * @return
     */
    private static long newId() {
        // getAndIncrement() When it grows to MAX_VALUE, it will grow to MIN_VALUE, and the negative can be used as ID
        return INVOKE_ID.getAndIncrement();
    }

    private static String safeToString(Object data) {
        if (data == null) return null;
        String dataStr;
        try {
            dataStr = data.toString();
        } catch (Throwable e) {
            dataStr = "<Fail toString of " + data.getClass() + ", cause: " +
                    StringUtils.toString(e) + ">";
        }
        return dataStr;
    }

    public long getId() {
        return mId;
    }

    public String getVersion() {
        return mVersion;
    }

    public void setVersion(String version) {
        mVersion = version;
    }

    public boolean isTwoWay() {
        return mTwoWay;
    }

    public void setTwoWay(boolean twoWay) {
        mTwoWay = twoWay;
    }

    public boolean isEvent() {
        return mEvent;
    }

    public void setEvent(String event) {
        mEvent = true;
        mData = event;
    }

    public boolean isBroken() {
        return mBroken;
    }

    public void setBroken(boolean mBroken) {
        this.mBroken = mBroken;
    }

    public Object getData() {
        return mData;
    }

    public void setData(Object msg) {
        mData = msg;
    }

    public boolean isHeartbeat() {
        return mEvent && HEARTBEAT_EVENT == mData;
    }

    public void setHeartbeat(boolean isHeartbeat) {
        if (isHeartbeat) {
            setEvent(HEARTBEAT_EVENT);
        }
    }

    @Override
    public String toString() {
        return "Request [id=" + mId + ", version=" + mVersion + ", twoway=" + mTwoWay + ", event=" + mEvent
                + ", broken=" + mBroken + ", data=" + (mData == this ? "this" : safeToString(mData)) + "]";
    }
}
