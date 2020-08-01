package com.code.resource.reading;

import java.io.IOException;

/**
 * ClientTest
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/06/22
 * <p>
 * desc：
 */
public class ClientTest {


    public static void main(String[] args) {

        short test = -9541;
        System.out.println((test >>> 8));


        byte b=-1;
        System.out.println((int)(char)b);
        System.out.println((int)(char)(b & 0xff));

        System.out.println((byte)128);


        System.out.println("----------");

        short num = -9541;
        byte[] result = short2byte(num);
        System.out.println(0xff);
        System.out.println(result[0]);
        System.out.println(result[1]);

        System.out.println("---------");

        /**
         * short型整数占16位 130 原码 = 00000000 10000010
         * byte型整数占8位 只读取 10000010，1是符号位。 进行转换要用补码，10000010 对应补码为 1 1111110  ->  -126
         *
         */
        short a = 130;
        byte a_b = (byte) a;
        System.out.println(a_b);

    }

    public static byte[] short2byte(short num) {

        byte[] result = new byte[2];
        for (int i = 0; i < 2; i++) {
            int offset = 16 - (i + 1) * 8;
            result[i] = (byte) ((num >> offset));
        }
        return result;
    }


}
