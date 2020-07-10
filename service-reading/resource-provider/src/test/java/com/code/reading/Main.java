package com.code.reading;

/**
 * Main
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/07/04
 * <p>
 * desc：
 */

public class Main {

    public static void main(String[] args) {
        MyThread thread = new MyThread();
        thread.setName("thread");
        Thread t = new Thread(thread);
        t.setName("a");

        // 主线程main 执行
        System.out.println("当前线程名：" + Thread.currentThread().getName());

        // 开启任务线程a
        System.out.println("开启任务线程a");
        t.start();
    }
}

/**
 * 本质上是线程a的任务体
 */
class MyThread extends Thread {
    @Override
    public void run() {
        try {
            // 当前线程名 - a
            System.out.println(this.currentThread().getName());

            // this 是 什么 - MyThread 实例
            System.out.println(this.getClass().getPackage().getName()+"."+this.getClass().getSimpleName());

            System.out.println(this.isAlive());

            this.start();

            System.out.println(this.isAlive());

            // 哪个对象执行休眠 - MyThread对象。 因为 a线程是该任务的执行线程，任务休眠，此时线程a要阻塞等待。
            this.sleep(5000);

            System.out.println("休眠结束");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}