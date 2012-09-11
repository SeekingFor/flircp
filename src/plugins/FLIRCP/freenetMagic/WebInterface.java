package plugins.FLIRCP.freenetMagic;

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;

import javax.imageio.ImageIO;
import plugins.FLIRCP.Worker;
import plugins.FLIRCP.freenetMagic.identicon.Identicon;
import plugins.FLIRCP.storage.RAMstore;
import plugins.FLIRCP.storage.RAMstore.PlainTextMessage;
import plugins.FLIRCP.storage.RAMstore.channel;
import plugins.FLIRCP.storage.RAMstore.users;
import freenet.clients.http.PageNode;
import freenet.clients.http.RedirectException;
import freenet.clients.http.Toadlet;
import freenet.clients.http.ToadletContext;
import freenet.clients.http.ToadletContextClosedException;
import freenet.keys.FreenetURI;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.Base64;
import freenet.support.HTMLNode;
import freenet.support.IllegalBase64Exception;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.io.BucketTools;
import freenet.support.io.Closer;

public class WebInterface extends Toadlet {
	private String mNameSpace;
	private RAMstore mStorage;
	private String mFormPassword;
	private Worker mPtrWorker;
	private BucketFactory mPtrTmpBucketFactory;
	
	public WebInterface(PluginRespirator pr, String path, RAMstore Storage, String formPassword, Worker ptrWorker) {
		super(pr.getHLSimpleClient());
		mNameSpace = path;
		mStorage = Storage;
		mFormPassword = formPassword;
		mPtrWorker = ptrWorker;
		mPtrTmpBucketFactory = pr.getNode().clientCore.tempBucketFactory;
	}
	
	@Override
	public String path() {
		return mNameSpace;
	}
	
	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// This method is called whenever a user requests a page from our mNameSpace
		handleWebRequest(uri, req, ctx);
	}
	public void handleMethodPOST(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// This method is called whenever a user requests a page from our mNameSpace
		
		// POST form authentication
		//FIXME link the core
		//FIXME validate referer
		//FIXME validate session
		//String passwordPlain = req.getPartAsString("formPassword", 32);
		//if((passwordPlain.length() == 0) || !passwordPlain.equals(core.formPassword)) {
		//	writeHTMLReply(ctx, 403, "Forbidden", "Invalid form password.");
		//	return;
		//}
		handleWebRequest(uri, req, ctx);
	}
	private void handleWebRequest(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// We check the requested URI against a whitelist. Any request not found here will result in a info page.
		String requestedPath = uri.toString().replace(mNameSpace, "");
		PageNode mPageNode;
		if(requestedPath.equals("")) {
			writeHTMLReply(ctx, 200, "OK", createRoot(req, ctx).outer.generate());
		} else if(requestedPath.startsWith("channelWindow")) {
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				mPageNode = ctx.getPageMaker().getPageNode("flircp #test", true, ctx);
				mPageNode = createChannelWindow(mPageNode, "#test");
			}
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.equals("options")) {
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				mPageNode = ctx.getPageMaker().getPageNode("flircp configuration", true, ctx);
				mPageNode = createConfig(mPageNode);
			}
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.equals("stats")) {
			writeHTMLReply(ctx, 200, "OK", createStats(req, ctx).outer.generate());
		} else if(requestedPath.startsWith("getIdenticon")) {
				byte[] routingKey;
				try {
					routingKey = Base64.decode(req.getParam("ident"));
					RenderedImage identiconImage =  new Identicon(routingKey).render(120, 120);
					ByteArrayOutputStream imageOutputStream = new ByteArrayOutputStream();
					ImageIO.write(identiconImage, "png", imageOutputStream);
					Bucket imageBucket = BucketTools.makeImmutableBucket(mPtrTmpBucketFactory, imageOutputStream.toByteArray());
					writeReply(ctx, 200, "image/png", "OK", imageBucket);
					imageBucket.free();
					Closer.close(imageOutputStream);
				} catch (IllegalBase64Exception e) {
					writeReply(ctx, 204, "text/plain", "not found", "not found");
				}
		} else if(requestedPath.equals("receiver")) {
			writeHTMLReply(ctx, 200, "OK", handleReceivedInput(req,ctx).outer.generate());
		} else if(requestedPath.startsWith("changeToChannel?channel=")) {
			String channel = requestedPath.split("=")[1];
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				mPageNode = ctx.getPageMaker().getPageNode("flircp #" + channel, true, ctx);
				mPageNode = createChannelWindow(mPageNode, "#" + channel);
			}
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.startsWith("show?channel=")) {
			writeHTMLReply(ctx, 200, "OK", createIframeContent("#"+requestedPath.split("=")[1]));
		} else if(requestedPath.startsWith("profile?ident=")) {
			mPageNode = ctx.getPageMaker().getPageNode("flircp profile", true, ctx);
			mPageNode = createProfilePage(mPageNode, requestedPath.split("=")[1]);
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.startsWith("sendMessage?channel=")) {
			String channel = "#" + requestedPath.split("=")[1];
			String message = req.getPartAsStringFailsafe("messageinput", 512);
			handleWriteMessage(channel, message);
			mPageNode = ctx.getPageMaker().getPageNode("flircp " + channel, true, ctx);
			mPageNode = createChannelWindow(mPageNode, channel);
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else {
			writeHTMLReply(ctx, 200, "OK", createRequestInfo(req, ctx).outer.generate());
		}
	}
	private String basicHTMLencode(String input) {
		//< → &lt;
		//> → &gt;
		//' → &#39;
		//" → &quot;
		//& → &amp;
		return input.replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;").replace("&", "&amp;");
	}
	private String addSpaces(String input) {
		// FIXME: add config option for max nick length. RFC?
		return addSpaces(input, 25);
	}
	private String addSpaces(String input, int desiredLenth) {
		for(int i = 0; i < desiredLenth - input.length(); i ++) {
			input = input + " ";
		}
		return input;
	}
	
	private HTMLNode parseWelcomeCreateA(String tag) {
		HTMLNode aNode = new HTMLNode("a", tag);
		if(tag.equals("FLIP")) {
			aNode.addAttribute("href", "../USK@pGQPA-9PcFiE3A2tCuCjacK165UaX07AQYw98iDQrNA,8gwQ67ytBNR03hNj7JU~ceeew22HVq6G50dcEeMcgks,AQACAAE/flip/7/");
		} else if(tag.equals("Freenet Social Network Guide for FLIP")) {
			aNode.addAttribute("href", "../USK@t5zaONbYd5DvGNNSokVnDCdrIEytn9U5SSD~pYF0RTE,guWyS9aCMcywU5PFBrKsMiXs7LzwKfQlGSRi17fpffc,AQACAAE/fsng/32/flip.html");
		} else {
			aNode.addAttribute("href", "not found");
		}
		aNode.addAttribute("target", "_blank");
		return aNode;
	}
	private HTMLNode parseWelcomeMessage(String message) {
		// TODO: use freenets html parser instead?
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		String buffer;
		for(String line : message.split("\n")) {
			buffer = "";
			for(String word : line.split(" ")) {
				if(!buffer.equals("") && (word.startsWith("[a]") || word.startsWith("[b]"))) {
					ownContentNode.addChild(new HTMLNode("span", buffer));
					buffer = "";
				}
				if(word.startsWith("[a]") && word.contains("[/a]")) {
					buffer = word.replace("[a]", "").replace("[/a]", "");
					ownContentNode.addChild(parseWelcomeCreateA(buffer));
					buffer = " ";
				} else if(word.startsWith("[a]") && !word.contains("[/a]")) {
					buffer = word.replace("[a]", "") + " ";
				} else if(word.contains("[/a]")) {
					buffer += word.replace("[/a]", "");
					ownContentNode.addChild(parseWelcomeCreateA(buffer));
					buffer = " ";
				} else if(word.startsWith("[b]") && word.contains("[/b]")) {
					ownContentNode.addChild(new HTMLNode("b", word.replace("[b]", "").replace("[/b]", "")));
					buffer = " ";
				} else if(word.startsWith("[b]") && !word.contains("[/b]")) {
					buffer = word.replace("[b]", "") + " ";
				} else if(word.contains("[/b]")) {
					buffer += word.replace("[/b]", "");
					ownContentNode.addChild(new HTMLNode("b", buffer));
					buffer = " ";
				} else {
					buffer += word + " ";
				}
			}
			if(!buffer.equals(" ")) {
				ownContentNode.addChild(new HTMLNode("span", buffer));
			}
			ownContentNode.addChild(new HTMLNode("br"));
		}
		return ownContentNode;
	}
	
	private void handleWriteMessage(String channel, String message) {
		// FIXME: check form password and max size! first dev release you know..
		// FIXME: max size is currently "checked" by using only the first 512 bytes from POST input.
		// FIXME: allow more than that, split it up and loop until everything is put into insert queue.
		// TODO: if insertmessage returns false show error in web ui
		//channel=#test
		//sentdate=2012-08-25 12:42:58
		//type=channelmessage
		//
		//ping
		// FIXME: use substring instead of replace
		if(message.toLowerCase().startsWith("/whois ")) {
			message = message.replace("/whois ", "").replace("/WHOIS ", "");
			mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, "\n" + mStorage.getWhoIs(message), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
		} else if(message.toLowerCase().startsWith("/topic ")) {
			message = message.replace("/topic ", "").replace("/TOPIC ", "");
		} else if(message.toLowerCase().startsWith("/help")) {
			message = message.replace("/help", "").replace("/HELP", "");
			if(message.startsWith(" ")) {
				message = message.substring(1, message.length());
			}
			if(message.equals("")) {
				//message = "flircp commands:\n- /me [message]\twrites messages as third person\n- /whois [nick]\tshows public key, connected channels and time since last message\n- /topic [newTopic]\tchanges the topic\n- /help [command]\tshows help for [command] or this text if no command given- /version\tshows version of flircp";
				message = "\n";
				message += "flircp commands:\n";
				message += "- /me [message]\t\twrites messages as third person\n";
				message += "- /whois [nick]\t\tshows public key, connected channels and time since last message\n";
				//message += "- /topic [newTopic]\tchanges the topic to newTopic\n";
				message += "- /version\t\tshows version of flircp";
				mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, message, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			}
		} else if(message.toLowerCase().startsWith("/version")) {
			message = "\n[version] current flircp version: " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_debug;
			mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, message, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
		} else if(message.length() > 0) {
			if(message.toLowerCase().startsWith("/me")) {
				message = (char) 1 + "ACTION" + message.substring(3, message.length()) + (char) 1;
			}
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
			String out = "channel=" + channel + "\n";
			out += "sentdate=" + sdf.format(new Date().getTime()) + "\n";
			out += "type=channelmessage\n\n";
			out += message;
			mPtrWorker.insertMessage(mStorage.new PlainTextMessage("message", out, "", channel, 0, 0));
			if(!message.startsWith((char) 1 + "ACTION")) {
				mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, message, mStorage.config.nick, channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			} else {
				mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, mStorage.config.nick + message.replace((char) 1 + "ACTION", "").replace("" + (char) 1, ""), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			}
			//mStorage.getChannel(channel).lastMessageIndex += 1;
			mStorage.updateLastMessageActivityForChannel(channel);
		}
	}
	private String createIframeContent(String channel) {
		String iFrameHTML = "<html>\n";
		iFrameHTML += "<head>\n";
		iFrameHTML += "<meta http-equiv='refresh' content='" + mStorage.config.iFrameRefreshInverval + "'>\n";
		iFrameHTML += "</head>\n";
		iFrameHTML += "<body>\n";
		if(mStorage.getChannel(channel) != null) {
			iFrameHTML += "<form action='setValueFromIFrame' method='POST'>\n";
			iFrameHTML += "<table border='0' cellspacing='0' cellpadding='0' height='100%' width='100%'>\n";
			iFrameHTML += "<tr>\n";
			iFrameHTML += "<td valign='top'>\n";
			//iFrameHTML += "<input type='text' name='topic' size='" + mStorage.config.topiclineWidth + "' value='" + mStorage.getChannel(channel).topic.replace("'", "´").replace("\"","´") + "' />\n";
			//iFrameHTML += "<textarea cols='" + mStorage.config.textareaWidth + "' rows='" + mStorage.config.textareaHeight + "' readonly='readonly'>\n";
			iFrameHTML += "<input type='text' name='topic' value='" + mStorage.getChannel(channel).topic.replace("'", "´").replace("\"","´") + "' style='width: 100%;' />\n";
			iFrameHTML += "<textarea style='height: 100%; width: 100%;' readonly='readonly'>\n";
			int messageIndex = 0;
			SimpleDateFormat sdf = new SimpleDateFormat("z HH:mm:ss");
			sdf.setTimeZone(TimeZone.getTimeZone(mStorage.config.timeZone));
			// ugly but nesessary as we need a copy of the data not a reference. reason: concurrent modification exceptions.
			for(PlainTextMessage message : mStorage.getChannel(channel).messages.toArray(new PlainTextMessage [mStorage.getChannel(channel).messages.size()])) {
				// FIXME: do better security checks to permit breaking out of the textarea
				if(!message.nickname.equals("*")) {
					iFrameHTML +=  sdf.format(message.timeStamp) + "\t" + addSpaces("<" + message.nickname.replace("/textarea","") + ">") + "\t" + message.message.replace("/textarea","") + "\n";
				} else {
					iFrameHTML += sdf.format(message.timeStamp) + "\t" + message.nickname + " " + message.message.replace("/textarea","") + "\n";
				}
				messageIndex = message.index;
			}
			mStorage.getChannel(channel).lastShowedIndex = messageIndex;
			iFrameHTML += "</textarea>";
			iFrameHTML += "</td>\n";
			iFrameHTML += "<td valign='top' width='" + (mStorage.config.userlistWidth + 2) + "px'>\n";
			iFrameHTML += "<input type='submit' name='whois' value='whois'>\n";
			iFrameHTML += "<input type='submit' name='pm' value='PM'>\n";
			iFrameHTML += "<br />\n";
			iFrameHTML += "<input type='submit' name='operator' value='Operator'>\n";
			iFrameHTML += "<input type='submit' name='voice' value='Voice'>\n";
			iFrameHTML += "<br />\n";
			//iFrameHTML += "<select multiple='multiple' style='width: " + mStorage.config.userlistWidth + "px; height: " + mStorage.config.userlistHeight + "px;'>\n";
			iFrameHTML += "<select multiple='multiple' style='width: 100%; height: 100%;'>\n";
			// TODO: add config option for first empty line 
			if(1 == 2) { iFrameHTML += "<option> </option>\n"; }
			// returns a new list => no concurrent modifications possible
			for(String user : mStorage.getUsersInChannel(channel)) {
				iFrameHTML += "<option>" + user.replace("/option","") + "</option>\n";
			}
			iFrameHTML += "</select>\n";
			iFrameHTML += "</td>\n";
			iFrameHTML += "</tr>\n";
			iFrameHTML += "</table>\n";
			iFrameHTML += "</form>\n";
		} else {
			iFrameHTML += "channel " + channel + " not found.";
		}
		iFrameHTML += "</body>\n";
		iFrameHTML += "</html>\n";
		return iFrameHTML;
	}
	
	private PageNode createProfilePage(PageNode mPageNode, String ident) {
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("id", "flircp");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		ownContentNode.addChild("br");
		if(mStorage.knownIdents.contains(ident)) {
			String routingKey = ident.split(",")[0].replace("SSK@", "");
			HTMLNode image = new HTMLNode("img");
			image.addAttribute("src", "getIdenticon?ident=" + routingKey);
			ownContentNode.addChild("b", mStorage.getNick(ident));
			ownContentNode.addChild("br");
			ownContentNode.addChild(image);
			mPageNode.content.addChild(ownContentNode);
		} else {
			ownContentNode.addChild("b", "user not found");
			mPageNode.content.addChild(ownContentNode);
		}
		return mPageNode;
	}
	
	private PageNode handleReceivedInput(HTTPRequest req, ToadletContext ctx) {
		// TODO: add functions for parsing
		PageNode mPageNode = ctx.getPageMaker().getPageNode("flircp configuration", ctx);
//		if(!req.getPartAsStringFailsafe("formPassword",255).equals(mFormPassword)) {
//			mPageNode.content.addChild(new HTMLNode("span", "formPassword does not match. should be " + mFormPassword + " and is " + req.getPartAsStringFailsafe("formPassword",255) + "."));
//			return mPageNode;
//		}
		String input;
		Boolean error = false;
		String errorMsg = "";
		// nick
		input = req.getPartAsStringFailsafe("nick",255);
		if(input.length() < 16) {
			mStorage.config.nick = req.getPartAsStringFailsafe("nick",255);
		} else {
			error = true;
			errorMsg += "nick length must be < 16\n";
		}
		// request and insert key
		input = req.getPartAsStringFailsafe("requestKey",255);
		String inputInsertKey = req.getPartAsStringFailsafe("insertKey",255);
		if(!input.endsWith("/")) { input += "/"; }
		if(!inputInsertKey.endsWith("/")) { inputInsertKey += "/"; }
		if(input.startsWith("USK")) { input = input.replace("USK", "SSK"); }
		if(inputInsertKey.startsWith("USK")) { inputInsertKey = inputInsertKey.replace("USK", "SSK"); }
		try {
			FreenetURI test = new FreenetURI(input);
			FreenetURI test1 = new FreenetURI(inputInsertKey);
			if(test.getDocName().equals("") && test1.getDocName().equals("")) {;
				mStorage.setRequestKey(input);
				mStorage.setInsertKey(inputInsertKey);
			} else {
				error = true;
				errorMsg += "wrong format for request or insert key. must be SSK@blah,blah,blah/\n";
			}
		} catch (MalformedURLException e) {
			error = true;
			errorMsg += "wrong format for request or insert key. must be SSK@blah,blah,blah " + e.getMessage() + "\n";
		}
		// TODO: add sanity checks for RSA
		// RSA public key
		input = req.getPartAsStringFailsafe("RSApublicKey",1024);
		mStorage.config.RSApublicKey = input;
		mStorage.setRSA(mStorage.config.requestKey, input);
		// RSA private key
		input = req.getPartAsStringFailsafe("RSAprivateKey",1024);
		mStorage.config.RSAprivateKey = input;
//		// chatline width
//		input = req.getPartAsStringFailsafe("chatLineWidth", 10);
//		try {
//			mStorage.config.chatLineWidth = Integer.parseInt(input);
//		} catch (NumberFormatException e) {
//			error = true;
//			errorMsg += "wrong format for chatline width. must be an integer.\n";
//		}
		// iFrame height
		input = req.getPartAsStringFailsafe("iframeHeight", 10);
		try {
			mStorage.config.iFrameHeight = Integer.parseInt(input);
		} catch (NumberFormatException e) {
			error = true;
			errorMsg += "wrong format for iFrame height. must be an integer.\n";
		}
//		// iFrame width
//		input = req.getPartAsStringFailsafe("iframeWidth", 10);
//		try {
//			mStorage.config.iFrameWidth = Integer.parseInt(input);
//		} catch (NumberFormatException e) {
//			error = true;
//			errorMsg += "wrong format for iFrame width. must be an integer.\n";
//		}
		// iFrame refresh interval
		input = req.getPartAsStringFailsafe("iframeRefreshInverval", 10);
		try {
			mStorage.config.iFrameRefreshInverval = Integer.parseInt(input);
		} catch (NumberFormatException e) {
			error = true;
			errorMsg += "wrong format for iFrame refresh interval. must be an integer.\n";
		}
//		// textarea height
//		input = req.getPartAsStringFailsafe("textareaHeight", 10);
//		try {
//			mStorage.config.textareaHeight = Integer.parseInt(input);
//		} catch (NumberFormatException e) {
//			error = true;
//			errorMsg += "wrong format for textarea height. must be an integer.\n";
//		}
//		// textarea width
//		input = req.getPartAsStringFailsafe("textareaWidth", 10);
//		try {
//			mStorage.config.textareaWidth = Integer.parseInt(input);
//		} catch (NumberFormatException e) {
//			error = true;
//			errorMsg += "wrong format for textarea width. must be an integer.\n";
//		}
//		// topicline width
//		input = req.getPartAsStringFailsafe("topiclineWidth", 10);
//		try {
//			mStorage.config.topiclineWidth = Integer.parseInt(input);
//		} catch (NumberFormatException e) {
//			error = true;
//			errorMsg += "wrong format for topicline width. must be an integer.\n";
//		}
//		// userlist height
//		input = req.getPartAsStringFailsafe("userlistHeight", 10);
//		try {
//			mStorage.config.userlistHeight = Integer.parseInt(input);
//		} catch (NumberFormatException e) {
//			error = true;
//			errorMsg += "wrong format for userlist height. must be an integer.\n";
//		}
		// userlist width
		input = req.getPartAsStringFailsafe("userlistWidth", 10);
		try {
			mStorage.config.userlistWidth = Integer.parseInt(input);
		} catch (NumberFormatException e) {
			error = true;
			errorMsg += "wrong format for userlist width. must be an integer.\n";
		}
		// timezone
		// FIXME: add sanity checks. list of all timezones?
		input = req.getPartAsStringFailsafe("timezone", 10);
		mStorage.config.timeZone = input;
		// show joins / parts
		input = req.getPartAsStringFailsafe("showJoinsParts", 10);
		if(input.toLowerCase().equals("true")) {
			mStorage.config.showJoinsParts = true;
		} else if(input.toLowerCase().equals("false")) {
			mStorage.config.showJoinsParts = false;
		} else {
			error = true;
			errorMsg += "show join / parts must be either true or false.\n";
		}
		
		// done
		HTMLNode messageDiv = new HTMLNode("div").addChild("b");
		HTMLNode fontNode;
		if(error) {
			for(String curErrorMsg : errorMsg.split("\n")) {
				fontNode = new HTMLNode("font", curErrorMsg);
				fontNode.addAttribute("color", "red");
				messageDiv.addChild(fontNode);
				messageDiv.addChild(new HTMLNode("br"));
			}
		} else {
			fontNode = new HTMLNode("font", "Configuration saved.");
			fontNode.addAttribute("color", "green");
			messageDiv.addChild(fontNode);
			mStorage.config.firstStart = false;
		}
		return createConfig(mPageNode, messageDiv);
	}
	private PageNode createRoot(HTTPRequest req, ToadletContext ctx) { 
		PageNode mPageNode; 
		if(mStorage.config.firstStart) {
			mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
			return createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
		} else {
			// FIXME: make it configurable which page to show here
			mPageNode = ctx.getPageMaker().getPageNode("flircp #test", true, ctx);
			createChannelWindow(mPageNode, "#test");
			return mPageNode;
		}
	}
	private PageNode createConfig(PageNode mPageNode) {
		return createConfig(mPageNode, null);
	}
	private PageNode createConfig(PageNode mPageNode, HTMLNode additionalMessage) {
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("id", "flircp");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		if(additionalMessage != null) {
			ownContentNode.addChild("br");
			ownContentNode.addChild(additionalMessage);
			ownContentNode.addChild("br");
		}
		ownContentNode.addChild("br");
		mPageNode.content.addChild(ownContentNode);
		
		HTMLNode table = new HTMLNode("table");
		HTMLNode tmpTRnode = new HTMLNode("tr");
		HTMLNode tmpTDnode = new HTMLNode("th", "name");
		tmpTRnode.addChild(tmpTDnode);
		tmpTDnode = new HTMLNode("th", "value");
		tmpTRnode.addChild(tmpTDnode);
		tmpTDnode = new HTMLNode("th", "description");
		tmpTRnode.addChild(tmpTDnode);
		table.addChild(tmpTRnode);
		table.addChild(addConfigTR("nick", "nick", 16, mStorage.config.nick, "nick length must be < 16"));
		table.addChild(addConfigTR("freenet request key", "requestKey", 100, mStorage.config.requestKey, ""));
		table.addChild(addConfigTR("freenet insert key", "insertKey", 100, mStorage.config.insertKey, ""));
		table.addChild(addConfigTR("public RSA key", "RSApublicKey", 100, mStorage.config.RSApublicKey, ""));
		table.addChild(addConfigTR("private RSA key", "RSAprivateKey", 100, mStorage.config.RSAprivateKey, ""));
		//table.addChild(addConfigTR("topicline width", "topiclineWidth", 4, Integer.toString(mStorage.config.topiclineWidth), "topicline width in chars"));
		//table.addChild(addConfigTR("chatline width", "chatLineWidth", 4, Integer.toString(mStorage.config.chatLineWidth), "chatline width in chars"));
		table.addChild(addConfigTR("iFrame height", "iframeHeight", 4, Integer.toString(mStorage.config.iFrameHeight), "iFrame height in px"));
		//table.addChild(addConfigTR("iFrame width", "iframeWidth", 4, Integer.toString(mStorage.config.iFrameWidth), "iFrame width in px"));
		table.addChild(addConfigTR("iFrame refresh interval", "iframeRefreshInverval", 4, Integer.toString(mStorage.config.iFrameRefreshInverval), "refresh interval in seconds"));
		//table.addChild(addConfigTR("textarea height", "textareaHeight", 4, Integer.toString(mStorage.config.textareaHeight), "textarea height in rows"));
		//table.addChild(addConfigTR("textarea width", "textareaWidth", 4, Integer.toString(mStorage.config.textareaWidth), "textarea width in cols (chars?)"));
		//table.addChild(addConfigTR("userlist height", "userlistHeight", 4, Integer.toString(mStorage.config.userlistHeight), "userlist height in px"));
		table.addChild(addConfigTR("userlist width", "userlistWidth", 4, Integer.toString(mStorage.config.userlistWidth), "userlist width in px"));
		table.addChild(addConfigTR("timezone", "timezone", 10, mStorage.config.timeZone, "timezone used for the chat window. messages will use UTC."));
		// TODO: add type to addConfigTR: text, check, select
		table.addChild(addConfigTR("show joins / parts", "showJoinsParts", 6, Boolean.toString(mStorage.config.showJoinsParts), ""));
		
		HTMLNode input = new HTMLNode("input");
		input.addAttribute("type", "submit");
		input.addAttribute("value", "save");
		
		HTMLNode mForm = new HTMLNode("form");
		mForm.addAttribute("action", "receiver");
		mForm.addAttribute("method", "POST");
		mForm.addChild(table);
		mForm.addChild(input);
		ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("width", "100%");
		ownContentNode.addAttribute("height", "100%");
		ownContentNode.addAttribute("align", "center");
		ownContentNode.addChild(mForm);
		mPageNode.content.addChild(ownContentNode);
		return mPageNode;
	}
	private HTMLNode addConfigTR(String name, String fieldName, int fieldSize, String value, String description) {
		HTMLNode tmpTRnode = new HTMLNode("tr");
		HTMLNode tmpTDnode = new HTMLNode("td", name + ":");
		tmpTRnode.addChild(tmpTDnode);
		HTMLNode input = new HTMLNode("input");
		input.addAttribute("type", "text");
		input.addAttribute("name", fieldName);
		input.addAttribute("size", Integer.toString(fieldSize));
		input.addAttribute("value", value);
		tmpTDnode = new HTMLNode("td");
		tmpTDnode.addChild(input);
		tmpTRnode.addChild(tmpTDnode);
		tmpTDnode = new HTMLNode("td", description);
		tmpTRnode.addChild(tmpTDnode);
		return tmpTRnode;
		
	}

	private PageNode createChannelWindow(PageNode mPageNode, String channel) {
		HTMLNode chanLink;
		HTMLNode chanLinkFont;
		for(channel chan : mStorage.channelList) {
			mPageNode.content.addChild(new HTMLNode("span", " | "));
			chanLinkFont = new HTMLNode("font", chan.name);
			if(!chan.name.equals(channel)) {
				chanLink = new HTMLNode("a");
				chanLink.addAttribute("href", "changeToChannel?channel=" + basicHTMLencode(chan.name.replace("#","")));
				if(chan.lastShowedIndex < chan.lastMessageIndex) {
					chanLinkFont.addAttribute("color", "red");	
				} else {
					chanLinkFont.addAttribute("color", "blue");
				}
				chanLink.addChild(chanLinkFont);
				mPageNode.content.addChild(chanLink);
			} else {
				chanLinkFont.addAttribute("color", "black");
				mPageNode.content.addChild(chanLinkFont);
			}
		}
		mPageNode.content.addChild(new HTMLNode("span", " |"));
		HTMLNode iframe = new HTMLNode("iframe");
		//iframe.addAttribute("width", Integer.toString(mStorage.config.iFrameWidth));
		iframe.addAttribute("height", Integer.toString(mStorage.config.iFrameHeight));
		iframe.addAttribute("width", "100%");
		//iframe.addAttribute("height", "100%");
		iframe.addAttribute("scrolling", "no");
		iframe.addAttribute("name", "flircp_iframe");
		iframe.addAttribute("src", "show?channel="+ channel.replace("#",""));
		iframe.setContent("you need to activate frames to use flircp");
		HTMLNode formMain = new HTMLNode("form");
		formMain.addAttribute("action", "sendMessage?channel=" + channel.replace("#",""));
		formMain.addAttribute("method", "POST");
		HTMLNode inputbox = new HTMLNode("input");
		inputbox.addAttribute("type", "text");
		inputbox.addAttribute("name", "messageinput");
		//inputbox.addAttribute("size", Integer.toString(mStorage.config.chatLineWidth));
		inputbox.addAttribute("style", "width: 100%;");
		inputbox.addAttribute("autofocus", "autofocus");
		inputbox.addAttribute("autocomplete", "off");
		formMain.addChild(iframe);
		formMain.addChild(new HTMLNode("br"));
		formMain.addChild(inputbox);
		mPageNode.content.addChild(formMain);
		return mPageNode;
	}
	
	private PageNode createStats(HTTPRequest req, ToadletContext ctx) {
		PageNode mPageNode = ctx.getPageMaker().getPageNode("flircp statistics", true, ctx);
		
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("id", "flircp");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		ownContentNode.addChild("br");
		HTMLNode tableNode = new HTMLNode("table");
		tableNode = createStats_FetchOverview(tableNode);
		ownContentNode.addChild(tableNode);
		ownContentNode.addChild(new HTMLNode("br"));
		tableNode = new HTMLNode("table");
		tableNode = createStats_QueueOverview(tableNode);
		ownContentNode.addChild(tableNode);
		ownContentNode.addChild(new HTMLNode("br"));
		tableNode = new HTMLNode("table");
		tableNode = createStats_ChannelList(tableNode);
		ownContentNode.addChild(tableNode);
		ownContentNode.addChild(new HTMLNode("br"));
		tableNode = new HTMLNode("table");
		tableNode = createStats_UserList(tableNode);
		ownContentNode.addChild(tableNode);
		mPageNode.content.addChild(ownContentNode);
		return mPageNode;
	}
	
	private HTMLNode createStats_QueueOverview(HTMLNode table) {
		HTMLNode tmpTrNode = new HTMLNode("tr");
		HTMLNode tmpTdNode;
		// header
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "3");
		tmpTdNode.setContent("task processing");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// description
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("td");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th", "waiting");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th", "processing time");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// content
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("messageparser");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", Integer.toString(mPtrWorker.getMessageParser().getCurrentQueueSize()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", String.format("%.8f", mPtrWorker.getMessageParser().getProcessingTime()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th", "insertqueue");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", Integer.toString(mPtrWorker.getCurrentQueueSize()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td", String.format("%.8f", mPtrWorker.getProcessingTime()));
		tmpTdNode.addAttribute("align", "right");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		return table;
	}

	private HTMLNode createStats_UserList(HTMLNode table) {
		// TODO: additionally save ddos and valid per user
		SimpleDateFormat sdf = new SimpleDateFormat("z HH:mm:ss");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		HTMLNode tmpTrNode;
		HTMLNode tmpTdNode;
		users user;
		// header
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "8");
		tmpTdNode.setContent("user statistics");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// description
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("current nick");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("channels joined");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("channel messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("last\nmessageindex");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("ddos/invalid messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("ddos/invalid identities");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("failed messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("last activity");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		try {
			for(String ident : mStorage.knownIdents) {
				user = mStorage.userMap.get(ident);
				// values
				tmpTrNode = new HTMLNode("tr");
				tmpTdNode = new HTMLNode("td");
				HTMLNode tmpANode = new HTMLNode("a",basicHTMLencode(user.nick));
				//tmpANode.addAttribute("href", "../" + ident.replace("SSK", "USK") + mStorage.getMessageBase() + "|" + mStorage.getCurrentDateString() + "|Identity/" + user.identEdition);
				tmpANode.addAttribute("href", "profile?ident=" + ident);
				tmpTdNode.addChild(tmpANode);
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Integer.toString(user.channelCount));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Integer.toString(user.messageCount));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Long.toString(user.messageEditionHint));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Long.toString(user.message_ddos));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Long.toString(user.identity_ddos));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.setContent(Integer.toString(user.failedMessageRequests.size()));
				tmpTdNode.addAttribute("align", "right");
				tmpTrNode.addChild(tmpTdNode);
				tmpTdNode = new HTMLNode("td");
				tmpTdNode.addAttribute("nowrap", "nowrap");
				tmpTdNode.setContent(sdf.format(user.lastActivity));
				tmpTrNode.addChild(tmpTdNode);
				table.addChild(tmpTrNode);
			}
			return table;
		} catch (ConcurrentModificationException e) {
			// recursion ftw.. as long as there are not hundreds of new idents added at the same time
			// FIXME: this sucks
			return createStats_UserList( new HTMLNode("table"));
		}
	}
	private HTMLNode createStats_ChannelList(HTMLNode table) {
		SimpleDateFormat sdf = new SimpleDateFormat("z HH:mm:ss");
		sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
		HTMLNode tmpTrNode;
		HTMLNode tmpTdNode;
		// header
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "5");
		tmpTdNode.setContent("channel statistics");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// description
		tmpTrNode = new HTMLNode("tr");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("channel");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("current users");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("messages");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("last message activity");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("topic");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		
		for(channel chan : mStorage.channelList) {
			// values
			tmpTrNode = new HTMLNode("tr");
			tmpTdNode = new HTMLNode("td");
			HTMLNode tmpAnode = new HTMLNode("a",chan.name);
			tmpAnode.addAttribute("href", "changeToChannel?channel=" + chan.name.replace("#", ""));
			tmpTdNode.addChild(tmpAnode);
			tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td");
			tmpTdNode.setContent(Integer.toString(chan.currentUserCount));
			tmpTdNode.addAttribute("align", "right");
			tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td");
			tmpTdNode.setContent(Integer.toString(chan.lastMessageIndex +1));
			tmpTdNode.addAttribute("align", "right");
			tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td");
			tmpTdNode.setContent(sdf.format(chan.lastMessageActivity));
			tmpTrNode.addChild(tmpTdNode);
			tmpTdNode = new HTMLNode("td", chan.topic);
			tmpTrNode.addChild(tmpTdNode);
			table.addChild(tmpTrNode);
		}
		return table;
	}
	private HTMLNode createStats_FetchOverview(HTMLNode table) {
		// header indexChainStructure
		HTMLNode tmpTrNode = new HTMLNode("tr");
		HTMLNode tmpTdNode = new HTMLNode("th");
		tmpTdNode.addAttribute("colspan", "6");
		tmpTdNode.setContent("global statistics");
		tmpTrNode.addChild(tmpTdNode);
		table.addChild(tmpTrNode);
		// header table
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("td", "");
		tmpTrNode.addChild("th", "valid");
		tmpTrNode.addChild("th", "new");
		tmpTrNode.addChild("th", "ddos");
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("concurrent fetchers");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("th");
		tmpTdNode.setContent("USK subscriptions");
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("th", "ddos old version");
		//tmpTrNode.addChild("th", "ddos unknown version");
		//tmpTrNode.addChild("th", "count");
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("th", "Announce");
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.announce_valid));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.announce_valid - mStorage.announce_duplicate));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.announce_ddos));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getAnnounceFetcher().concurrentFetchCount));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent("-");
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosOldVersion));
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosUnknownVersion));
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("th", "Identity");
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.identity_valid));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent("-");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.identity_ddos));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getIdentityFetcher().concurrentFetchCount));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getIdentityFetcher().getSubscriptionCount()));
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosOldVersion));
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosUnknownVersion));
		table.addChild(tmpTrNode);
		tmpTrNode = new HTMLNode("tr");
		tmpTrNode.addChild("th", "Message");
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.message_valid));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent("-");
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mStorage.message_ddos));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getMessageFetcher().concurrentFetchCount));
		tmpTrNode.addChild(tmpTdNode);
		tmpTdNode = new HTMLNode("td");
		tmpTdNode.addAttribute("align", "right");
		tmpTdNode.setContent(Integer.toString(mPtrWorker.getMessageFetcher().getSubscriptionCount()));
		tmpTrNode.addChild(tmpTdNode);
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosOldVersion));
		//tmpTrNode.addChild("td", Integer.toString(mStorage.ddosUnknownVersion));
		table.addChild(tmpTrNode);
		
		//tmpTrNode.addChild("td", Integer.toString(mStorage.messageCount));
		return table;
	}
	
	private PageNode createRequestInfo(HTTPRequest req, ToadletContext ctx) {
		return createRequestInfo(req, ctx, false);
	}
	private PageNode createRequestInfo(HTTPRequest req, ToadletContext ctx, boolean isNotImplemented) { 
		URI uri = ctx.getUri();
		PageNode mPageNode = ctx.getPageMaker().getPageNode("flircp InfoPage", true, ctx);
		mPageNode.content.addChild("br");
		if(isNotImplemented) {
			mPageNode.content.addChild("span","You reached this page because flircp was not in the mood to implement this feature.");
		} else {
			mPageNode.content.addChild("span","You reached this page because flircp did not found the requested URI");
		}
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// requested URI
		mPageNode.content.addChild("b", "URI:");
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("i", uri.toString());
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// used Method
		mPageNode.content.addChild("b", "Method:");
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("i", req.getMethod());
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// POST data
		mPageNode.content.addChild("b", "HTTPRequest.getParts()-->HTTPRequest.getPartAsStringFailsafe(part, 255):");
		mPageNode.content.addChild("br");
		String tmpGetRequestParts[] = req.getParts();
		for (int i = 0; i < tmpGetRequestParts.length; i++) {
			mPageNode.content.addChild("i", tmpGetRequestParts[i] + "-->" + req.getPartAsStringFailsafe(tmpGetRequestParts[i], 255));
			mPageNode.content.addChild("br");
		}
		mPageNode.content.addChild("br");
		mPageNode.content.addChild("br");
		// Parameters Key-->Value
		mPageNode.content.addChild("b", "HTTPRequest.getParameterNames()-->HTTPRequest.getParam(parameter):");
		mPageNode.content.addChild("br");
		String partString = "";
		Collection<String> tmpGetRequestParameterNames = req.getParameterNames();
		for (Iterator<String> tmpIterator = tmpGetRequestParameterNames.iterator(); tmpIterator.hasNext();) {
			partString = tmpIterator.next();
			mPageNode.content.addChild("i", partString + "-->" + req.getParam(partString));
			mPageNode.content.addChild("br");
		}
		return mPageNode;
	}
}
