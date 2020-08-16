package com.alibaba.dubbo.demo.consumer;

import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.curator.CuratorZookeeperClient;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.retry.RetryNTimes;

/**
 * ZookeeperClientTest
 *
 * @author <a href="mailto:libao.huang@yunhutech.com">shunhua</a>
 * @since 2020/08/10
 * <p>
 * desc：
 */
public class ZookeeperClientTest {
    public static void main(String[] args) {

        try {
            // 创建 CuratorFramework 构造器
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    // 连接地址
                    .connectString("127.0.0.1:2181")
                    // 重试策略，1 次，间隔 1000 ms
                    .retryPolicy(new RetryNTimes(2, 5000))
                    // 连接超时时间
                    .connectionTimeoutMs(5000);

            // 构建 CuratorFramework 实例
            CuratorFramework client = builder.build();
            // 添加连接监听器。在连接状态发生变化时，调用#stateChange(state)方法，进行StateListener的回调
            client.getConnectionStateListenable().addListener(new ConnectionStateListener() {
                /**
                 * 在连接状态发生变化时，调用 #stateChange(state) 方法，进行 StateListener 的回调。
                 *
                 * @param client
                 * @param state
                 */
                @Override
                public void stateChanged(CuratorFramework client, ConnectionState state) {
                    if (state == ConnectionState.LOST) {
                        System.out.println("LOST");
                    } else if (state == ConnectionState.CONNECTED) {
                       System.out.println("CONNECTED");
                    } else if (state == ConnectionState.RECONNECTED) {
                        System.out.println("RECONNECTED");
                    }
                }
            });
            // 启动客户端
            client.start();

            System.in.read();
        }catch (Exception ex){

        }


    }
}
