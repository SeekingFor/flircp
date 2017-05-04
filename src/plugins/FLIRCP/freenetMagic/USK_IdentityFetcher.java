package plugins.FLIRCP.freenetMagic;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

import plugins.FLIRCP.storage.RAMstore;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.HighLevelSimpleClient;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.async.USKCallback;
import freenet.client.async.USKManager;
import freenet.keys.FreenetURI;
import freenet.keys.USK;
import freenet.node.RequestClient;
import freenet.node.RequestClientBuilder;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.io.ResumeFailedException;

public class USK_IdentityFetcher implements USKCallback, ClientGetCallback, RequestClient {
	private final RAMstore mStorage;
	private FreenetMessageParser mFreenetMessageParser;
	private final USKManager mUSKmanager;
	private List<USK> mSubscriptions;
	public int concurrentFetchCount;
	private final HighLevelSimpleClient mRequestClient;

	public USK_IdentityFetcher(PluginRespirator pr, RAMstore Storage) {
		mStorage = Storage;
		mUSKmanager = pr.getNode().clientCore.uskManager;
		mSubscriptions = new ArrayList<>();
		concurrentFetchCount = 0;
		mRequestClient = pr.getNode().clientCore.makeClient((short) 1, false, true);
	}
	public void setFreenetMessageParser(FreenetMessageParser freenetMessageParser) {
		this.mFreenetMessageParser = freenetMessageParser;
	}
	public void removeAllFetchers() {
		for(USK usk : mSubscriptions) {
			mUSKmanager.unsubscribe(usk, this);
		}
	}
	public void removeSingleFetcher(String ident) {
		String curIdent;
		for(USK usk : mSubscriptions) {
			curIdent = usk.getURI().toString().split("/",2)[0].replace("USK@", "SSK@") + "/"; 
			if(ident.equals(curIdent)) {
				mUSKmanager.unsubscribe(usk, this);
				mSubscriptions.remove(usk);
				break;
			}
		}
	}
	public int getSubscriptionCount() {
		return mSubscriptions.size();
	}
	
	public void addInitialSubscription(String identRequestKey) {
		FreenetURI identRequestURI;
		try {
			if(!identRequestKey.equals(mStorage.config.requestKey)) {
				identRequestURI = new FreenetURI(identRequestKey).setKeyType("USK").setDocName(mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Identity");
				identRequestURI = identRequestURI.setSuggestedEdition(0).setMetaString(null);
				addRequest(identRequestURI);
			}
		} catch (MalformedURLException e) {
			System.err.println("[USK_IdentityFetcher]::addInitialSubscription identRequestKey = " + identRequestKey);
			System.err.println("[USK_IdentityFetcher]::addInitialSubscription MalformedURLException. " + e.getMessage());
			e.printStackTrace();
		}
	}
	
	private void addSubscription(FreenetURI identRequestURI) {
		USK usk = null;
		try {
			String ident = identRequestURI.toString().split("/",2)[0].replace("USK@", "SSK@") + "/"; 
			if(!ident.equals(mStorage.config.requestKey)) {
				usk = USK.create(identRequestURI);
				USK uskIdent = USK.create(identRequestURI);
				if(!mSubscriptions.contains(uskIdent)) {
					mStorage.userMap.get(ident).identSubscriptionActive = true;
					mUSKmanager.subscribe(usk, this, true, true, this);
					mSubscriptions.add(uskIdent);
				}
			}
		} catch (MalformedURLException e) {
			if (usk != null) {
				System.err.println("[USK_IdentityFetcher]::addSubscription usk = " + usk.getURI().toString());
			} else {
				System.err.println("[USK_IdentityFetcher]::addSubscription usk = null. " + e.getMessage());
			}
			e.printStackTrace();
		}

	}
	
	public void resetSubcriptions() {
		List<USK> newSubscriptionList = new ArrayList<>();
		try {
			for(USK usk : mSubscriptions) {
				mUSKmanager.unsubscribe(usk, this);
				try {
					usk = USK.create(usk.getURI().setDocName(mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Identity").setSuggestedEdition(0));
					mUSKmanager.subscribe(usk, this, true, true, this);
					newSubscriptionList.add(usk);
				} catch (MalformedURLException e) {
					System.err.println("[USK_IdentityFetcher]::resetSubscriptions() MalformedURLException: " + e.getMessage());
				}
			}
			mSubscriptions = newSubscriptionList;
		} catch (ConcurrentModificationException e) {
			// subscription was deleted or added during loop
			resetSubcriptions();
		}
	}
	
	@Override
	public short getPollingPriorityNormal() {
		// USK_cb
		return (short) 1;
	}

	@Override
	public short getPollingPriorityProgress() {
		// USK_cb
		return (short) 1;
	}

	@Override
	public boolean persistent() {
		// USK_cb
		return false;
	}

	@Override
	public boolean realTimeFlag() {
		// RequestClient
		return true;
	}

	@Override
	public void onFoundEdition(long l, USK key,
			ClientContext context, boolean metadata, short codec, byte[] data,
			boolean newKnownGood, boolean newSlotToo) {
		// usk_cb
		//System.err.println("[USK_IdentityFetcher] found edition " + l + " codec=" + codec + " newKnownGood=" + newKnownGood + " newSlotToo=" + newSlotToo + " data: somedata" + " USK=" + key.getURI().toASCIIString());
		// FIXME: replace USK => SSK; last /x => -; add edition l
		// FIXME: use USK and catch redirect
		String ident = key.getURI().toString().split("/")[0].replace("USK@", "SSK@") + "/";
		if(!ident.equals(mStorage.config.requestKey)) {
			if(l > mStorage.getIdentEdition(ident)) {
				mStorage.setIdentEdition(ident, l);
				addRequest(key.getURI().setSuggestedEdition(l));
			}
		}
	}

	@Override
	public void onSuccess(FetchResult result, ClientGetter state) {
		concurrentFetchCount -= 1;
		try {
			mFreenetMessageParser.addMessage("identity", state.getURI().toString().split("/")[0].replace("USK","SSK") + "/", new String(result.asByteArray()).trim(), state.getURI().toString());
			// addSubscription is called after successful requesting the
			// initital identity message e.g. no redirect to newer versions found
			// FIXME: use local mSubscriptions instead?
			String ident = state.getURI().toString().split("/", 2)[0].replace("USK@", "SSK@") + "/"; 
			if(!ident.equals(mStorage.config.requestKey)) {
				if(!mStorage.userMap.get(ident).identSubscriptionActive) {
					addSubscription(state.getURI());
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// ClientGetCallback
		
	}
	
	private void checkRequestRestart(FreenetURI uri, String reason) {
		// non fatal errors. we can simply restart the request or ignore this edition.
		if(uri.getEdition() == 0) {
			// initial USK request, no subscription activated yet.
			// simply restart the request
			//System.err.println("[USK_IdentityFetcher] " + reason + ". initial request. restarting request. " + uri.toString());
			restartRequest(uri);
		} else {
			// backed up by subscription. we just wait for the next edition.
			//System.err.println("[USK_IdentityFetcher] " + reason + ". waiting for next edition from subscription. " + uri.toString());
		}
	}
	
	private void checkInvalid(FreenetURI uri, String reason) {
		checkInvalid(uri, reason, "");
	}
	
	private void checkInvalid(FreenetURI uri, String reason, String message) {
		// fatal errors. can't fetch this request. subscribing to edition 0 or ignoring this edition.
		// FIXME: better stop fetching this identity?
		String ident = uri.toString().split("/", 2)[0].replace("USK@", "SSK@") + "/";
		if(!ident.equals(mStorage.config.requestKey)) {
			if(!mStorage.userMap.get(ident).identSubscriptionActive) {
				// initial USK request, no subscription activated yet.
				addSubscription(uri);
				System.err.println("[USK_IdentityFetcher] " + reason + ". initial request. subscribing to edition " + uri.getEdition() + " anyway. " + message);
			} else {
				// backed up by subscription. we just wait for the next edition.
				System.err.println("[USK_IdentityFetcher] " + reason + ". waiting for next edition from subscription. " + uri.toString() + " " + message);
			}
		}
	}

	@Override
	public void onFailure(FetchException e, ClientGetter state) {
		// ClientGetCallback
		concurrentFetchCount -= 1;
		String ident = state.getURI().toString().split("/")[0].replace("USK", "SSK") + "/";
		switch (e.getMode()) {
		case RECENTLY_FAILED:
			checkRequestRestart(state.getURI(), "RECENTLY_FAILED");
			break;
		case DATA_NOT_FOUND:
			checkRequestRestart(state.getURI(), "DATA_NOT_FOUND");
			break;
		case ALL_DATA_NOT_FOUND:
			// should not possible while fetching SSKs without following redirects.
			System.err.println("[USK_IdentityFetcher] ALL_DATA_NOT_FOUND. you should not see me. " + state.getURI().toString() + " " + e.getMessage());
			break;
		case ROUTE_NOT_FOUND:
			// if hit we are trying to fetch something but the node does not have a proper connection.
			checkRequestRestart(state.getURI(), "ROUTE_NOT_FOUND");
			break;
		case REJECTED_OVERLOAD:
			checkRequestRestart(state.getURI(), "REJECTED_OVERLOAD");
			break;
		case INVALID_METADATA:
			// wtf?
			mStorage.identity_ddos +=1;
			mStorage.userMap.get(ident).identity_ddos += 1;
			checkInvalid(state.getURI(), "INVALID_METADATA", e.getMessage());
			break;
		case TOO_BIG_METADATA:
			// wtf?
			mStorage.identity_ddos +=1;
			mStorage.userMap.get(ident).identity_ddos += 1;
			checkInvalid(state.getURI(), "TOO_BIG_METADATA", e.getMessage());
			break;
		case TOO_BIG:
			// should not be possible while polling SSK's without following redirects.
			mStorage.identity_ddos +=1;
			mStorage.userMap.get(ident).identity_ddos += 1;
			checkInvalid(state.getURI(), "TOO_BIG", e.getMessage());
			break;
		case TOO_MANY_REDIRECTS:
			checkInvalid(state.getURI(), "TOO_MANY_REDIRECTS", e.getMessage());
			break;
		case TOO_MUCH_RECURSION:
			checkInvalid(state.getURI(), "TOO_MUCH_RECURSION");
			break;
		case PERMANENT_REDIRECT:
			if(e.newURI.toString().startsWith(state.getURI().toString().split("/")[0])) {
				long newIdentEdition = e.newURI.getEdition();
				if(newIdentEdition > mStorage.getIdentEdition(ident)) {
					mStorage.setIdentEdition(ident, newIdentEdition);
					addRequest(e.newURI);
					//System.err.println("[USK_IdentityFetcher] got new edition. old edition was " + state.getURI().getEdition() + " new edition is " + newIdentEdition);
				}
			} else {
				mStorage.identity_ddos +=1;
				mStorage.userMap.get(ident).identity_ddos += 1;
				System.err.println("[USK_IdentityFetcher] got illegal redirection. old URI: " + state.getURI().toString() + " new URI: " + e.newURI.toString());
			}
			break;
		default:
			// now we have a serious problem.
			mStorage.identity_ddos +=1;
			mStorage.userMap.get(ident).identity_ddos += 1;
			System.err.println("[USK_IdentityFetcher]::onFailure::default::else: " + e.getMessage() + " mode=" + e.getMode() + " uri=" + state.getURI().toString());
			break;
		}
	}
	
	private void restartRequest(final FreenetURI uri) {
		Thread delayed = new Thread() {
			@Override
			public void run() {
				try {
					sleep(1000);
					addRequest(uri);
				} catch (InterruptedException e) {
					
				}
			}
		};
		delayed.start();
	}
	private void addRequest(FreenetURI uri) {
		FetchContext mFetchContext = mRequestClient.getFetchContext();
		mFetchContext.allowSplitfiles = true;		// FIXME: change to false if this is fixed
		mFetchContext.canWriteClientCache = true;
		mFetchContext.dontEnterImplicitArchives = true; //?
		mFetchContext.filterData = false; //?
		mFetchContext.followRedirects = false;
		mFetchContext.ignoreStore = false;
		//final? mFetchContext.ignoreTooManyPathComponents = false;
		mFetchContext.ignoreUSKDatehints = true; // ?
		mFetchContext.localRequestOnly = false;
		mFetchContext.maxArchiveLevels = 0; //?
		mFetchContext.maxArchiveRestarts = 0; //?
		mFetchContext.maxCheckBlocksPerSegment = 3; //?
		mFetchContext.maxDataBlocksPerSegment = 3; //?
		//mFetchContext.maxMetadataSize = ?
		// cooldown for 30 minutes, wtf? this is a real time chat plugin.
		//mFetchContext.maxNonSplitfileRetries = -1;
		mFetchContext.maxNonSplitfileRetries = 2;
		//mFetchContext.maxOutputLength = 1024 ?
		mFetchContext.maxRecursionLevel = 1; //?
		mFetchContext.maxSplitfileBlockRetries = 0;
		//mFetchContext.maxTempLength = ?
		//final? mFetchContext.maxUSKRetries = -1; //?
		//mFetchContext.overrideMIME = "text/plain";?
		//mFetchContext.prefetchHook = ?
		//mFetchContext.returnZIPManifests = true ?
		//mFetchContext.tagReplacer = ?
		//mFetchContext.setCooldownRetries(cooldownRetries);
		//mFetchContext.setCooldownTime(cooldownTime);
		try {
			String ident = uri.toString().split("/", 2)[0].replace("USK@", "SSK@") + "/"; 
			if(!ident.equals(mStorage.config.requestKey)) {
				mRequestClient.fetch(uri, this, mFetchContext, (short) 2);
				concurrentFetchCount += 1;
			}
		} catch (FetchException e) {
			System.err.println("[USK_IdentityFetcher]::addRequest() FetchException " + e.getMessage());
		}
	}
        
    @Override
    public void onResume(ClientContext cc) throws ResumeFailedException {
    }

    @Override
    public RequestClient getRequestClient() {
        return this;
    }
}
