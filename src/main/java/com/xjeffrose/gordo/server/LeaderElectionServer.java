package com.xjeffrose.gordo.server;

import com.xjeffrose.gordo.server.handlers.GordoLeaderElectionHandler;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

public class LeaderElectionServer {

  private final GordoServerBootstrap server;
  private final int port;

  private final List<Integer> ballotBox;

  public LeaderElectionServer(int port, int q) {
    this.port = port;
    this.ballotBox = new ArrayList<>();

    this.server = new GordoServerBootstrap(new InetSocketAddress(port), new GordoLeaderElectionHandler(q, new CampaignManager()), 100);
  }

  public void start() {
    server.start();
  }

  public void stop() {
    try {
      server.stop();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }


}
