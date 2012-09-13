package plugins.FLIRCP.storage;

import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;

import plugins.FLIRCP.freenetMagic.USK_IdentityFetcher;
import plugins.FLIRCP.freenetMagic.USK_MessageFetcher;

public class RAMstore {
	public HashMap<String, User> userMap;
	public List<Channel> channelList;
	public List<String> knownIdents;
	private String announceKey="";
	private long announceEdition=-1;
	private String currentDateString="";
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
	// FIXME: implement messageCount and BufferSize
	// TODO: create store for pubkeys of invalid messages.
	// TODO: allows automatic blocking of senders of invalid messages
	public int announce_valid = 0;
	public int announce_duplicate = 0;
	public int identity_valid = 0;
	public int message_valid = 0;
	public int announce_ddos = 0;
	public int identity_ddos = 0;
	public int message_ddos = 0;
	public Config config;
	public String welcomeText;
	
	
	public RAMstore() {
		this.userMap = new HashMap<String, RAMstore.User>();
		this.knownIdents = new ArrayList<String>();
		this.channelList = new ArrayList<RAMstore.Channel>();
		this.sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		this.currentDateString = getCurrentUtcDate();
		this.config = new Config();
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + announceEdition;
		this.welcomeText = "[b]Welcome to flircp.[/b]\n\n";
		this.welcomeText += "This plugin provides a realtime chat for Freenet.\n";
		this.welcomeText += "Latency is mostly 20 to 30 seconds on a well connected node.\n";
		this.welcomeText += "flircp is compatible with FLIP so you can communicate with other users of flircp and FLIP.\n";
		this.welcomeText += "This software is currently in alpha stage (" + config.version_major + "." + config.version_minor + "." + config.version_debug + ").\n";
		this.welcomeText += "If you find a bug or hava a feature proposal please tell me (SeekingFor) about. You can reach me in #flircp.\n\n";
		this.welcomeText += "Kudos to somedude for writing [a]FLIP[/a] in the first place. Also thanks karl for his patches for FLIP and TheSeeker for helping me out with various issues I had.\n";
		this.welcomeText += "If you want to use a IRC client instead this plugin you can check out the [a]Freenet Social Network Guide for FLIP[/a] written by JustusRanvier.\n\n";
		this.welcomeText += "As this is the first time you start flircp please take the time to configure your settings.\n";
		this.welcomeText += "Currently there is no permanent storage implemented, if you restart the plugin (or your Freenet node) all settings will be lost and you have to configure flircp again.\n\n";
		this.welcomeText += "Your announcment to other users will be kind of slow in this version, next versions will improve this.\n";
		this.welcomeText += "If you have any questions feel free to head over to #flircp.";
	}
	
	public class Config {
		// general
		public Boolean firstStart = true;
		public short concurrentAnnounceFetcher = 10;
		public short maxMessageRetriesAfterDNF = 10;
		public String messageBase = "flip";
		public int version_major = 0;
		public int version_minor = 0;
		public int version_debug = 3;
		// ident
		public String nick = "flircp_testuser";
		public String requestKey = "";
		public String insertKey = "";
		public String RSApublicKey = "";
		public String RSAprivateKey = "";
		public Boolean autojoinChannel = true;
		public List<String> joinedChannels = new ArrayList<String>();
		// web ui
		public String timeZone = "UTC";
		public int iFrameHeight = 610;
		public int iFrameWidth = 1880;
		public int iFrameRefreshInverval = 5;
		public int topiclineWidth = 211;
		public int textareaHeight = 37;
		public int textareaWidth = 241;
		public int userlistHeight = 552;
		public int userlistWidth = 150;
		public int showMessagesPerChannel=0;
		public int maxMessageBufferSizePerChannel=1024;
		public int chatLineWidth = 233;
		public Boolean showJoinsParts = false;
		public Boolean enableJavaScriptToScrollDownToLatestMessage = true;
		public int iFrameFontSize = 10;
		public Config() {
			// TODO: load config from file / db / whatever
		}
	}
	public void setInsertKey(String insertKey) {
		if(!config.insertKey.equals(insertKey)) { config.insertKey = insertKey; }
	}
	public void setRequestKey(String requestKey) {
		if(!config.requestKey.equals(requestKey)) {
			addNewUser(requestKey, true);
			userMap.remove(config.requestKey);
			config.requestKey = requestKey;
		}
	}
	public class Channel implements Comparable<Channel>{
		public String name;
		public String topic;
		public List<PlainTextMessage> messages;
		public int lastMessageIndex;
		public int lastShowedIndex;
		public int currentUserCount;
		public long lastMessageActivity;
		public Channel(String channelName) {
			this.name = channelName;
			this.topic = "";
			this.lastMessageIndex = -1;
			this.lastShowedIndex = -1;
			this.messages = new ArrayList<RAMstore.PlainTextMessage>();
			this.currentUserCount = 0;
			this.lastMessageActivity = 0;
		}
		@Override
		public int compareTo(Channel o) {
			return this.name.compareTo(o.name);
		}
		
	}
	public Boolean addNewChannel(String channelName) {
		// don't allow:
		// - channels not starting with #
		// - channels with more than one #
		// - channels without #
		// - channels with empty name
		// TODO: add max size
		if(channelName.startsWith("#") &&
				channelName.replace("#", "").length() == channelName.length() - 1 &&
				!channelName.equals("#")) {
			Boolean found = false;
			for(Channel chan : channelList) {
				if(chan != null && chan.name.equals(channelName)) {
					found = true;
					break;
				}
			}
			if(!found) {
				channelList.add(new Channel(channelName));
				Collections.sort(channelList);
			}
		} else {
			return false;
		}
		return true;
	}
	public Channel getChannel(String channelName) {
		for(Channel channel : channelList) {
			if(channel.name.equals(channelName)) {
				return channel;
			}
		}
		return null;
	}
	public void setTopic(String channelName, String newTopic) {
		if(getChannel(channelName) != null) {
			getChannel(channelName).topic = newTopic;
		}
//		for(channel channel : channelList) {
//			if(channel.name.equals(channelName)) {
//				channel.topic = newTopic;
//			}
//		}
	}
	public class User {
		// FIXME: change editions to long
		// TODO: clean this up and remove unnecessary variables.
		public String nick;
		public String originalNick;
		public String RSA;
		public long identEdition;
		public long messageEditionHint;
		public Boolean identityRequested;
		public List<String> channels;
		public Boolean updatedMessageIndexFromIndentityMessage;
		public int messageCount;
		public int channelCount;
		public long lastActivity;
		public long identity_ddos;
		public long message_ddos;
		public Boolean identSubscriptionActive;
		public List<String> failedMessageRequests;
		public long lastMessageTime;
		public User() {
			this.nick = "";
			this.originalNick = "";
			this.RSA = "";
			this.identEdition = -1;
			this.messageEditionHint = -1;
			this.identityRequested = false;
			this.channels = new ArrayList<String>();
			this.updatedMessageIndexFromIndentityMessage = false;
			this.messageCount = 0;
			this.channelCount = 0;
			this.lastActivity = new Date().getTime();
			this.identity_ddos = 0;
			this.message_ddos = 0;
			this.identSubscriptionActive = false;
			this.failedMessageRequests = new ArrayList<String>();
			this.lastMessageTime = new Date().getTime();
		}
	}
	public class PlainTextMessage {
		public String message;
		public String ident;
		public String nickname;
		public String chan;
		public long timeStamp;
		public int index;
		public PlainTextMessage(String ident, String message, String nickname, String chan, long timeStamp, int messageIndex) {
			this.message = message;
			this.ident = ident;
			this.nickname = nickname;
			this.chan = chan;
			this.timeStamp = timeStamp;
			this.index = messageIndex;
		}
	}

	// channel functions
	public void addNewUser(String requestKey) {
		addNewUser(requestKey, false);
	}
	public void addNewUser(String requestKey, Boolean ownUser) {
		User newUser = new User();
		userMap.put(requestKey, newUser);
		if(!ownUser) { knownIdents.add(requestKey); }
	}
	public void addUserToChannel(String requestKey, String channel) {
		userMap.get(requestKey).lastActivity = new Date().getTime();
		if(!userMap.get(requestKey).channels.contains(channel)) {
			userMap.get(requestKey).channels.add(channel);
			userMap.get(requestKey).channelCount += 1;
			getChannel(channel).currentUserCount += 1;
			// TODO: make join/parts showing as new messages configurable
			//getChannel(channel).lastMessageIndex += 1;
			if(config.showJoinsParts) {
				getChannel(channel).messages.add(new PlainTextMessage(requestKey, getNick(requestKey) + " joined " + channel + "." , "*", channel, new Date().getTime(), getChannel(channel).lastMessageIndex));
			}
		}
	}
	public Boolean isUserInChannel(String requestKey, String channel) {
		return userMap.get(requestKey).channels.contains(channel);
	}
	public void removeUserFromChannel(String requestKey, String channel) {
		userMap.get(requestKey).lastActivity = new Date().getTime();
		removeUserFromChannel(requestKey, channel, false);
	}
	public void removeUserFromChannel(String requestKey, String channel, Boolean timeout) {
		if(isUserInChannel(requestKey, channel)) {
			getChannel(channel).currentUserCount -= 1;
			// TODO: make join/parts showing as new messages configurable
			//getChannel(channel).lastMessageIndex += 1;
			if(!timeout) {
				if(config.showJoinsParts) {
					getChannel(channel).messages.add(new PlainTextMessage(requestKey, getNick(requestKey) + " left " + channel + "." , "*", channel, new Date().getTime(), getChannel(channel).lastMessageIndex));
				}
			} else {
				if(config.showJoinsParts) {
					getChannel(channel).messages.add(new PlainTextMessage(requestKey, getNick(requestKey) + " left " + channel + " (timed out)." , "*", channel, new Date().getTime(), getChannel(channel).lastMessageIndex));
				}
			}
			userMap.get(requestKey).channelCount -= 1;
			userMap.get(requestKey).channels.remove(channel);
		}
	}
	public void updateLastMessageActivityForChannel(String channel) {
		getChannel(channel).lastMessageActivity = new Date().getTime();
	}
	public List<String> getUsersInChannel(String channel) {
		List<String> userList = new ArrayList<String>();
		for(String ident : knownIdents) {
			if(isUserInChannel(ident, channel)) {
				userList.add(userMap.get(ident).nick);
			}
		}
		userList.add(config.nick);
		Collections.sort(userList);
		return userList;
	}
	
	// identity functions
	// FIXME: clean this up, mixed with general functions like utc date and getMessageBase()
	public void checkUserActivity() {
		// 1000 ms * 60 seconds * 17 minutes
		long datetimeToKick = new Date().getTime() - 17 * 60 * 1000;
		try {
			for(String ident : knownIdents) {
				if(userMap.get(ident).lastActivity < datetimeToKick && userMap.get(ident).channelCount > 0) {
					for(String channel : userMap.get(ident).channels) {
						removeUserFromChannel(ident, channel, true);
					}
				}
			}
		} catch (ConcurrentModificationException e) {
			// ignore exception. doesn't matter if we check now or on the next run
		}
	}
	public Boolean isNickInUseByOtherIdentity(String requestKey, String nick) {
		if(nick.equals(config.nick)) { return true; }
		for(String ident : knownIdents) {
			if(nick.equals(userMap.get(ident).nick)) {
				if(!requestKey.equals(ident)) {
					return true;
				}
			}
		}
		return false;
	}
	public String getWhoIs(String nickname) {
		if(!config.nick.equals(nickname)) {
			for(String ident : knownIdents) {
				if(userMap.get(ident).nick.equals(nickname)) {
					String channels = "";
					for(String channel : userMap.get(ident).channels) {
						channels += channel + " ";
					}
					if(channels.length() > 0) { channels = channels.substring(0, channels.length() - 1); }
					return "[" + nickname + "]" + " Public key: " + ident + "\n[" + nickname + "]" + " channels: " + channels + "\n[" + nickname + "]" + " has been idle: " + ((new Date().getTime() - userMap.get(ident).lastMessageTime) / 1000) + " seconds" ;
				}
			}
		} else {
			return "[" + nickname + "]" + " Public key: " + config.requestKey + "\n[" + nickname + "]" + " has been idle: " + ((new Date().getTime() - userMap.get(config.requestKey).lastMessageTime) / 1000) + " seconds" ;
		}
		return "[WHOIS] nick not found.";
	}
	public Boolean isIdentityFound(String requestKey) {
		return userMap.get(requestKey).identityRequested;
	}
	public void setIdentityFound(String requestKey, Boolean found) {
		userMap.get(requestKey).identityRequested = found;
	}
	public void setAllEditionsToZero(USK_IdentityFetcher identFetcher, USK_MessageFetcher messageFetcher) {
		// FIXME: use two version, 5 min before UTC datechange to create new
		// FIXME: subscriptions and 5 min after UTC datechange to stop old subscriptions.
		setAnnounceEdition(-1);
		try {
			for(String ident : knownIdents) {
				setIdentEdition(ident, 0);
				setMessageEditionHint(ident, 0);
			}
			identFetcher.resetSubcriptions();
			messageFetcher.resetSubcriptions();
		} catch (ConcurrentModificationException e) {
			// ident was added during loop
			setAllEditionsToZero(identFetcher, messageFetcher);
		}
	}
	public long getAnnounceEdition(){
		return this.announceEdition;
	}
	public void setAnnounceEdition(long newAnnounceEdition){
		this.announceEdition=newAnnounceEdition;
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + this.announceEdition;
	}
	public String getCurrentDateString() {
		return this.currentDateString;
	}
	public String getCurrentUtcDate(){
		return sdf.format(new Date());
	}
	public void setCurrentDateString(String newUtcDate){
		this.currentDateString=newUtcDate;
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + this.announceEdition;
	}
	public String getMessageBase() {
		return this.config.messageBase;
	}
	public void setMessageBase(String newMessageBase) {
		this.config.messageBase=newMessageBase;
		this.announceKey="KSK@" + this.config.messageBase + "|"+ this.currentDateString + "|Announce|" + this.announceEdition;
	}
	public String getAnnounceKey(){
		return this.announceKey;
	}
	public String getNick(String identRequestKey) {
		return userMap.get(identRequestKey).nick;
	}
	public String getOriginalNick(String identRequestKey) {
		return userMap.get(identRequestKey).originalNick;
	}
	public void setNick(String identRequestKey, String newNick) {
		setNick(identRequestKey, newNick, newNick);
	}
	public void setNick(String identRequestKey, String newNick, String newOriginalNick) {
		userMap.get(identRequestKey).nick = newNick;
		userMap.get(identRequestKey).originalNick = newOriginalNick;
	}
	public long getIdentEdition(String identRequestKey) {
		return userMap.get(identRequestKey).identEdition;
	}
	public void setIdentEdition(String identRequestKey, long identEdition) {
		userMap.get(identRequestKey).identEdition = identEdition;
	}
		
	public long getMessageEditionHint(String identRequestKey) {
		return userMap.get(identRequestKey).messageEditionHint;
	}
	public void setMessageEditionHint(String identRequestKey, long messageEditionHint) {
		userMap.get(identRequestKey).messageEditionHint = messageEditionHint;
	}
	
	public String getRSA(String identRequestKey) {
		return userMap.get(identRequestKey).RSA;
	}
	public void setRSA(String identRequestKey, String RSAkey) {
		userMap.get(identRequestKey).RSA = RSAkey;
	}
	public String getIdentIconHash(String identPubkey){
		return identPubkey;
	}
}
