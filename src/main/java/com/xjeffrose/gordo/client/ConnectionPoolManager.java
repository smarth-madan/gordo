package com.xjeffrose.gordo.client;

import com.xjeffrose.xio.SSL.XioSecurityHandlerImpl;
import com.xjeffrose.xio.client.retry.BoundedExponentialBackoffRetry;
import com.xjeffrose.xio.client.retry.RetryLoop;
import com.xjeffrose.xio.client.retry.TracerDriver;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionPoolManager {
  private static final Logger log = LoggerFactory.getLogger(ConnectionPoolManager.class);
  private final static String NODE_LIST_PATH = "/chicago/node-list";
  private static final long TIMEOUT = 1000;
  private static boolean TIMEOUT_ENABLED = true;

  private final Map<String, Listener> listenerMap = new ConcurrentHashMap<>();
  private final Map<String, ChannelFuture> connectionMap = new ConcurrentHashMap<>();
  private final NioEventLoopGroup workerLoop = new NioEventLoopGroup(20);
  private final ZkClient zkClient;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final ScheduledExecutorService connectCheck = Executors.newSingleThreadScheduledExecutor();

  public ConnectionPoolManager(ZkClient zkClient) {
    this.zkClient = zkClient;
  }

  public ConnectionPoolManager(String hostname) {
    this.zkClient = null;
    listenerMap.put(hostname, new ChicagoListener());
    connect(new InetSocketAddress(hostname.split(":")[0], Integer.parseInt(hostname.split(":")[1])), listenerMap.get(hostname));
  }

  public void start() {
    running.set(true);
    refreshPool();
  }

  public void stop() {
    log.info("ConnectionPoolManager stopping");
    running.set(false);
    ChannelGroup channelGroup = new DefaultChannelGroup(workerLoop.next());
    for(ChannelFuture cf : connectionMap.values()) {
      channelGroup.add(cf.channel());
    }
    log.info("Closing channels");
    channelGroup.close().awaitUninterruptibly();
    log.info("Stopping workerLoop");
    workerLoop.shutdownGracefully();
    connectCheck.shutdownNow();
  }

  public void checkConnection(){
    List<String> reconnectNodes = new ArrayList<>();
    buildNodeList().forEach(s -> {
      if(connectionMap.get(s) == null){
        log.debug("Channel not present for "+ s);
        reconnectNodes.add(s);
      }else if(connectionMap.get(s) != null && !connectionMap.get(s).channel().isWritable()){
        log.debug("Channel not writeable for "+ s);
        reconnectNodes.add(s);
      }
    });

    reconnectNodes.forEach(s -> {
      ChannelFuture cf = connectionMap.remove(s);
      if(cf != null) {
        cf.channel().disconnect();
        cf.channel().close();
        cf.cancel(true);
      }
      if(listenerMap.get(s) == null){
        listenerMap.put(s, new ChicagoListener());
      }
      connect(address(s), listenerMap.get(s));
    });
  }

  private List<String> buildNodeList() {
    return zkClient.list(NODE_LIST_PATH);
  }

  private InetSocketAddress address(String node) {
    String chunks[] = node.split(":");
    return new InetSocketAddress(chunks[0], Integer.parseInt(chunks[1]));
  }

  private void refreshPool() {
    buildNodeList().stream().forEach(xs -> {
      listenerMap.put(xs, new ChicagoListener());
      connect(address(xs), listenerMap.get(xs));
    });

    try {
      connectCheck.scheduleAtFixedRate(new Runnable() {
        @Override
        public void run() {
          checkConnection();
        }
      }, 3000, 7000, TimeUnit.MILLISECONDS);
    } catch (Exception e){
      e.printStackTrace();
    }
  }


  public ChannelFuture getNode(String node) throws GordoClientTimeoutException {
    log.debug("Trying to get node:"+node);
    return _getNode(node, System.currentTimeMillis());
  }

  private ChannelFuture _getNode(String node, long startTime) throws GordoClientTimeoutException {
    while (connectionMap.get(node) == null) {
      if (TIMEOUT_ENABLED && (System.currentTimeMillis() - startTime) > TIMEOUT) {
        Thread.currentThread().interrupt();
        System.out.println("Cannot get connection for node "+ node +" connectionMap ="+ connectionMap.keySet().toString());
        throw new GordoClientTimeoutException();
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    ChannelFuture cf = connectionMap.get(node);

    if (cf.channel().isWritable()) {
      return cf;
    }else{
      checkConnection();
      return _getNode(node,startTime);
    }
  }

  public Listener getListener(String node) {
    return listenerMap.get(node);
  }

  public void addNode(String hostname, ChannelFuture future) {
    connectionMap.put(hostname, future);

  }

  private void connect(InetSocketAddress server, Listener listener) {
    // Start the connection attempt.
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
        .option(ChannelOption.SO_REUSEADDR, true)
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 32 * 1024)
        .option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024)
        .option(ChannelOption.TCP_NODELAY, true);
    bootstrap.group(workerLoop)
        .channel(NioSocketChannel.class)
        .handler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel channel) throws Exception {
            ChannelPipeline cp = channel.pipeline();
            cp.addLast(new XioSecurityHandlerImpl(true).getEncryptionHandler());
            //cp.addLast(new XioIdleDisconnectHandler(20, 20, 20));
            cp.addLast(new GordoCodec());
            cp.addLast(new GordoClientHandler(listener));
          }
        });

    BoundedExponentialBackoffRetry retry = new BoundedExponentialBackoffRetry(50, 500, 4);

    TracerDriver tracerDriver = new TracerDriver() {

      @Override
      public void addTrace(String name, long time, TimeUnit unit) {
      }

      @Override
      public void addCount(String name, int increment) {
      }
    };

    RetryLoop retryLoop = new RetryLoop(retry, new AtomicReference<>(tracerDriver));

    connect2(server, bootstrap, retryLoop);
  }

  private void connect2(InetSocketAddress server, Bootstrap bootstrap, RetryLoop retryLoop) {
    ChannelFutureListener listener = new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) {
        if (!future.isSuccess()) {
          if (!running.get()) {
            return;
          }
          try {
            retryLoop.takeException((Exception) future.cause());
            log.error("==== Service connect failure (will retry)", future.cause());
            connect2(server, bootstrap, retryLoop);
          } catch (Exception e) {
            log.error("==== Service connect failure ", future.cause());
            // Close the connection if the connection attempt has failed.
            future.channel().close();
          }
        } else {
          log.debug("Chicago connected to: " + server);
          String hostname = server.getAddress().getHostAddress();
          if (hostname.equals("localhost")) {
            hostname = "127.0.0.1";
          }
          log.debug("Adding hostname: " + hostname + ":" + ((InetSocketAddress) future.channel().remoteAddress()).getPort());
          addNode(hostname + ":" + ((InetSocketAddress) future.channel().remoteAddress()).getPort(), future);
        }
      }
    };

    bootstrap.connect(server).addListener(listener);
  }
}
