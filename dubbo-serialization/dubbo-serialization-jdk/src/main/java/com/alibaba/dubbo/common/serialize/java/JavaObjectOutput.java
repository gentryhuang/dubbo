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
package com.alibaba.dubbo.common.serialize.java;


import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.serialize.nativejava.NativeJavaObjectOutput;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/**
 * 在 NativeJava 的基础上，实现了对空字符串和空对象的处理
 */
public class JavaObjectOutput extends NativeJavaObjectOutput {

    public JavaObjectOutput(OutputStream os) throws IOException {
        super(new ObjectOutputStream(os));
    }

    /**
     * 注意 compact 为true的情况  {@link CompactedJavaSerialization#serialize(URL, OutputStream)}
     * @param os
     * @param compact
     * @throws IOException
     */
    public JavaObjectOutput(OutputStream os, boolean compact) throws IOException {
        super(compact ? new CompactedObjectOutputStream(os) : new ObjectOutputStream(os));
    }

    /**
     * 对空字符串的处理
     *
     * @param v
     * @throws IOException
     */
    @Override
    public void writeUTF(String v) throws IOException {
        if (v == null) {
            getObjectOutputStream().writeInt(-1);
        } else {
            getObjectOutputStream().writeInt(v.length());
            getObjectOutputStream().writeUTF(v);
        }
    }

    /**
     * 对空对象的处理
     *
     * @param obj
     * @throws IOException
     */
    @Override
    public void writeObject(Object obj) throws IOException {
        if (obj == null) {
            getObjectOutputStream().writeByte(0);
        } else {
            getObjectOutputStream().writeByte(1);
            getObjectOutputStream().writeObject(obj);
        }
    }

    @Override
    public void flushBuffer() throws IOException {
        getObjectOutputStream().flush();
    }
}
