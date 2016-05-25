package plugins.FLIRCP;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.TimeZone;

import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertContext;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.api.BucketFactory;
import freenet.support.api.RandomAccessBucket;
import plugins.FLIRCP.freenetMagic.Async_AnnounceFetcher;
import plugins.FLIRCP.freenetMagic.FreenetMessageParser;
import plugins.FLIRCP.freenetMagic.USK_IdentityFetcher;
import plugins.FLIRCP.freenetMagic.USK_MessageFetcher;
import plugins.FLIRCP.storage.RAMstore;
import plugins.FLIRCP.storage.RAMstore.PlainTextMessage;
import plugins.FLIRCP.storage.RAMstore.Channel;

public class Worker extends Thread  {
	private RAMstore mStorage;
	private FreenetMessageParser mFreenetMessageParser;
	private HighLevelSimpleClient mInserter;
	private Deque<PlainTextMessage> mQueue;
	private BucketFactory mTmpBucketFactory;
	private Async_AnnounceFetcher mAsyncAnnounceFetcher;
	private USK_IdentityFetcher mIdentityFetcher_usk;
	private USK_MessageFetcher mMessageFetcher_usk;
	private float processingTime = 0;
	private long announceEdition = 0;
	private volatile boolean isRunning;
	
	public void terminate() {
		mAsyncAnnounceFetcher.isRunning = false;
		mIdentityFetcher_usk.removeAllFetchers();
		mMessageFetcher_usk.removeAllFetchers();
		mFreenetMessageParser.terminate();
		isRunning = false;
		this.interrupt();
	}
	
	public Worker(PluginRespirator pr, RAMstore Storage) {
		mStorage = Storage;
		this.setName("flircp.worker");
		// FIXME: use pointer for worker and assign Fetchers and Parser in new instances from the worker pointer
		mAsyncAnnounceFetcher = new Async_AnnounceFetcher(Storage, pr.getNode().clientCore.makeClient((short) 1, false, true));
		mIdentityFetcher_usk = new USK_IdentityFetcher(pr, Storage);
		mMessageFetcher_usk = new USK_MessageFetcher(Storage, pr);
		mFreenetMessageParser = new FreenetMessageParser(Storage, mIdentityFetcher_usk, mMessageFetcher_usk);
		mIdentityFetcher_usk.setFreenetMessageParser(mFreenetMessageParser);
		mMessageFetcher_usk.setFreenetMessageParser(mFreenetMessageParser);
		mAsyncAnnounceFetcher.setFreenetMessageParser(mFreenetMessageParser);
		mFreenetMessageParser.start();
		mAsyncAnnounceFetcher.startFetching();
		// insert at realtime prio 1.
		mInserter = pr.getNode().clientCore.makeClient((short) 1, false, true);
		mQueue = new ArrayDeque<>();
		mTmpBucketFactory = pr.getNode().clientCore.tempBucketFactory;
	}
	
	public Async_AnnounceFetcher getAnnounceFetcher() {
		return mAsyncAnnounceFetcher;
	}
	public USK_IdentityFetcher getIdentityFetcher() {
		return mIdentityFetcher_usk;
	}
	public USK_MessageFetcher getMessageFetcher() {
		return mMessageFetcher_usk;
	}
	public FreenetMessageParser getMessageParser() {
		return mFreenetMessageParser;
	}
	
	public int getCurrentQueueSize() {
		return mQueue.size();
	}
	public float getProcessingTime() {
		return processingTime / 1000;
	}
	public boolean insertMessage(PlainTextMessage message) {
		// inserts message into queue
		if(mStorage.config.insertKey.equals("")) {
			System.err.println("[WORKER]::insertMessage() ERROR: identInsertKey is empty");
			return false;
		}
		mQueue.addLast(message);
		mStorage.userMap.get(mStorage.config.requestKey).lastMessageTime = new Date().getTime();
		//System.err.println("[Worker]::insertMessage() added message \"" + message + "\" type \"" + message.ident + "\" to insert queue.");
		return true;
	}
	
	@Override
	public void run() {
		// count tcp packets. if its odd multiply with 3, add 1. if its even divide it by two. why? because we can.
		isRunning = true;
		PlainTextMessage currentJob;
		long now = new Date().getTime();
		long lastAnnounceInsert = now - 6 * 60 * 60 * 1000 - 1;
		long lastIdentityInsert = now - 60 * 60 * 1000 - 1;
		long lastKeepAliveInsert = now - 14 * 60 * 1000 + 60 * 1000;
		while(!isInterrupted() && isRunning) {
			now = new Date().getTime();
			if(!mStorage.getCurrentDateString().equals(mStorage.getCurrentUtcDate())) {
				mStorage.setCurrentDateString(mStorage.getCurrentUtcDate());
				mStorage.setAllEditionsToZero(mIdentityFetcher_usk, mMessageFetcher_usk);
				announceEdition = 0;
				lastAnnounceInsert = now - 6 * 60 * 60 * 1000 - 1;
				lastIdentityInsert = now - 60 * 60 * 1000 - 1;
				lastKeepAliveInsert = now - 14 * 60 * 1000 -1;
			}
			mStorage.checkUserActivity();
			if(!mStorage.config.firstStart && now - lastAnnounceInsert > 6 * 60 * 60 * 1000) {
				insertMessage(mStorage.new PlainTextMessage("announce", mStorage.config.requestKey, "", "", 0, 0));
				lastAnnounceInsert = now;
			}
			if(!mStorage.config.firstStart && now - lastIdentityInsert > 60 * 60 * 1000) {
				String message;
				if(mStorage.getMessageEditionHint(mStorage.config.requestKey) < 0) {
					message = "lastmessageindex=0\n";
				} else {
					message = "lastmessageindex=" + mStorage.getMessageEditionHint(mStorage.config.requestKey) + "\n";
				}
				message += "name=" + mStorage.config.nick + "\n";
				message += "rsapublickey=" + mStorage.getRSA(mStorage.config.requestKey) + "\n";
				message += "\n";
				insertMessage(mStorage.new PlainTextMessage("identity", message, "", "", 0, 0));
				lastIdentityInsert = now;
			}
			if(!mStorage.config.firstStart && now - lastKeepAliveInsert > 14 * 60 * 1000) {
				if(mStorage.channelList.size() > 0) {
					SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
					sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
					String message = "channels=";
					String topics = "";
					if(mStorage.config.autojoinChannel) {
						for(Channel chan : mStorage.channelList) {
							message += chan.name + " ";
							if(!chan.topic.equals("")) {
								topics += "topic." + chan.name.replace("=", ":") + "=" + chan.topic + "\n";
							}
						}
					} else {
						Channel chan;
						for(String channelName : mStorage.config.joinedChannels) {
							chan = mStorage.getChannel(channelName);
							message += chan.name + " ";
							if(!chan.topic.equals("")) {
								topics += "topic." + chan.name.replace("=", ":") + "=" + chan.topic + "\n";
							}
						}
					}
					if(message.endsWith(" ")) {
						message = message.substring(0, message.length() - 1);
					}
					message +=  "\n";
					message += "sentdate=" + sdf.format(new Date().getTime()) + "\n";
					message += "type=keepalive\n";
					if(message.length() + topics.length() + 1 < 1025) {
						message += topics;
					} else {
						// TODO: add round robin
						for(String line : topics.split("\n")) {
							if(message.length() + line.length() + 2 < 1025) {
								message += line + "\n";
							}
						}
					}
					message += "\n";
					insertMessage(mStorage.new PlainTextMessage("message", message, "", "", 0, 0));
					lastKeepAliveInsert = now;
				}
			}
			currentJob = mQueue.pollFirst();
			if(currentJob == null) {
				// nothing in queue
				try {
					sleep(100);
				} catch (InterruptedException e) {
					// ignore. FLIRCP.terminate() will set isRunning == false
				}				
			} else {
				//System.err.println("Threadworker has something in his queue.");
				RandomAccessBucket mTmpBucket;
				InsertBlock mTmpInsertBlock;
				OutputStream mTmpOutputStream;
				String insertKey = "";
				// FIXME: abusing ident here.. add variable "type" 
				if(currentJob.ident.equals("announce")) {
					insertKey = "KSK@" + mStorage.getMessageBase() + "|"+ mStorage.getCurrentDateString() + "|Announce|" + announceEdition;
				} else if(currentJob.ident.equals("identity")) {
					insertKey = mStorage.config.insertKey + mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Identity-" + (long) (mStorage.getIdentEdition(mStorage.config.requestKey) + 1);
				} else if(currentJob.ident.equals("message")) {
					insertKey = mStorage.config.insertKey + mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Message-" + (long) (mStorage.getMessageEditionHint(mStorage.config.requestKey) + 1);
				}
				
				// inserting message
				try {
					//System.err.println("[Worker]::run() trying to insert " + currentJob.ident + " into identKeyspace \"" + insertKey + "\"");
					mTmpBucket = mTmpBucketFactory.makeBucket(currentJob.message.getBytes().length);
					mTmpOutputStream = mTmpBucket.getOutputStream();
					mTmpOutputStream.write(currentJob.message.getBytes());
					mTmpOutputStream.close();
					mTmpBucket.setReadOnly();
					mTmpInsertBlock = new InsertBlock(mTmpBucket, null, new FreenetURI(insertKey));
					InsertContext mInsertContext = mInserter.getInsertContext(true);
					mInsertContext.maxInsertRetries = -1;
					//mInserter.insert(mTmpInsertBlock, false, null, false, mInsertContext, this, (short) 1);
					mInserter.insert(mTmpInsertBlock, false, null);
					//System.err.println("[flircp] inserted message: " + messageLink.toString());
					// FIXME: do we need the .free() later again if we fail and never reach the next statement?
					mTmpBucket.free();
					if(currentJob.ident.equals("announce")) {
						announceEdition += 1;
					} else if(currentJob.ident.equals("identity")){
						mStorage.setIdentEdition(mStorage.config.requestKey, mStorage.getIdentEdition(mStorage.config.requestKey) +1);
					} else if(currentJob.ident.equals("message")) {
						mStorage.setMessageEditionHint(mStorage.config.requestKey, mStorage.getMessageEditionHint(mStorage.config.requestKey) +1);
					}
				} catch (MalformedURLException e) {
					System.err.println("[Worker]::run() MalformedURLException while inserting temporary bucket into message keyspace. " + e.getMessage());
				} catch (IOException e) {
					System.err.println("[Worker]::run() IOException while writing message bytes to temporary bucket. tried to insert message. " + e.getMessage());
				} catch (InsertException e) {
					if(e.getMode() == InsertException.COLLISION) {
						if(currentJob.ident.equals("announce")) {
							// TODO: how to get the current announce edition? saved edition from
							// mStorage is not accurate as each announce fetcher does += 1.
							// starting from 0 and save edition to local variable instead.
							announceEdition += 1;
						} else if(currentJob.ident.equals("identity")) {
							// uh? this shouldn't be possible at all. new keys for every start now.
							// after database backend is implemented identity editions should be always accurate too.
							mStorage.setIdentEdition(mStorage.config.requestKey, mStorage.getIdentEdition(mStorage.config.requestKey) + 1);
							System.err.println("[Worker]::run() COLLISION for identity insert. wtf?");
						} else if(currentJob.ident.equals("message")) {
							// uh? this shouldn't be possible at all. new keys for every start now.
							// after database backend is implemented message editions should be always accurate too.
							mStorage.setMessageEditionHint(mStorage.config.requestKey, mStorage.getMessageEditionHint(mStorage.config.requestKey) + 1);
							System.err.println("[Worker]::run() COLLISION for message insert. wtf?");
						}
						// add the job on top of the queue again.
						mQueue.addFirst(currentJob);
					} else if(e.getMode() == InsertException.InsertExceptionMode.REJECTED_OVERLOAD) {
						// just add the job to the queue again.
						mQueue.addFirst(currentJob);
					} else {
						System.err.println("[Worker]::run() InsertException while inserting message. " + e.getMessage() + ". errorNr: " + e.getMode());
					}
				}
//				if(processingTime != Float.parseFloat("0")) {
//					processingTime = (processingTime + new Date().getTime() - now) / 2;
//				} else {
//					processingTime = new Date().getTime() - now;
//				}
				processingTime = new Date().getTime() - now;
			}
		}
	}
	
}
