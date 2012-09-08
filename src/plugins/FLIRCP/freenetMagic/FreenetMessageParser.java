package plugins.FLIRCP.freenetMagic;

import java.net.MalformedURLException;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

import freenet.keys.FreenetURI;

import plugins.FLIRCP.storage.RAMstore;
import plugins.FLIRCP.storage.RAMstore.channel;

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
	
	public void addMessage(String source, String ident, String content, String freenetURI) {
		mMessageQueue.addFirst(new FreenetMessage(source, ident, content, freenetURI));
	}
	
	public void terminate() {
		// FIXME: isRunning should be isTerminating()
		isRunning = false;
		this.interrupt();
	}

	@Override
	public void run() {
		isRunning = true;
		FreenetMessage message;
		long now;
		while(!isInterrupted() && isRunning) {
			try {
				message = mMessageQueue.pollLast();
				if(message != null) {
					now = new Date().getTime();
					if("announce".equals(message.source)) {
						if(parseAnnounceMessage(message)) {
							// TODO: add event here?
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
					if(processingTime != 0) {
						processingTime = (processingTime + new Date().getTime() - now) / 2;
					} else {
						processingTime = new Date().getTime() - now;
					}
				}
				sleep(50);
			} catch (InterruptedException e) {
				//System.err.println("[FreenetMessageParser]::run() InterruptedExcpetion");
			}
		}
	}
	
	private Boolean parseAnnounceMessage(FreenetMessage message) {
		if(!mStorage.knownIdents.contains(message.content) && !message.content.equals(mStorage.config.requestKey)){
			try {
				FreenetURI testURI = new FreenetURI(message.content + "test");
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
		mStorage.announce_valid += 1;
		return true;
	}
	
	private Boolean parseIdentMessage(FreenetMessage message) {
		try {
			String parseResult[] = message.content.split("\n");
			// FIXME: loop of all elements, split("="), if [0].equals("name"|"lastmessageindex"|"rsapublickey") { ... }
			long lastmessageindex=Long.parseLong(parseResult[0].split("=")[1]);
			String nick;
			if(!parseResult[1].equals("name=")) {
				nick=parseResult[1].split("=")[1];
			} else {
				nick = "";
				System.err.println("[FreenetMessageParser] found empty nick for " + message.ident);
			}
			String rsakey=parseResult[2].split("=")[1];
			if(!mStorage.isIdentityFound(message.ident)) {
				mStorage.setIdentityFound(message.ident, true);
			}
			if(!nick.equals("")) {
				if(mStorage.isNickInUseByOtherIdentity(message.ident, nick)) {
					// FIXME: add rnd numbers
					nick = nick + "_imposer!";
				}
				if(!mStorage.getNick(message.ident).equals(nick)) {
					if(!mStorage.getNick(message.ident).equals("")) {
						System.err.println("[IdentityFetcher] got new nick for ident " + message.ident);
						channel chan;
						for(String channel : mStorage.userMap.get(message.ident).channels) {
							if(channel != null && !channel.equals("")) {
								chan = mStorage.getChannel(channel);
								chan.lastMessageIndex += 1;
								chan.messages.add(mStorage.new PlainTextMessage(message.ident, "nick changed from " + mStorage.getNick(message.ident) + " to " + nick + ".", "*", chan.name, new Date().getTime(), chan.lastMessageIndex));
							}
						}
					}
					mStorage.setNick(message.ident, nick);
				}
			}
			if(!mStorage.userMap.get(message.ident).RSA.equals(rsakey)) {
				if(!mStorage.userMap.get(message.ident).RSA.equals("")) {
					System.err.println("[FreenetMessageParser] got new public RSA key for ident " + message.ident);
				}
				mStorage.setRSA(message.ident, rsakey);
			}
			// set initial message edition
			if(mStorage.getMessageEditionHint(message.ident) == -1) {
				// we don't want old stuff, are we?
				// FIXME: control this by a configuration setting
				mStorage.setMessageEditionHint(message.ident, lastmessageindex);
				mUSK_MessageFetcher.addInitialMessageSubscription(message.ident);
			}
			return true;
		} catch (ArrayIndexOutOfBoundsException e1) {
			// something went wrong while parsing the identity message
			// ddos + FIXME: remove ident from knownidents?
			System.err.println("[FreenetMessageParser] something went wrong while parsing the identity message. Message was:");
			System.err.println(message.content);
			System.err.println("[FreenetMessageParser] came from " + message.uri);
			System.err.println("[FreenetMessageParser] assuming ddos.");
			return false;
		}
	}
	
	private Boolean parseMessageMessage(FreenetMessage message) {
		String type = "";
		try {
			String parseResult[] = message.content.split("\n");
			for(String line : parseResult) {
				if(line.contains("type=")) {
					type = line.split("=")[1];
					break;
				}
			}
			if(type.equals("")) {
				System.err.println("[FreenetMessageParser] got invalid message without type definition for " + message.uri);
				return false;
			} else {
				// TODO: parse senddate; if diff current datetime > x ignore message?
				String channel;
				String senddate;
				String content;
				if(type.equals("joinchannel")) {
					//channel=#test
					//sentdate=2012-08-25 12:37:27
					//type=joinchannel
					channel = parseResult[0].split("=")[1];
					senddate = parseResult[1].split("=")[1];
					mStorage.addNewChannel(channel);
					mStorage.addUserToChannel(message.ident, channel);
				}
				else if(type.equals("channelmessage")) {
					//channel=#test
					//sentdate=2012-08-25 12:42:58
					//type=channelmessage
					//
					//ping
					channel = parseResult[0].split("=")[1];
					senddate = parseResult[1].split("=")[1];
					content = message.content.split("type="+type+"\n")[1].replace("\n", "");
					mStorage.addNewChannel(channel);
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
					mStorage.userMap.get(message.ident).messageCount += 1;
				}
				else if(type.equals("keepalive")) {
					//channels=#anonet #flip #fms #freemail #freenet #freenode-bridge #fsng #irc2p-bridge #linux #sone #test
					//sentdate=2012-09-01 20:01:30
					//type=keepalive
					// FIXME: add lastActivity for channel. if lastActivity < x remove user from channel 
					if(parseResult[0].contains("channel")) {
						senddate = parseResult[1].split("=")[1];
						for(String singleChannel : parseResult[0].split("=")[1].split(" ")) {
							mStorage.addNewChannel(singleChannel);
							mStorage.addUserToChannel(message.ident, singleChannel);
						}
					}
				}
				else if(type.equals("partchannel")) {
					//channel=#test
					//sentdate=2012-08-25 13:31:25
					//type=partchannel
					channel = parseResult[0].split("=")[1];
					senddate = parseResult[1].split("=")[1];
					mStorage.addNewChannel(channel);
					mStorage.removeUserFromChannel(message.ident, channel);
				}
				else if(type.equals("settopic")) {
					//channel=#test
					//sentdate=2012-09-01 21:13:56
					//type=settopic
					//
					//crazy new topic
					channel = parseResult[0].split("=")[1];
					senddate = parseResult[1].split("=")[1];
					content = message.content.split("type="+type+"\n")[1].replace("\n","");
					mStorage.addNewChannel(channel);
					mStorage.addUserToChannel(message.ident, channel);										
					mStorage.setTopic(channel, content);
					mStorage.getChannel(channel).lastMessageIndex += 1;
					mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(message.ident, mStorage.getNick(message.ident) + " changed topic to: " + content, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
				} else {
					// not implemented type
					// ignore privatemessage
					if(!type.equals("privatemessage")) {
						System.err.println("[FreenetMessageParser] got not implemented or invalid message type: " + type + " for " + message.uri);
						return false;
					}
				}
			}
		return true;
		} catch (ArrayIndexOutOfBoundsException e1) {
			// something went wrong while parsing the identity message
			// ddos + FIXME: remove ident from knownidents?
			System.err.println("[FreenetMessageParser] something went wrong while parsing Message- message. Message was:");
			System.err.println(message.content);
			System.err.println("[FreenetMessageParser] came from " + message.uri);
			System.err.println("[FreenetMessageParser] assuming ddos.");
			return false;
		}
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
