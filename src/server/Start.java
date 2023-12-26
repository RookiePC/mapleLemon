package server;

import client.SkillFactory;
import constants.GameConstants;
import constants.ServerConstants;
import constants.WorldConstants;
import database.DatabaseConnection;
import database.dao.AccountsDao;
import database.entity.AccountsPO;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.world.World;
import handling.world.WorldRespawnService;
import server.cashshop.CashItemFactory;
import server.life.MapleLifeFactory;
import server.life.MapleMonsterInformationProvider;
import server.life.MobSkillFactory;
import server.life.PlayerNPC;
import server.quest.MapleQuest;
import tools.MapleLogger;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Persistence;
import java.io.IOException;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Start {

    public static final Start instance = new Start();
    private int rankTime;
    private boolean ivCheck;
    private static ServerSocket srvSocket = null;
    private static final int srvPort = 6350;

    public Start() {
        this.ivCheck = false;
    }

    public void test() {

//        AccountsDao acc = new AccountsDao();
//        AccountsPO result = acc.getAccountByName("admin");



//        AccountsDao acc = DaoFactory.getInstance().createDao(AccountsDao.class);
//        AccountsPO result = acc.getAccountByName("icelemon1314");
//        acc.transactionStart();
//
//        result.setSalt("bbbbb");
//
//
//        acc.transactionCommit();


        MapleLogger.error("eeeeeeeeeeeee");
//        MapleLogger.info("ttttttttt");
//        MapleLogger.debug("DDDDDDDDDDDDDDDD");

        EntityManagerFactory factory = Persistence.createEntityManagerFactory("MapleLemonJPA");
        EntityManager manager = factory.createEntityManager();
//        EntityTransaction transaction = manager.getTransaction();
//        transaction.begin();
//        manager.persist(new AccountsPO());
        AccountsPO account = manager.find(AccountsPO.class, 1);
        System.out.println(account.getSalt());

//        account.setSalt("test1");
//        manager.persist(account);
//        manager.flush();

        // 5.提交事务，关闭资源
//        transaction.commit();
        manager.close();
        factory.close();
        return ;
    }

    public void run() {
        System.setProperty("io.netty.tryReflectionSetAccessible", "false");

        long start = System.currentTimeMillis();
//        LoggingService.init();
//        MapleInfos.printAllInfos();
        this.rankTime = Integer.parseInt(ServerProperties.getProperty("rankTime", "120"));
        this.ivCheck = Boolean.parseBoolean(ServerProperties.getProperty("ivCheck", "false"));
        if ((ServerProperties.getProperty("admin", false)) || (ServerConstants.USE_LOCALHOST)) {
            ServerConstants.USE_FIXED_IV = false;
            MapleLogger.info("[!!! 已开启只能管理员登录模式 !!!]");
        }
        // @TODO 放在用户登录的时候处理
        AccountsDao accountD = new AccountsDao();
        accountD.updateLoginStateToZero();

        MapleLogger.info("正在加载服务端...");
        MapleLogger.info("当前操作系统: " + System.getProperty("sun.desktop"));
        World.init();
        MapleLogger.info("服务器地址: " + ServerProperties.getProperty("channel.interface", ServerConstants.IP) + ":" + LoginServer.DEFAULT_PORT);
        MapleLogger.info("游戏版本: " + ServerConstants.MAPLE_TYPE + " v." + ServerConstants.MAPLE_VERSION + "." + ServerConstants.MAPLE_PATCH);
        MapleLogger.info("主服务器: " + WorldConstants.getMainWorld().name());
        runTimerThread();
        loadMapleData(false);

        System.out.print("加载登入服务...");
        LoginServer.run_startup_configurations();

        MapleLogger.info("正在加载频道...");
        ChannelServer.startChannel_Main();

        MapleLogger.info("频道加载完成!\r\n");
        System.out.print("正在加载商城...");
        CashShopServer.run_startup_configurations();

        Runtime.getRuntime().addShutdownHook(new Thread(new Shutdown()));

        printSection("刷怪线程");
        WorldRespawnService.getInstance();
        if (ServerProperties.getProperty("RandDrop", false)) {
            ChannelServer.getInstance(1).getMapFactory().getMap(910000000).spawnRandDrop();
        }
//        ShutdownServer.registerMBean();
//        ServerConstants.registerMBean();
        PlayerNPC.loadAll();
//        printSection("定时活动");
//        MessengerRankingWorker.getInstance();
        LoginServer.setOn();
//        Server.run_startup_configurations();
//        Server.setGameRunning(true);
//
//        if (this.rankTime > 0) {
//            printSection("刷新排名");
//            RankingWorker.start();
//        }
//
//          if (Boolean.parseBoolean(ServerProperties.getProperty("world.AccCheck", "false"))) {
//              printSection("启动检测");
//              startCheck();
//        }
//          printSection("在线统计");
//          在线统计(Integer.parseInt(ServerProperties.getProperty("world.showUserCountTime", "30")));
//          MessengerRankingWorker.getInstance();
//
//          if (Boolean.parseBoolean(ServerProperties.getProperty("world.checkCopyItem", "false")))   {
//            checkCopyItemFromSql();
//        }
        long now = System.currentTimeMillis() - start;
        long seconds = now / 1000;
        long ms = now % 1000;
        if (ServerProperties.getProperty("aotoSave", false)) {
            DatabaseBackup.getInstance().startTasking();
            MapleLogger.info("启动数据库自动备份!");
        }
//        ManagerSin.main(ServerConstants.GUI);
        MapleLogger.info("加载完成, 耗时: " + seconds + "秒" + ms + "毫秒\r\n");
        MapleLogger.info("服务端开启完毕，可以登入游戏了！");
    }

    /**
     *  时间计时线程
     */
    public static void runTimerThread() {
        System.out.print("\r\n正在加载线程");
        Timer.WorldTimer.getInstance().start();
        Timer.EtcTimer.getInstance().start();
        Timer.MapTimer.getInstance().start();
        Timer.CloneTimer.getInstance().start();
        Timer.CheatTimer.getInstance().start();
        Timer.EventTimer.getInstance().start();
        Timer.BuffTimer.getInstance().start();
        Timer.PingTimer.getInstance().start();
        MapleLogger.info("完成!\r\n");
    }

    public static void loadMapleData(boolean reload) {
        MapleLogger.info("载入数据(因为数据量大可能比较久而且内存消耗会飙升)");

        MapleLogger.info("加载等级经验数据");
        GameConstants.LoadExp();

        MapleLogger.info("加载任务数据");
        //加载任务信息
        MapleLifeFactory.loadQuestCounts(reload);
        //加载转存到数据库的任务信息
        MapleQuest.initQuests(reload);

        MapleLogger.info("加载爆物数据");
        //加载爆物数据
        MapleMonsterInformationProvider.getInstance().addExtra();
        //加载全域爆物数据
        MapleMonsterInformationProvider.getInstance().load();

        MapleLogger.info("加载道具数据");
        //加载道具信息(从WZ)
        MapleItemInformationProvider.getInstance().runEtc(reload);
        //加载道具信息(从SQL)
        MapleItemInformationProvider.getInstance().runItems(reload);
        //加载发型脸型
        MapleItemInformationProvider.getInstance().loadHairFace(reload);

        MapleLogger.info("加载技能数据");
        //加载技能
        SkillFactory.loadAllSkills(reload);

        MobSkillFactory.getInstance(); //载入怪物技能

        MapleLogger.info("loadSpeedRuns");
        //?
        SpeedRunner.loadSpeedRuns(reload);

        MapleLogger.info("加载商城道具数据");
        //加载商城道具信息
        CashItemFactory.getInstance().initialize(reload);
        MapleLogger.info("数据载入完成!\r\n");
    }

    public static void printSection(String s) {
        s = "-[ " + s + " ]";
        while (s.getBytes().length < 79) {
            s = "=" + s;
        }
        MapleLogger.info(s);
    }

    public static void main(String[] args) {
        instance.run();
//        instance.test();
    }

    public int getRankTime() {
        return this.rankTime;
    }

    public void setRankTime(int rankTime) {
        this.rankTime = rankTime;
    }

    public boolean isIvCheck() {
        return this.ivCheck;
    }

    protected static void checkSingleInstance() {
        try {
            srvSocket = new ServerSocket(srvPort);
        } catch (IOException ex) {
            if (ex.getMessage().contains("Address already in use: JVM_Bind")) {
                MapleLogger.info("在一台主机上同时只能启动一个进程(Only one instance allowed)。");
            }
            System.exit(0);
        }
    }

    protected static void checkCopyItemFromSql() {
        ArrayList<Integer> equipOnlyIds = new ArrayList();
        Map checkItems = new HashMap();
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM inventoryitems WHERE equipOnlyId > 0");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int itemId = rs.getInt("itemId");
                int equipOnlyId = rs.getInt("equipOnlyId");
                if (equipOnlyId > 0) {
                    if (checkItems.containsKey(equipOnlyId)) {
                        if (((Integer) checkItems.get(equipOnlyId)) == itemId) {
                            equipOnlyIds.add(equipOnlyId);
                        }
                    } else {
                        checkItems.put(equipOnlyId, itemId);
                    }
                }
            }
            rs.close();
            ps.close();

            Collections.sort(equipOnlyIds);
            for (int i : equipOnlyIds) {
                ps = con.prepareStatement("DELETE FROM inventoryitems WHERE equipOnlyId = ?");
                ps.setInt(1, i);
                ps.executeUpdate();
                ps.close();
                MapleLogger.info("发现复制装备 该装备的唯一ID: " + i + " 已进行删除处理..");
            }
        } catch (SQLException ex) {
            MapleLogger.info("[EXCEPTION] 清理复制装备出现错误." + ex);
        } finally {
            try {
                con.close();
            } catch (Exception e) {}
        }
    }

    public static class Shutdown implements Runnable {

        @Override
        public void run() {
            ShutdownServer.getInstance().run();
        }
    }
}
