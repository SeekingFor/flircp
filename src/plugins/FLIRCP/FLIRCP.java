package plugins.FLIRCP;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;

import plugins.FLIRCP.freenetMagic.WebInterface;
import plugins.FLIRCP.storage.RAMstore;
import sun.misc.BASE64Encoder;
import freenet.keys.FreenetURI;
import freenet.l10n.BaseL10n.LANGUAGE;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginL10n;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.PluginRespirator;
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
		///home/user/dev/freenet/plugin-HelloWorld-staging/dist/FLIRCP.jar
		mStorage = new RAMstore();
		if(mStorage.config.insertKey.equals("")){
			FreenetURI keys[] = pr.getHLSimpleClient().generateKeyPair("");
			mStorage.config.insertKey=keys[0].toString();
			mStorage.config.requestKey=keys[1].toString();
			mStorage.addNewUser(mStorage.config.requestKey, true);
			mStorage.setNick(mStorage.config.requestKey, mStorage.config.nick);
		}
		if(mStorage.config.RSAprivateKey.equals("")){
			// TODO: move this to own void
			try {
				// FIXME: replace sun BASE64Encoder with Freenet.Utils.Base64.encodeStandard()
				KeyPairGenerator kpg;
				KeyPair kp;
				BASE64Encoder b64encoder = new BASE64Encoder();
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
				pubKey += b64encoder.encode(pub.getModulus().toByteArray()) + "|";
				pubKey += b64encoder.encode(pub.getPublicExponent().toByteArray());
				pubKey = pubKey.replace("\n", "");
				// private: create base64 encode values of private modulus (same as public modulus?), public exponent, 
				// flip format: length(modulus) + | + base64(modulus) + | + base64(public exponent) + | + base64(private exponent) + | + ?
				String privateKey;
				privateKey = (priv.getModulus().bitLength() / 8) + "|";
				privateKey += b64encoder.encode(priv.getModulus().toByteArray()) + "|";
				privateKey += b64encoder.encode(pub.getPublicExponent().toByteArray()) + "|";
				privateKey += b64encoder.encode(priv.getPrivateExponent().toByteArray()) + "|";
				mStorage.config.RSAprivateKey = privateKey;
				mStorage.config.RSApublicKey = pubKey;
				mStorage.setRSA(mStorage.config.requestKey, pubKey);
			} catch (NoSuchAlgorithmException e) {
				System.err.println("[flircp] can't find RSA keygenerator. " + e.getMessage());
				e.printStackTrace();
			} catch (InvalidKeySpecException e) {
				System.err.println("[flircp] can't find RSA specs. " + e.getMessage());
				e.printStackTrace();
			}
		}
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
	}
	
	public void terminate() {
		mThreadWorker.terminate();
		mFredWebUI.unregister(mToadlet);					// unload toadlet
		mPageMaker.removeNavigationCategory("IRC Chat");	// unload category
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
