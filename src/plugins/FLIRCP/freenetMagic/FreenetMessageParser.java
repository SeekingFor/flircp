package plugins.FLIRCP.freenetMagic;

import java.net.MalformedURLException;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;

import freenet.keys.FreenetURI;

import plugins.FLIRCP.storage.RAMstore;
import plugins.FLIRCP.storage.RAMstore.Channel;

public class FreenetMessageParser extends Thread {
	private USK_IdentityFetcher mUSK_IdentityFetcher;
	private USK_MessageFetcher mUSK_MessageFetcher;
	private Deque<FreenetMessage> mMessageQueue;
	private RAMstore mStorage;
	private float processingTime = 0;
	private Boolean isRunning;
	
	public FreenetMessageParser(RAMstore storage, USK_IdentityFetcher mUSK_IdentityFetcher, USK_MessageFetcher mUSK_MessageFetcher) {
		this.setName("flircp.freenetMessageParser");
		this.mMessageQueue = new ArrayDeque<FreenetMessage>();
		this.mStorage = storage;
		this.mUSK_IdentityFetcher = mUSK_IdentityFetcher;
		this.mUSK_MessageFetcher = mUSK_MessageFetcher;
	}

	public int getCurrentQueueSize() {
		return mMessageQueue.size();
	}
	
	public float getProcessingTime() {
		return processingTime / 1000;
	}
	
	public synchronized void addMessage(String source, String ident, String content, String freenetURI) {
		mMessageQueue.addFirst(new FreenetMessage(source, ident, content, freenetURI));
		this.notify();
	}
	
	public void terminate() {
		isRunning = false;
		this.interrupt();
	}
	
	private void parseMessage(FreenetMessage message) {
		long now = new Date().getTime();
		if("announce".equals(message.source)) {
			if(parseAnnounceMessage(message)) {
				mStorage.announce_valid += 1;
			} else {
				mStorage.announce_ddos += 1;
			}
		} else if("identity".equals(message.source)) {
			if(parseIdentMessage(message)) {
				mStorage.identity_valid += 1;
			} else {
				mStorage.identity_ddos +=1;
				mStorage.userMap.get(message.ident).identity_ddos += 1;
			}
		} else if("message".equals(message.source)) {
			if(parseMessageMessage(message)) {
				mStorage.message_valid += 1;
			} else {
				mStorage.message_ddos += 1;
				mStorage.userMap.get(message.ident).message_ddos += 1;
			}
		} else {
			System.err.println("[FreenetMessageParser] not supported source: " + message.source);
		}
//		if(processingTime != Float.parseFloat("0")) {
//			processingTime = (processingTime + new Date().getTime() - now) / 2;
//		} else {
//			processingTime = new Date().getTime() - now;
//		}
		// TODO: fix average job time calculation
		processingTime = new Date().getTime() - now;
	}
	
	@Override
	public void run() {
		isRunning = true;
		while(!isInterrupted() && isRunning) {
			try {
				synchronized (this) {
					if(mMessageQueue.size() > 0) {
						parseMessage(mMessageQueue.pollLast());
					} else {
						this.wait();						
					}
				}
			} catch (InterruptedException e) {
				//System.err.println("[FreenetMessageParser]::run() InterruptedExcpetion");
			}
		}
	}
	
	private Boolean parseAnnounceMessage(FreenetMessage message) {
		if(!mStorage.knownIdents.contains(message.content) && !message.content.equals(mStorage.config.requestKey)){
			try {
				// FreenetURI testURI = new FreenetURI(message.content + "test");
				new FreenetURI(message.content + "test");
			} catch (MalformedURLException e) {
				// URI is not valid.
				System.err.println("[FreenetMessageParser] got illegal announce message from " + message.uri + "\nmessage was: " + message.content);
				return false;
			}
			mStorage.addNewUser(message.content);
			mUSK_IdentityFetcher.addInitialSubscription(message.content);
		} else {
			mStorage.announce_duplicate += 1;
		}
		return true;
	}
	
	private Boolean parseIdentMessage(FreenetMessage message) {
		HashMap<String, String> keyPairs = parseFLIPmessage(message.content);
		Boolean failed = false;
		if(keyPairs.get("name") != null) {
			String nick = keyPairs.get("name");
			if(!nick.equals("")) {
				if(!mStorage.getOriginalNick(message.ident).equals(nick)) {
					String originalNick = nick;
					if(mStorage.isNickInUseByOtherIdentity(message.ident, nick)) {
						nick = nick + "_" + message.ident.substring(4,6);
						if(mStorage.isNickInUseByOtherIdentity(message.ident, nick)) {
							nick = nick + message.ident.substring(6,7);
							if(mStorage.isNickInUseByOtherIdentity(message.ident, nick)) {
								nick = nick + message.ident.substring(7,8);
								if(mStorage.isNickInUseByOtherIdentity(message.ident, nick)) {
									nick = nick + "_imposer!";								
								}
							}
						}
					}
					if(!mStorage.getOriginalNick(message.ident).equals("")) {
						System.err.println("[IdentityFetcher] got new nick for ident " + message.ident);
						Channel chan;
						for(String channel : mStorage.userMap.get(message.ident).channels) {
							if(channel != null && !channel.equals("")) {
								chan = mStorage.getChannel(channel);
								chan.lastMessageIndex += 1;
								chan.messages.add(mStorage.new PlainTextMessage(message.ident, "nick changed from " + mStorage.getOriginalNick(message.ident) + " (" + mStorage.getNick(message.ident) + ") to " + originalNick + " (" + nick + ").", "*", chan.name, new Date().getTime(), chan.lastMessageIndex));
							}
						}
					}
					mStorage.setNick(message.ident, nick, originalNick);
				}
			} else {
				//System.err.println("[FreenetMessageParser] found empty nick for " + message.ident);
			}
		} else {
			failed = true;
		}
		if(keyPairs.get("lastmessageindex") != null) {
			if(!mStorage.isIdentityFound(message.ident)) {
				// we don't want old stuff, are we?
				// TODO: control this by a configuration setting
				mStorage.setMessageEditionHint(message.ident, Long.parseLong(keyPairs.get("lastmessageindex")));
				mStorage.setIdentityFound(message.ident, true);
				//System.err.println("[FreenetMessageParser] starting fetching messages for nick " + mStorage.getNick(message.ident));
				mUSK_MessageFetcher.addInitialMessageSubscription(message.ident);
			}
		} else {
			failed = true;
		}
		if(keyPairs.get("rsapublickey") != null) {
			if(!mStorage.getRSA(message.ident).equals(keyPairs.get("rsapublickey"))) {
				if(!mStorage.userMap.get(message.ident).RSA.equals("")) {
					System.err.println("[FreenetMessageParser] got new public RSA key for ident " + message.ident);
				}
				mStorage.setRSA(message.ident, keyPairs.get("rsapublickey"));
			}
		} else {
			failed = true;
		}
		if(failed) {
			// something went wrong while parsing the identity message
			// ddos + FIXME: remove ident from knownidents?
			System.err.println("[FreenetMessageParser] something went wrong while parsing the identity message. Message was:");
			System.err.println(message.content);
			System.err.println("[FreenetMessageParser] came from " + message.uri);
			System.err.println("[FreenetMessageParser] assuming ddos.");
			return false;
		}
		return true;
	}
	
	private Boolean parseMessageMessage(FreenetMessage message) {
		// TODO: parse senddate; if diff current utc datetime > x ignore message?
		HashMap<String, String> keyPairs = parseFLIPmessage(message.content);
		String type = keyPairs.get("type");
		Boolean failed = false;
		if(type == null || type.equals("")) {
			System.err.println("[FreenetMessageParser] got invalid message without type definition for " + message.uri + ". assuming ddos. content was:");
			System.err.println(message.content);
			return false;
		} else if(type.equals("joinchannel")) {
			if(keyPairs.get("channel") != null) {
				if (mStorage.addNewChannel(keyPairs.get("channel"))) {
					mStorage.addUserToChannel(message.ident, keyPairs.get("channel"));
				}
			} else {
				failed = true;
			}
		} else if(type.equals("channelmessage")) {
			if(keyPairs.get("channel") != null && keyPairs.get("body") != null) {
				String channel = keyPairs.get("channel");
				String content = keyPairs.get("body");
				if (mStorage.addNewChannel(channel)) {
					mStorage.addUserToChannel(message.ident, channel);
					mStorage.getChannel(channel).lastMessageIndex += 1;
					if(content.startsWith((char) 1 + "ACTION")) {
						mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(message.ident, mStorage.getNick(message.ident) + content.replace((char) 1 + "ACTION", "").replace("" + (char) 1, ""), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
					} else if(content.contains("" + (char) 1)) {
						mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(message.ident, "[illegal CTCP request] from " + mStorage.getNick(message.ident) + ": " + content.replace("" + (char) 1, ""), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
					}else {
						mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(message.ident, content, mStorage.getNick(message.ident), channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
					}
					mStorage.updateLastMessageActivityForChannel(channel);
					mStorage.userMap.get(message.ident).lastMessageTime = new Date().getTime();
					mStorage.userMap.get(message.ident).messageCount += 1;
				}
			} else {
				failed = true;
			}
		} else if(type.equals("keepalive")) {
			if(keyPairs.get("channels") != null) {
				for(String singleChannel : keyPairs.get("channels").split(" ")) {
					if(mStorage.addNewChannel(singleChannel)) {
						mStorage.addUserToChannel(message.ident, singleChannel);
					}
				}
				for(String key : keyPairs.keySet()) {
					if(key.startsWith("topic.#")) {
						// syntax for channel topics in headers: topic.#channel.replace("=",":")=some crazy topic
						String channel = key.split("\\.", 2)[1].replace(":", "=");
						String topic = keyPairs.get(key);
						if (mStorage.addNewChannel(channel)) {
							mStorage.addUserToChannel(message.ident, channel);
							if(!topic.equals("")
									&& !topic.equals(mStorage.getChannel(channel).topic)
									&& mStorage.getChannel(channel).topic.equals("")) {
								// TODO: add config option for new topic = new message
								mStorage.getChannel(channel).lastMessageIndex += 1;
								mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(message.ident, "found new topic through automatic topic propagation from " + mStorage.getNick(message.ident) + ": " + topic, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
								mStorage.setTopic(channel, topic);
							}
						}
					}
				}
			} else {
				failed = true;
			}
		} else if(type.equals("partchannel")) {
			if(keyPairs.get("channel") != null) {
				if (mStorage.addNewChannel(keyPairs.get("channel"))) {
					mStorage.removeUserFromChannel(message.ident, keyPairs.get("channel"));
				}
			} else {
				failed = true;
			}
		} else if(type.equals("settopic")) {
			if(keyPairs.get("channel") != null && keyPairs.get("body") != null) {
				String channel = keyPairs.get("channel");
				String content = keyPairs.get("body");
				if(mStorage.addNewChannel(channel)) {
					mStorage.addUserToChannel(message.ident, channel);										
					mStorage.setTopic(channel, content);
					// TODO: add config option for new topic = new message
					mStorage.getChannel(channel).lastMessageIndex += 1;
					mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(message.ident, mStorage.getNick(message.ident) + " changed topic to: " + content, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
					mStorage.userMap.get(message.ident).lastMessageTime = new Date().getTime();
				}
			} else {
				failed = true;
			}
		} else {
			// not implemented type
			if(!type.equals("privatemessage")) {
				System.err.println("[FreenetMessageParser] invalid message type '" + type + "' from " + message.uri + ". assuming ddos. content was:");
				System.err.println(message.content);
				return false;
			}
		}
		if(failed) {
			System.err.println("[FreenetMessageParser] got invalid message of type '" + type + "' from " + message.uri + ". assuming ddos. content was:");
			System.err.println(message.content);
			return false;
		}
		return true;
	}
	
	private HashMap<String, String> parseFLIPmessage(String content) {
		HashMap<String, String> outMap = new HashMap<String, String>();
		Boolean headerFinnished = false;
		outMap.put("body", "");
		for(String line : content.split("\n")) {
			if(line.length() > 0) {
				if(!headerFinnished) {
					if(line.contains("=")) {
						outMap.put(line.split("=", 2)[0] , line.split("=", 2)[1]);
					} else {
						System.err.println("[FreenetMessageParser] unable to parse keypair: " + line);
					}
				} else {
					outMap.put("body", outMap.get("body") + line + "\n");
				}
			} else if(!headerFinnished) {
				headerFinnished = true;
			} else {
				outMap.put("body", outMap.get("body") + line + "\n");
			}
		}
		// remove last \n from body
		if(outMap.get("body").length() > 0) {
			outMap.put("body", outMap.get("body").substring(0, outMap.get("body").length() -1));
		}
		return outMap;
	}

	private class FreenetMessage {
		public String source;
		public String ident;
		public String content;
		public String uri;
		public FreenetMessage(String source, String ident, String content, String freenetURI) {
			this.source = source;
			this.ident = ident;
			this.content = content;
			this.uri = freenetURI;
		}
	}
}
