package plugins.FLIRCP;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Properties;

import plugins.FLIRCP.freenetMagic.WebInterface;
import plugins.FLIRCP.storage.RAMstore;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.clients.http.PageMaker;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContainer;

public class FLIRCP implements FredPlugin, FredPluginThreadless, FredPluginL10n {
	private ToadletContainer mFredWebUI;
	private PageMaker mPageMaker;
	private Toadlet mToadlet;
	private Worker mThreadWorker;
	private RAMstore mStorage;

	public void runPlugin(PluginRespirator pr) {
		
		StringBuilder errorMessage = new StringBuilder();
		// TODO: read/store joined channels if autojoin == false;
		mStorage = new RAMstore();
		File dir = new File("flircp");
		if(!dir.isDirectory()) {
			dir.mkdir();
		}
		if(readConfiguration(pr, errorMessage)) {
			mThreadWorker = new Worker(pr, mStorage);
			mFredWebUI = pr.getToadletContainer();
			mPageMaker = pr.getPageMaker();
			// create a new toadlet for /FLIRCP/ namespace
			mToadlet = new WebInterface(pr, "/flircp/", mStorage, pr.getNode().clientCore.formPassword, mThreadWorker);
			// use the last parameter called menuOffset to change the display order (0 = left, no parameter = right)
			mPageMaker.addNavigationCategory(mToadlet.path(), "IRC Chat", "IRC Chat for Freenet", this);
			// add first visible navigation item
			if(mStorage.config.AllowFullAccessOnly) {
				mFredWebUI.register(mToadlet, "IRC Chat", mToadlet.path() + "channelWindow" , true, "Public Chat", "public chat", true, null, this);
				mFredWebUI.register(mToadlet, "IRC Chat", mToadlet.path() + "options" , true, "Options", "configure your flircp experience", true, null, this);
				mFredWebUI.register(mToadlet, "IRC Chat", mToadlet.path() + "stats" , true, "Statistics", "show statistics", true, null, this);
				// add another hidden navigation item to catch a click on main navigation category
				mFredWebUI.register(mToadlet, null, mToadlet.path(), true, true);
			} else {
				mFredWebUI.register(mToadlet, "IRC Chat", mToadlet.path() + "channelWindow" , true, "Public Chat", "public chat", false, null, this);
				mFredWebUI.register(mToadlet, "IRC Chat", mToadlet.path() + "options" , true, "Options", "configure your flircp experience", false, null, this);
				mFredWebUI.register(mToadlet, "IRC Chat", mToadlet.path() + "stats" , true, "Statistics", "show statistics", false, null, this);
				// add another hidden navigation item to catch a click on main navigation category
				mFredWebUI.register(mToadlet, null, mToadlet.path(), true, false);
			}
			mThreadWorker.start();
		} else {
			System.err.println("[flircp] can't start flircp because of invalid configuration file. please fix the following problems and reload flircp on the plugin page of your node.");
			for(String error : errorMessage.toString().split("\n")) {
				System.err.println("[flircp] " + error);
			}
			mStorage = null;
			errorMessage = null;
		}
	}
	
	public void terminate() {
		try {
			mThreadWorker.terminate();
			mFredWebUI.unregister(mToadlet);					// unload toadlet
			mPageMaker.removeNavigationCategory("IRC Chat");	// unload category
		} catch (NullPointerException e) {
			// ignore. flircp was not started successful.
		}
	}

	private boolean readConfiguration(PluginRespirator pr, StringBuilder errorMessage) {
		Properties configProps = new Properties();
		FileInputStream in;
		FileOutputStream out;
		boolean success = true;
		try {
			in = new FileInputStream("flircp/config");
			configProps.load(in);
			in.close();
		} catch (FileNotFoundException e) {
			// configuration file does not yet exist or can't be opened for reading
			System.out.println("[flircp] can't load configuration file freenet_directory/flircp/config. assuming first start.");
		} catch (IOException e) {
			// file can't be read
			success = false;
			errorMessage.append("can't read configuration file freenet_directory/flircp/config. please check your file permissions. " + e.getMessage() + "\n");
		} catch (IllegalArgumentException e) {
			// configuration file contains at least one invalid property
			success = false;
			errorMessage.append("at least one configuration property found in your configuration file is invalid. please do not manually modify the configuration file. " + e.getMessage() + "\n");
		}
		if(success) {
			if((mStorage.config.requestKey = configProps.getProperty("freenet.key.request", "")).equals("")
			|| (mStorage.config.insertKey = configProps.getProperty("freenet.key.insert", "")).equals("")) {
				// either insert or request key is missing. produce a new key pair. 
				FreenetURI keys[] = pr.getHLSimpleClient().generateKeyPair("");
				mStorage.config.insertKey=keys[0].toString();
				mStorage.config.requestKey=keys[1].toString();
				configProps.setProperty("freenet.key.insert", mStorage.config.insertKey);
				configProps.setProperty("freenet.key.request", mStorage.config.requestKey);
	
			}
			mStorage.config.nick = configProps.getProperty("nick", "flircp_testuser");
			mStorage.addNewUser(mStorage.config.requestKey, true);
			mStorage.setNick(mStorage.config.requestKey, mStorage.config.nick);
			if((mStorage.config.RSApublicKey = configProps.getProperty("rsa.public", "")).equals("")
			|| (mStorage.config.RSAprivateKey = configProps.getProperty("rsa.private", "")).equals("")) {
				if(generateRSAkeyPair()) {
					configProps.setProperty("rsa.private", mStorage.config.RSAprivateKey);
					configProps.setProperty("rsa.public", mStorage.config.RSApublicKey);
				} else {
					success = false;
					errorMessage.append("failed to generate RSA keypair.\n");
				}
			}
			try {
				mStorage.config.iFrameHeight = Integer.parseInt(configProps.getProperty("ui.web.iframe.height"));
			} catch (NumberFormatException e) {
				// ignore and keep default value of mStorage.config
			}
			try {
				mStorage.config.iFrameRefreshInverval = Integer.parseInt(configProps.getProperty("ui.web.iframe.refreshinterval"));
			} catch (NumberFormatException e) {
				// ignore and keep default value of mStorage.config
			}
			try {
				mStorage.config.iFrameFontSize = Integer.parseInt(configProps.getProperty("ui.web.chat.fontsize"));
			} catch (NumberFormatException e) {
				// ignore and keep default value of mStorage.config
			}
			try {
				mStorage.config.userlistWidth = Integer.parseInt(configProps.getProperty("ui.web.chat.userlist.width"));
			} catch (NumberFormatException e) {
				// ignore and keep default value of mStorage.config
			}
			mStorage.config.timeZone = configProps.getProperty("ui.web.chat.timezone", "UTC");
			mStorage.config.showJoinsParts = Boolean.parseBoolean(configProps.getProperty("ui.web.chat.showJoinPart", "false"));
			mStorage.config.encapsulateNicks = Boolean.parseBoolean(configProps.getProperty("ui.web.chat.nickStyleIRC", "true"));
			mStorage.config.firstStart = Boolean.parseBoolean(configProps.getProperty("firstStart", "true"));
			if(mStorage.config.encapsulateNicks) {
				mStorage.config.useDelimeterForNicks = false;
			} else {
				mStorage.config.useDelimeterForNicks = true;
			}
			mStorage.config.autojoinChannel = Boolean.parseBoolean(configProps.getProperty("irc.channels.autojoin", "true"));
			try {
				out = new FileOutputStream("flircp/config", false);
				configProps.store(out, "configuration created by flircp " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_debug);
				out.close();
			} catch (FileNotFoundException e) {
				// out stream can't create file
				success = false;
				errorMessage.append("can't create configuration file freenet_directory/flircp/config. please check your file permissions. " + e.getMessage() + "\n");
			} catch (IOException e) {
				// configProps can't write to file
				success = false;
				errorMessage.append("can't write configuration to freenet_directory/flircp/config. please check your file permissions. " + e.getMessage() + "\n");
			} catch(ClassCastException e) {
				// configProps has at least one invalid property
				success = false;
				errorMessage.append("at least one configuration property is invalid. please do not manually modify the configuration file. " + e.getMessage() + "\n");
			}
		}
		return success;
	}
	private boolean generateRSAkeyPair() {
		try {
			// FIXME: replace sun BASE64Encoder with Freenet.Utils.Base64.encodeStandard()
			KeyPairGenerator kpg;
			KeyPair kp;
			
			//BASE64Encoder b64encoder = new BASE64Encoder();
			kpg = KeyPairGenerator.getInstance("RSA");
			// 128 byte key
			kpg.initialize(1024);
			kp = kpg.genKeyPair();
			KeyFactory fact = KeyFactory.getInstance("RSA");
			RSAPublicKeySpec pub = fact.getKeySpec(kp.getPublic(), RSAPublicKeySpec.class);
			RSAPrivateKeySpec priv = fact.getKeySpec(kp.getPrivate(), RSAPrivateKeySpec.class);
			// pubkey: create base64 encoded values of public modulus and public exponent
			// flip format: length(modulus) + | + base64(modulus) + | + base64(exponent)
			String pubKey;
			pubKey = (pub.getModulus().bitLength() / 8) + "|";
			pubKey += Base64.encodeStandard(pub.getModulus().toByteArray()) + "|";
			pubKey += Base64.encodeStandard(pub.getPublicExponent().toByteArray());
			pubKey = pubKey.replace("\n", "");
			// private: create base64 encode values of private modulus (same as public modulus?), public exponent, 
			// flip format: length(modulus) + | + base64(modulus) + | + base64(public exponent) + | + base64(private exponent) + | + ?
			String privateKey;
			privateKey = (priv.getModulus().bitLength() / 8) + "|";
			privateKey += Base64.encodeStandard(priv.getModulus().toByteArray()) + "|";
			privateKey += Base64.encodeStandard(pub.getPublicExponent().toByteArray()) + "|";
			privateKey += Base64.encodeStandard(priv.getPrivateExponent().toByteArray()) + "|";
			// privateKey += ?
			mStorage.config.RSAprivateKey = privateKey;
			mStorage.config.RSApublicKey = pubKey;
			mStorage.setRSA(mStorage.config.requestKey, pubKey);
			return true;
		} catch (NoSuchAlgorithmException e) {
			System.err.println("[flircp] can't find RSA keygenerator. " + e.getMessage());
			e.printStackTrace();
			return false;
		} catch (InvalidKeySpecException e) {
			System.err.println("[flircp] can't find RSA specs. " + e.getMessage());
			e.printStackTrace();
			return false;
		}
		
	}
	
	@Override
	public String getString(String key) {
		return key;
	}

	@Override
	public void setLanguage(LANGUAGE newLanguage) {
		// TODO Auto-generated method stub
		
	}
}
