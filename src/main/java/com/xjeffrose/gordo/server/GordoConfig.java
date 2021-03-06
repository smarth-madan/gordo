package com.xjeffrose.gordo.server;

import com.typesafe.config.Config;
import com.xjeffrose.xio.core.XioMetrics;
import com.xjeffrose.xio.server.XioServerDef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GordoConfig {
  private static final Logger log = LoggerFactory.getLogger(GordoConfig.class.getName());

//  private final int gordoPort;
//  private String gordoBindIP;
  private String X509_CERT;
  private String PRIVATE_KEY;
  private Config conf;
  private int workers;
  private Map<XioServerDef, XioMetrics> channelStats;
  private int bossCount;
  private String adminBindIP;
  private int adminPort;
  private int statsPort;
  private String statsBindIP;
  private int gordoPort;
  private String gordoBindIP;
  private String cert;
  private String key;
  private String dbPath;
  private int quorum;
  private boolean graceFullStart;
  private long compactionSize;
  private boolean databaseMode;

  public GordoConfig(Config conf) {

    this.conf = conf;
    try {
      this.X509_CERT = new String(Files.readAllBytes(Paths.get(conf.getString("X509_CERT")).toAbsolutePath()));
      this.PRIVATE_KEY = new String(Files.readAllBytes(Paths.get(conf.getString("PRIVATE_KEY")).toAbsolutePath()));
    } catch (IOException e) {
      this.X509_CERT = null;
      this.PRIVATE_KEY = null;
      e.printStackTrace();
    }

//    this.dbPath = conf.getString("db_path");
    this.workers = conf.getInt("workers");
    this.bossCount =  conf.getInt("boss_count");
    this.adminBindIP = conf.getString("admin_bind_ip");
    this.adminPort = conf.getInt("admin_port");
    this.statsBindIP = conf.getString("stats_bind_ip");
    this.statsPort = conf.getInt("stats_port");
    this.gordoBindIP = conf.getString("gordo_bind_ip");
    this.gordoPort = conf.getInt("gordo_port");
    this.quorum = conf.getInt("quorum");


  }


  public int getWorkers() {
    return workers;
  }

  public void setChannelStats(Map<XioServerDef, XioMetrics> channelStats) {
    this.channelStats = channelStats;
  }

  public int getBossCount() {
    return bossCount;
  }

  public String getAdminBindIP() {
    return adminBindIP;
  }

  public int getAdminPort() {
    return adminPort;
  }

  public int getStatsPort() {
    return statsPort;
  }

  public String getStatsBindIP() {
    return statsBindIP;
  }

  public int getDBPort() {
    return gordoPort;
  }

  public String getDBBindIP() {
    return gordoBindIP;
  }

  public String getDBBindEndpoint() {
    return gordoBindIP + ":" + gordoPort;
  }

  public String getCert() {
    return X509_CERT;
  }

  public String getKey() {
    return PRIVATE_KEY;
  }

  public String getDBPath() {
    return dbPath;
  }

  public int getQuorum() {
    return quorum;
  }


}
