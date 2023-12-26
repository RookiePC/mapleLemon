package client;

import client.messages.PlayerGMRank;
import constants.ServerConstants;
import database.DaoFactory;
import database.DatabaseConnection;
import database.DatabaseException;
import database.dao.AccountsDao;
import database.dao.CharacterDao;
import database.entity.AccountsPO;
import database.entity.CharacterPO;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.vo.send.LoginStatusSendVO;
import handling.world.PartyOperation;
import handling.world.WorldBuddyService;
import handling.world.WorldFindService;
import handling.world.WorldMessengerService;
import handling.world.WorldSidekickService;
import handling.world.WrodlPartyService;
import handling.world.messenger.MapleMessengerCharacter;
import handling.world.party.MapleParty;
import handling.world.party.MaplePartyCharacter;
import handling.world.sidekick.MapleSidekick;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.Persistence;
import javax.script.ScriptEngine;

import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;
import scripting.item.ItemActionManager;
import scripting.item.ItemScriptManager;
import scripting.npc.NPCConversationManager;
import scripting.npc.NPCScriptManager;
import scripting.quest.QuestActionManager;
import scripting.quest.QuestScriptManager;
import server.Timer.PingTimer;
import server.maps.MapleMap;
import server.quest.MapleQuest;
import server.shops.IMaplePlayerShop;
import tools.*;
import tools.packet.LoginPacket;
import io.netty.channel.Channel;

public class MapleClient implements Serializable {

    private static final long serialVersionUID = 9179541993413738569L;
    public static final byte LOGIN_NOTLOGGEDIN = 0;
    public static final byte LOGIN_SERVER_TRANSITION = 1;
    public static final byte LOGIN_LOGGEDIN = 2;
    public static final byte CHANGE_CHANNEL = 3;
    public static final byte ENTERING_PIN = 4; // 需要设置性别
    public static final byte PIN_CORRECT = 5;
    public static final int DEFAULT_CHARSLOT = LoginServer.getMaxCharacters();
    public static final AttributeKey<MapleClient> CLIENT_KEY = AttributeKey.valueOf("CLIENT");
    private final transient MapleAESOFB send, receive;
    private Channel session;
    private long sessionId;
    private MapleCharacter player;
    private int channel = 1;
    private AccountsPO accountPo;
    private int accId = -1;
    private int world;
    private String birthday;
    private int charslots = DEFAULT_CHARSLOT;
    private int cardslots = 3;
    private boolean loggedIn = false;
    private boolean serverTransition = false;
    private transient Calendar tempban = null;
    private String accountName;
    private transient long lastPong = 0L;
    private transient long lastPing = 0L;
    private boolean monitored = false;
    private boolean receiving = true;
    private int gmLevel;
    private byte greason = 1;
    private byte gender = -1;
    public transient short loginAttempt = 0;
    private final transient List<Integer> allowedChar = new LinkedList();
    private transient String mac = "00-00-00-00-00-00";
    private final transient List<String> maclist = new LinkedList();
    private final transient Map<String, ScriptEngine> engines = new HashMap();
    private transient ScheduledFuture<?> idleTask = null;
    private transient String secondPassword;
    private transient String salt2;
    private transient String tempIP = "";
    private final transient Lock mutex = new ReentrantLock(true);
    private final transient Lock npc_mutex = new ReentrantLock();
    private long lastNpcClick = 0L;
    private static final Lock login_mutex = new ReentrantLock(true);
    private final byte loginattempt = 0;
    private DebugWindow debugWindow;
    private final Map<Integer, Pair<Short, Short>> charInfo = new LinkedHashMap();

    public MapleClient(MapleAESOFB send, MapleAESOFB receive, Channel session) {
        this.send = send;
        this.receive = receive;
        this.session = session;
    }

    public final MapleAESOFB getReceiveCrypto() {
        return receive;
    }

    public final MapleAESOFB getSendCrypto() {
        return send;
    }

    public final Channel getSession() {
        return session;
    }

    public long getSessionId() {
        return this.sessionId;

    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public void StartWindow() {
        if (this.debugWindow != null) {
            this.debugWindow.setVisible(false);
            this.debugWindow = null;
        }
        this.debugWindow = new DebugWindow();
        this.debugWindow.setVisible(true);
        this.debugWindow.setC(this);
    }

    public Lock getLock() {
        return this.mutex;
    }

    public Lock getNPCLock() {
        return this.npc_mutex;
    }

    public MapleCharacter getPlayer() {
        return this.player;
    }

    public void setPlayer(MapleCharacter player) {
        this.player = player;
    }

    public void createdChar(int id) {
        this.allowedChar.add(id);
    }

    public boolean login_Auth(int id) {
        return this.allowedChar.contains(id);
    }

    public List<MapleCharacter> loadCharacters(int serverId) {
        List chars = new LinkedList();
        MapleCharacter chr;
        for (CharNameAndId cni : loadCharactersInternal(serverId)) {
            chr = MapleCharacter.loadCharFromDB(cni.id, this, false);
            chars.add(chr);
            charInfo.put(chr.getId(), new Pair(chr.getLevel(), chr.getJob()));
            if (!login_Auth(chr.getId())) {
                allowedChar.add(chr.getId());
            }
        }
        return chars;
    }

    public boolean canMakeCharacter(int serverId) {
        // @TODO max character
        return loadCharactersSize(serverId) < 6;
    }

    public List<String> loadCharacterNames(int serverId) {
        List chars = new LinkedList();
        for (CharNameAndId cni : loadCharactersInternal(serverId)) {
            chars.add(cni.name);
        }
        return chars;
    }

    private List<CharNameAndId> loadCharactersInternal(int serverId) {
        List chars = new LinkedList();
        CharacterDao charDao = new CharacterDao();
        List<CharacterPO> charPo = charDao.getCharacterByNameAndWorld(this.accId, serverId);
        for(CharacterPO chPo : charPo) {
            chars.add(new CharNameAndId(chPo.getName(), chPo.getId()));
            LoginServer.getLoginAuth(chPo.getId());
        }
        return chars;
    }

    private int loadCharactersSize(int serverId) {
        int chars = 0;
        CharacterDao charDao = new CharacterDao();
        List<CharacterPO> charPo = charDao.getCharacterByNameAndWorld(this.accId, serverId);
        chars = charPo.size();
        return chars;
    }

    public boolean isLoggedIn() {
        return loggedIn && accId > 0;
    }

    private Calendar getTempBanCalendar(Date tempBanedDate) {
        Calendar lTempban = Calendar.getInstance();
        if (tempBanedDate == null) {
            lTempban.setTimeInMillis(0L);
            return lTempban;
        }
        Calendar today = Calendar.getInstance();
        lTempban.setTimeInMillis(tempBanedDate.getTime());
        if (today.getTimeInMillis() < lTempban.getTimeInMillis()) {
            return lTempban;
        }
        lTempban.setTimeInMillis(0L);
        return lTempban;
    }

    public Calendar getTempBanCalendar() {
        return this.tempban;
    }

    public byte getBanReason() {
        return this.greason;
    }

    public boolean hasBannedMac() {
        if ((this.mac.equalsIgnoreCase("00-00-00-00-00-00")) || (this.mac.length() != 17)) {
            return false;
        }
        boolean ret = false;
        return ret;
    }

    public int finishLogin() {
        login_mutex.lock();
        try {
            if (getLoginState() > 0) {
                this.loggedIn = false;
                return 7;
            }
            updateLoginState(MapleClient.LOGIN_LOGGEDIN, getSessionIPAddress());
        } finally {
            login_mutex.unlock();
        }
        return 0;
    }

    public void clearInformation() {
        accountName = null;
        accId = -1;
        secondPassword = null;
        gmLevel = 0;
        loggedIn = false;
        mac = "00-00-00-00-00-00";
        maclist.clear();
    }

    public int changePassword(String oldpwd, String newpwd) {
        int ret = -1;
        AccountsDao accDao = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO acc = accDao.getAccountByName(this.accountName);

        boolean updatePassword = false;
        String passhash = acc.getPassword();
        String salt = acc.getSalt();
        if ((passhash == null) || (passhash.isEmpty())) {
            ret = -1;
        } else if ((LoginCryptoLegacy.isLegacyPassword(passhash)) && (LoginCryptoLegacy.checkPassword(oldpwd, passhash))) {
            ret = 0;
            updatePassword = true;
        } else if (oldpwd.equals(passhash)) {
            ret = 0;
            updatePassword = true;
        } else if ((salt == null) && (LoginCrypto.checkSha1Hash(passhash, oldpwd))) {
            ret = 0;
            updatePassword = true;
        } else if (LoginCrypto.checkSaltedSha512Hash(passhash, oldpwd, salt)) {
            ret = 0;
            updatePassword = true;
        } else {
            ret = -1;
        }
        if (updatePassword) {
            String newSalt = LoginCrypto.makeSalt();
            acc.setPassword(LoginCrypto.makeSaltedSha512Hash(newpwd, newSalt));
            acc.setSalt(newSalt);
            accDao.save(acc);
        }
        return ret;
    }

    /**
     * 验证帐号密码
     *
     * @param login
     * @param originPwd
     * @return
     */
    public int login(String login, String originPwd) {
        int loginok = LoginStatusSendVO.LOGIN_STATE_UNKNOW_ACCOUNT;
        String pwd = LoginCrypto.hexSha1(originPwd); // 用最简单的sha1

        AccountsDao acc = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO account = acc.getAccountByName(login);
        if (account == null) {
            return loginok;
        }

        final int banned = account.getBanned();
        final String passhash = account.getPassword();
        final String oldSession = account.getSessionIP();
        accountName = login;
        accId = account.getId();
        gmLevel = account.getGm();
        greason = account.getGreason();
        tempban = getTempBanCalendar(account.getTempban());
        gender = account.getGender();

        if (banned > 0 && gmLevel < 6) {
            loginok = LoginStatusSendVO.LOGIN_STATE_BANNED;
        } else {
            if (banned == -1) {
                unban(acc, account);
            }
            // Check if the passwords are correct here. :B
            if (passhash == null || passhash.isEmpty()) {
                //match by sessionIP
                if (oldSession != null && !oldSession.isEmpty()) {
                    loggedIn = getSessionIPAddress().equals(oldSession);
                    loginok = loggedIn ? LoginStatusSendVO.LOGIN_STATE_OK : LoginStatusSendVO.LOGIN_STATE_WRONG_PASSWORD;
                } else {
                    loginok = LoginStatusSendVO.LOGIN_STATE_WRONG_PASSWORD;
                    loggedIn = false;
                }
            } else if (pwd.equals(passhash)) {
                loginok = LoginStatusSendVO.LOGIN_STATE_OK;
            } else {
                pwd = LoginCrypto.hexSha256(originPwd);
                if (pwd.equals(passhash)) {
                    loginok = LoginStatusSendVO.LOGIN_STATE_OK;
                    // @TODO update password to sha-256
                } else {
                    loggedIn = false;
                    loginok = LoginStatusSendVO.LOGIN_STATE_WRONG_PASSWORD;
                }
            }
            if (getLoginState() > MapleClient.LOGIN_NOTLOGGEDIN) { // already loggedin
                if (loginok != LoginStatusSendVO.LOGIN_STATE_OK) {
                    loggedIn = false;
                    loginok = LoginStatusSendVO.LOGIN_STATE_LOGINNED;
                } else {//解卡处理
                    if (isAccountNeedToUnlock()) {
                        account.setLoggedin((byte) 0);
                        acc.save(account);
                    }
                }
            }
        }
        return loginok;
    }

    public boolean isAccountNeedToUnlock() {
        boolean isNeedUnlock = false;
        for (ChannelServer cserv : ChannelServer.getAllInstances()) {
            for (final MapleCharacter mch : cserv.getPlayerStorage().getAllCharacters()) {
                if (mch.getAccountID() == accId) {
                    try {
                        mch.getClient().sendPacket(MaplePacketCreator.serverMessagePopUp("当前账号在别的地方登录了\r\n若不是你本人操作请及时更改密码。"));
                        mch.getClient().disconnect(true, mch.getClient().getChannel() == -10);
                        Thread closeSession = new Thread() {
                            @Override
                            public void run() {
                                try {
                                    sleep(3000);
                                } catch (InterruptedException ex) {
                                }
                                mch.getClient().getSession().close();
                            }
                        };
                        closeSession.start();
                    } catch (Exception ex) {
                    }
                    isNeedUnlock = true;
                }
            }
        }

        return isNeedUnlock;
    }


    private void unban(AccountsDao accDao, AccountsPO account) {
        account.setBanned((byte) 0);
        account.setBanreason("");
        accDao.save(account);
    }

    public void setAccID(int id) {
        this.accId = id;
    }

    public int getAccID() {
        return this.accId;
    }

    public void setAccountPo(AccountsPO acc){
        this.accountPo = acc;
    }

    public AccountsPO getAccountPo() {
        return this.accountPo;
    }

    public void updateLoginState(int newstate) {
        updateLoginState(newstate, getSessionIPAddress());
    }

    public void updateLoginState(int newstate, String SessionID) {
        AccountsDao accDao = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO account = accDao.getAccountById(this.accId);

        account.setLoggedin((byte) newstate);
        account.setSessionIP(SessionID);
        account.setLastlogin(new Date());

        accDao.save(account);
        if (newstate == 0) {
            this.loggedIn = false;
            this.serverTransition = false;
        } else {
            this.serverTransition = ((newstate == 1) || (newstate == 3));
            this.loggedIn = (!this.serverTransition);
        }
    }

    public byte getLoginState() {
        AccountsDao accDao = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO account = accDao.getAccountById(this.accId);

        byte state;
        if (account.getBanned() > 0 && account.getGm() < 6) {
           this.session.close();
           throw new DatabaseException("Account doesn't exist or is banned");
        }
        // 目前没有发现有什么需要处理的生日逻辑
        birthday = account.getBirthday();
        state = account.getLoggedin();
        if ((state == MapleClient.LOGIN_SERVER_TRANSITION || state == MapleClient.CHANGE_CHANNEL) && (account.getLastlogin().getTime() + 20000L < System.currentTimeMillis())) {
            state = MapleClient.LOGIN_NOTLOGGEDIN;
            updateLoginState(state, getSessionIPAddress());
        }
        loggedIn = state == MapleClient.LOGIN_LOGGEDIN;
        return state;
    }

    public void removalTask(boolean shutdown) {
        try {
            this.player.cancelAllBuffs_();
            this.player.cancelAllDebuffs();
            if (this.player.getMarriageId() > 0) {
                MapleQuestStatus stat1 = this.player.getQuestNoAdd(MapleQuest.getInstance(160001));
                MapleQuestStatus stat2 = this.player.getQuestNoAdd(MapleQuest.getInstance(160002));
                if ((stat1 != null) && (stat1.getCustomData() != null) && ((stat1.getCustomData().equals("2_")) || (stat1.getCustomData().equals("2")))) {
                    if ((stat2 != null) && (stat2.getCustomData() != null)) {
                        stat2.setCustomData("0");
                    }
                    stat1.setCustomData("3");
                }
            }
            if ((this.player.getMapId() == 180000001) && (!this.player.isIntern())) {
                MapleQuestStatus stat1 = this.player.getQuestNAdd(MapleQuest.getInstance(123455));
                MapleQuestStatus stat2 = this.player.getQuestNAdd(MapleQuest.getInstance(123456));
                if (stat1.getCustomData() == null) {
                    stat1.setCustomData(String.valueOf(System.currentTimeMillis()));
                } else if (stat2.getCustomData() == null) {
                    stat2.setCustomData("0");
                } else {
                    int seconds = Integer.parseInt(stat2.getCustomData()) - (int) ((System.currentTimeMillis() - Long.parseLong(stat1.getCustomData())) / 1000L);
                    if (seconds < 0) {
                        seconds = 0;
                    }
                    stat2.setCustomData(String.valueOf(seconds));
                }
            }
            this.player.changeRemoval(true);
            if (this.player.getEventInstance() != null) {
                this.player.getEventInstance().playerDisconnected(this.player, this.player.getId());
            }
            IMaplePlayerShop shop = this.player.getPlayerShop();
            if (shop != null) {
                shop.removeVisitor(this.player);
                if (shop.isOwner(this.player)) {
                    if ((shop.getShopType() == 1) && (shop.isAvailable()) && (!shutdown)) {
                        shop.setOpen(true);
                    } else {
                        shop.closeShop(true, !shutdown);
                    }
                }
            }
            this.player.setMessenger(null);
            if (this.player.getMap() != null) {
                if ((shutdown) || ((getChannelServer() != null) && (getChannelServer().isShutdown()))) {
                    int questID = -1;
                    switch (this.player.getMapId()) {
                        case 240060200:
                            questID = 160100;
                            break;
                        case 240060201:
                            questID = 160103;
                            break;
                        case 280030000:
                        case 280030100:
                            questID = 160101;
                            break;
                        case 280030001:
                            questID = 160102;
                            break;
                        case 270050100:
                            questID = 160104;
                            break;
                        case 105100300:
                        case 105100400:
                            questID = 160106;
                            break;
                        case 211070000:
                        case 211070100:
                        case 211070101:
                        case 211070110:
                            questID = 160107;
                            break;
                        case 551030200:
                            questID = 160108;
                            break;
                        case 271040100:
                            questID = 160109;
                    }

                    if (questID > 0) {
                        this.player.getQuestNAdd(MapleQuest.getInstance(questID)).setCustomData("0");
                    }
                } else if (this.player.isAlive()) {
                    switch (this.player.getMapId()) {
                        case 220080001:
                        case 541010100:
                        case 541020800:
                            this.player.getMap().addDisconnected(this.player.getId());
                    }
                }

                this.player.getMap().removePlayer(this.player);
            }
        } catch (NumberFormatException e) {
            MapleLogger.error("error:", e);
        }
    }

    public void disconnect(boolean RemoveInChannelServer, boolean fromCS) {
        disconnect(RemoveInChannelServer, fromCS, false);
    }

    public void disconnect(boolean RemoveInChannelServer, boolean fromCS, boolean shutdown) {
        if (this.debugWindow != null) {
            this.debugWindow.setVisible(false);
            this.debugWindow = null;
        }
        if (this.player != null) {
            MapleMap map = player.getMap();
            MapleParty party = player.getParty();
            String namez = player.getName();
            int idz = player.getId();
            int messengerid = player.getMessenger() == null ? 0 : player.getMessenger().getId();
            BuddyList bl = player.getBuddylist();
            MaplePartyCharacter chrp = new MaplePartyCharacter(player);
            MapleMessengerCharacter chrm = new MapleMessengerCharacter(player);
            removalTask(shutdown);
            LoginServer.getLoginAuth(player.getId());
            player.saveToDB(true, fromCS);
            if (shutdown) {
                player = null;
                receiving = false;
                return;
            }
            if (!fromCS) {
                ChannelServer ch = ChannelServer.getInstance(map == null ? channel : map.getChannel());
                int chz = WorldFindService.getInstance().findChannel(idz);
                if (chz < -1) {
                    disconnect(RemoveInChannelServer, true);
                    return;
                }
                try {
                    if ((chz == -1) || (ch == null) || (ch.isShutdown())) {
                        player = null;
                        return;
                    }
                    if (messengerid > 0) {
                        WorldMessengerService.getInstance().leaveMessenger(messengerid, chrm);
                    }
                    if (party != null) {
                        party.cancelAllPartyBuffsByChr(player.getId());
                        chrp.setOnline(false);
                        WrodlPartyService.getInstance().updateParty(party.getId(), PartyOperation.LOG_ONOFF, chrp);
                        if ((map != null) && (party.getLeader().getId() == idz)) {
                            MaplePartyCharacter lchr = null;
                            for (MaplePartyCharacter pchr : party.getMembers()) {
                                if ((pchr != null) && (map.getCharacterById(pchr.getId()) != null) && ((lchr == null) || (lchr.getLevel() < pchr.getLevel()))) {
                                    lchr = pchr;
                                }
                            }
                            if (lchr != null) {
                                WrodlPartyService.getInstance().updateParty(party.getId(), PartyOperation.CHANGE_LEADER_DC, lchr);
                            }
                        }
                    }
                    if (bl != null) {
                        if (!serverTransition) {
                            WorldBuddyService.getInstance().loggedOff(namez, idz, channel, bl.getBuddyIds());
                        } else {
                            WorldBuddyService.getInstance().loggedOn(namez, idz, channel, bl.getBuddyIds());
                        }
                    }
                } catch (Exception e) {
                    MapleLogger.error(new StringBuilder().append(getLogMessage(this, "ERROR")).append(e).toString());
                } finally {
                    if ((RemoveInChannelServer) && (ch != null)) {
                        ch.removePlayer(player);
                    }
                    player = null;
                }
            } else {
                int ch = WorldFindService.getInstance().findChannel(idz);
                if (ch > 0) {
                    disconnect(RemoveInChannelServer, false);
                    return;
                }
                try {
                    if (party != null) {
                        chrp.setOnline(false);
                        WrodlPartyService.getInstance().updateParty(party.getId(), PartyOperation.LOG_ONOFF, chrp);
                    }
                    if (!serverTransition) {
                        WorldBuddyService.getInstance().loggedOff(namez, idz, this.channel, bl.getBuddyIds());
                    } else {
                        WorldBuddyService.getInstance().loggedOn(namez, idz, this.channel, bl.getBuddyIds());
                    }
                    if (player != null) {
                        player.setMessenger(null);
                    }
                } catch (Exception e) {
                    MapleLogger.error(new StringBuilder().append(getLogMessage(this, "ERROR")).append(e).toString());
                } finally {
                    if ((RemoveInChannelServer) && (ch > 0)) {
                        CashShopServer.getPlayerStorage().deregisterPlayer(player);
                    }
                    player = null;
                }
            }
        }
        if ((!this.serverTransition) && (isLoggedIn())) {
            updateLoginState(MapleClient.LOGIN_NOTLOGGEDIN, getSessionIPAddress());
        }
        this.engines.clear();
    }

    public String getSessionIPAddress() {
        InetSocketAddress addr = (InetSocketAddress) this.session.remoteAddress();
        // old IP :icelemon.vm/192.168.13.188| new ip :icelemon.vm
        MapleLogger.info("old IP :" + this.session.remoteAddress().toString().split(":")[0] + "| new ip :" + addr.getAddress().getHostAddress());
//        return addr.getHostName();
        return this.session.remoteAddress().toString().split(":")[0];
    }

    public boolean CheckIPAddress() {
        if (this.accId < 0) {
            return false;
        }
        boolean canlogin = true;

        AccountsDao accDao = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO account = accDao.getAccountById(this.accId);

        String sessionIP = account.getSessionIP();
        if (sessionIP != null) {
            canlogin = getSessionIPAddress().equals(sessionIP.split(":")[0]);
        }
        if (account.getBanned() > 0) {
            canlogin = false;
        }

        return canlogin;
    }

    public void DebugMessage(StringBuilder sb) {
        sb.append("IP: ");
        sb.append(getSession().remoteAddress());
        sb.append(" || 连接状态: ");
        sb.append(getSession().isActive());
        sb.append(" || 正在关闭: ");
//        sb.append(getSession().isClosing());
//        sb.append(" || CLIENT: ");
//        sb.append(getSession().getAttribute("CLIENT") != null);
        sb.append(getSession().isOpen());
        sb.append(" || 是否已登陆: ");
        sb.append(isLoggedIn());
        sb.append(" || 角色上线: ");
        sb.append(getPlayer() != null);
    }

    public int getChannel() {
        return this.channel;
    }

    public ChannelServer getChannelServer() {
        return ChannelServer.getInstance(this.channel);
    }

    public byte getGender() {
        return this.gender;
    }

    public void setGender(byte gender) {
        this.gender = gender;
    }

    /**
     *
     * @param gender
     */
    public void changeGender(byte gender) {
        AccountsDao accDao = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO account = accDao.getAccountById(this.accId);

        account.setGender(gender);
        accDao.save(account);
    }


    public String getSecondPassword() {
        return this.secondPassword;
    }

    public void setSecondPassword(String secondPassword) {
        this.secondPassword = secondPassword;
    }

    public String getAccountName() {
        return this.accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public int getWorld() {
        return this.world;
    }

    public void setWorld(int world) {
        this.world = world;
    }

    public int getLatency() {
        return (int) (this.lastPong - this.lastPing);
    }

    public long getLastPong() {
        return this.lastPong;
    }

    public long getLastPing() {
        return this.lastPing;
    }

    public void pongReceived() {
        this.lastPong = System.currentTimeMillis();
    }

    public void sendPing() {
        this.lastPing = System.currentTimeMillis();
        sendPacket(LoginPacket.getPing());

        PingTimer.getInstance().schedule(new Runnable() {
            @Override
            public void run() {
                if (getLatency() < 0) {
                    disconnect(true, false);
                    if (getSession().isActive()) {
                        MapleLogger.info(MapleClient.getLogMessage(MapleClient.this, "PING超时."));
                        getSession().close();
                    }
                }
            }
        }, 15000L);
    }

    public static String getLogMessage(MapleClient cfor, String message) {
        return getLogMessage(cfor, message, new Object[0]);
    }

    public static String getLogMessage(MapleCharacter cfor, String message) {
        return getLogMessage(cfor == null ? null : cfor.getClient(), message);
    }

    public static String getLogMessage(MapleCharacter cfor, String message, Object[] parms) {
        return getLogMessage(cfor == null ? null : cfor.getClient(), message, parms);
    }

    public static String getLogMessage(MapleClient cfor, String message, Object[] parms) {
        StringBuilder builder = new StringBuilder();
        if (cfor != null) {
            if (cfor.getPlayer() != null) {
                builder.append("<");
                builder.append(MapleCharacterUtil.makeMapleReadable(cfor.getPlayer().getName()));
                builder.append(" (角色ID: ");
                builder.append(cfor.getPlayer().getId());
                builder.append(")> ");
            }
            if (cfor.getAccountName() != null) {
                builder.append("(账号: ");
                builder.append(cfor.getAccountName());
                builder.append(") ");
            }
        }
        builder.append(message);

        for (Object parm : parms) {
            int start = builder.indexOf("{}");
            builder.replace(start, start + 2, parm.toString());
        }
        return builder.toString();
    }

    public boolean isSuperGM() {
        return this.gmLevel >= PlayerGMRank.SUPERGM.getLevel();
    }

    public boolean isIntern() {
        return this.gmLevel >= PlayerGMRank.INTERN.getLevel();
    }

    public boolean isGm() {
        return this.gmLevel >= PlayerGMRank.GM.getLevel();
    }

    public boolean isAdmin() {
        return this.gmLevel >= PlayerGMRank.ADMIN.getLevel();
    }

    public int getGmLevel() {
        return gmLevel;
    }

    public final void setGm(int gmLevel) {
        this.gmLevel = gmLevel;
    }

    public ScheduledFuture<?> getIdleTask() {
        return this.idleTask;
    }

    public void setIdleTask(ScheduledFuture<?> idleTask) {
        this.idleTask = idleTask;
    }

    public boolean isMonitored() {
        return this.monitored;
    }

    public void setMonitored(boolean m) {
        this.monitored = m;
    }

    public boolean isReceiving() {
        return this.receiving;
    }

    public void setReceiving(boolean m) {
        this.receiving = m;
    }

    public String getTempIP() {
        return this.tempIP;
    }

    public void setTempIP(String s) {
        this.tempIP = s;
    }

    public void setScriptEngine(String name, ScriptEngine e) {
        this.engines.put(name, e);
    }

    public ScriptEngine getScriptEngine(String name) {
        return (ScriptEngine) this.engines.get(name);
    }

    public void removeScriptEngine(String name) {
        this.engines.remove(name);
    }

    public boolean canClickNPC() {
        return this.lastNpcClick + 500L < System.currentTimeMillis();
    }

    public void setClickedNPC() {
        this.lastNpcClick = System.currentTimeMillis();
    }

    public void removeClickedNPC() {
        this.lastNpcClick = 0L;
    }

    public NPCConversationManager getCM() {
        return NPCScriptManager.getInstance().getCM(this);
    }

    public QuestActionManager getQM() {
        return QuestScriptManager.getInstance().getQM(this);
    }

    public ItemActionManager getIM() {
        return ItemScriptManager.getInstance().getIM(this);
    }

    public boolean hasCheckMac(String macData) {
        if ((macData.equalsIgnoreCase("00-00-00-00-00-00")) || (macData.length() != 17) || (this.maclist.isEmpty())) {
            return false;
        }
        return this.maclist.contains(macData);
    }

    public boolean isAccountNameUsed(String accountName) {
        AccountsDao accDao = DaoFactory.getInstance().createDao(AccountsDao.class);
        AccountsPO account = accDao.getAccountByName(accountName);
        if (account == null) {
            return false;
        } else {
            return true;
        }
    }

    protected static class CharNameAndId {

        public final String name;
        public final int id;

        public CharNameAndId(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    public void sendPacket(byte[] packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet cannot be null");
        }
        if (packet.length == 0) {
            return;//We don't want to bother netty
        }
//        connectionLock.lock();
//        try{
        this.session.writeAndFlush(packet);
//        }finally{
//            connectionLock.unlock();
//        }
    }
}
