package plugins.FLIRCP.freenetMagic;

import freenet.clients.http.LinkFilterExceptedToadlet;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.Iterator;
import java.util.Properties;
import java.util.TimeZone;

import javax.imageio.ImageIO;
import plugins.FLIRCP.Worker;
import plugins.FLIRCP.freenetMagic.identicon.Identicon;
import plugins.FLIRCP.storage.RAMstore;
import plugins.FLIRCP.storage.RAMstore.PlainTextMessage;
import plugins.FLIRCP.storage.RAMstore.Channel;
import plugins.FLIRCP.storage.RAMstore.User;
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
        
        //FIXME: SOMEONE DO THIS RIGHT SOMEHOW!
        @Override
        public boolean allowPOSTWithoutPassword() {
		return true;
	}
	
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
		if(mStorage.config.AllowFullAccessOnly && !ctx.isAllowedFullAccess()) {
			// full access not allowed for the requesting ip
			writeHTMLReply(ctx, 403, "", "Your host is not allowed to access this page.<br />Try adding it to 'Hosts having a full access to the Freenet web interface (read warning)' in your <a href='../config/fproxy'>fproxy configuration</a>.");
		}
		String requestedPath = uri.toString().replace(mNameSpace, "");
		PageNode mPageNode;
		if(requestedPath.equals("")) {
			writeHTMLReply(ctx, 200, "OK", createRoot(req, ctx).outer.generate());
		} else if(requestedPath.startsWith("channelWindow")) {
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				String channel = "#test";
				if(!mStorage.config.autojoinChannel
						&& !mStorage.config.joinedChannels.contains(channel)
						&& mStorage.config.joinedChannels.size() > 0) {
					channel =  mStorage.config.joinedChannels.get(0);
				}
				mPageNode = ctx.getPageMaker().getPageNode("flircp " + channel, true, ctx);
				mPageNode = createChannelWindow(mPageNode, channel);
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
			String channel = "#" + requestedPath.split("=")[1];
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				if(!mStorage.config.autojoinChannel && !mStorage.config.joinedChannels.contains(channel)) {
					mStorage.config.joinedChannels.add(channel);
				}
				mPageNode = ctx.getPageMaker().getPageNode("flircp " + channel, true, ctx);
				mPageNode = createChannelWindow(mPageNode, channel);
			}
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.startsWith("part?channel=")) {
			String channel = "#" + requestedPath.split("=")[1];
			if(mStorage.config.firstStart) {
				mPageNode = ctx.getPageMaker().getPageNode("Hello stranger. Welcome to flircp", true, ctx);
				mPageNode = createConfig(mPageNode, parseWelcomeMessage(mStorage.welcomeText));
			} else {
				if(!mStorage.config.autojoinChannel && mStorage.config.joinedChannels.contains(channel)) {
					mStorage.config.joinedChannels.remove(channel);
				}
				if(mStorage.config.joinedChannels.size() > 0) {
					channel = mStorage.config.joinedChannels.get(0);
				} else {
					channel = "#test";
				}
				mPageNode = ctx.getPageMaker().getPageNode("flircp " + channel, true, ctx);
				mPageNode = createChannelWindow(mPageNode, channel);
			}
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.startsWith("show?channel=")) {
			writeHTMLReply(ctx, 200, "OK", createIframeContent("#"+requestedPath.split("=")[1].replace("#lastLine", "")));
		} else if(requestedPath.startsWith("profile?ident=")) {
			mPageNode = ctx.getPageMaker().getPageNode("flircp profile", true, ctx);
			mPageNode = createProfilePage(mPageNode, requestedPath.split("=")[1]);
			writeHTMLReply(ctx, 200, "OK", mPageNode.outer.generate());
		} else if(requestedPath.startsWith("sendMessage?channel=")) {
			// TODO: check for autojoin channel and if channel available
			String channel = "#" + requestedPath.split("=")[1];
			String message = req.getPartAsStringFailsafe("messageinput", 512);
			writeHTMLReply(ctx, 200, "OK", handleWriteMessage(ctx, channel, message).outer.generate());
		} else if(requestedPath.startsWith("setValueFromIFrame")) {
			//System.err.println("[WebInterface] got setValueFromIFrame: channel=" + channel);
			//String[] users = req.getMultipleParam("user");
			//System.err.println("[WebInterface] got setValueFromIFrame: userlist length=" + users.length);
			//System.err.println("[WebInterface] got setValueFromIFrame: whois=" + req.getPartAsStringFailsafe("whois", 5));
			//if(users.length > 0) {
			//	if(req.getPartAsStringFailsafe("whois", 5).equals("whois")) {
			//		for(String user : users) {
			//			mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, "[flircp] WHOIS " + user + "\n" + mStorage.getWhoIs(user), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			//		}	
			//	}
			//}
			String channel = req.getPartAsStringFailsafe("channel", 50);
			String user = req.getPartAsStringFailsafe("user", 22);
			if(req.getPartAsStringFailsafe("whois", 5).equals("whois")) {
				mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, "[flircp] WHOIS " + user + "\n" + mStorage.getWhoIs(user), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			}
			writeHTMLReply(ctx, 200, "OK", createIframeContent(channel));
		} else {
			writeHTMLReply(ctx, 200, "OK", createRequestInfo(req, ctx).outer.generate());
		}
	}
	private String basicHTMLencode(String input) {
		//& → &amp;
		//< → &lt;
		//> → &gt;
		//' → &#39;
		//" → &quot;
		//\n→ <br />
		input = input.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;").replace("\n", "<br />");
		Boolean bOpened = false;
		Boolean iOpened = false;
		Boolean uOpened = false;
		StringBuilder builder = new StringBuilder();
		for(char character : input.toCharArray()) {
			if(character == 0x02) {
				// bold
				if(!bOpened) {
					builder.append("<b>");
					bOpened = true;
				} else {
					builder.append("</b>");
					bOpened = false;
				}
			} else if(character == 0x1D) {
				// italics
				if(!iOpened) {
					builder.append("<i>");
					iOpened = true;
				} else {
					builder.append("</i>");
					iOpened = false;
				}
			} else if(character == 0x1F) {
				// underline
				if(!uOpened) {
					builder.append("<u>");
					uOpened = true;
				} else {
					builder.append("</u>");
					uOpened = false;
				}
			} else {
				builder.append(character);
			}
		}
		if(bOpened) { builder.append("</b>"); }
		if(iOpened) { builder.append("</i>"); }
		if(uOpened) { builder.append("</u>"); }
		return builder.toString();
	}

	private HTMLNode parseWelcomeCreateLink(String tag) {
		HTMLNode aNode = new HTMLNode("a", tag);
		if(tag.equals("FLIP")) {
			aNode.addAttribute("href", "../USK@pGQPA-9PcFiE3A2tCuCjacK165UaX07AQYw98iDQrNA,8gwQ67ytBNR03hNj7JU~ceeew22HVq6G50dcEeMcgks,AQACAAE/flip/9/");
		} else if(tag.equals("Freenet Social Network Guide for FLIP")) {
			aNode.addAttribute("href", "../USK@t5zaONbYd5DvGNNSokVnDCdrIEytn9U5SSD~pYF0RTE,guWyS9aCMcywU5PFBrKsMiXs7LzwKfQlGSRi17fpffc,AQACAAE/fsng/37/flip.html");
		} else {
			aNode.addAttribute("href", "not found");
		}
		aNode.addAttribute("target", "_blank");
		return aNode;
	}
	private HTMLNode parseWelcomeMessage(String message) {
		// TODO: use freenets html parser instead?
		// TODO: use own parser basicHTMLencode() instead?
		HTMLNode ownContentNode = new HTMLNode("div");
		ownContentNode.addAttribute("style", "margin:auto; width:55em; word-wrap: break-word;");
		String buffer;
		for(String line : message.split("\n")) {
			buffer = "";
			for(String word : line.split(" ")) {
				if(!buffer.equals("") && (word.startsWith("[a]") || word.startsWith("[b]"))) {
					// TODO: uh? check this^ again. should be !(word.startsWith() || word.startsWith())?
					ownContentNode.addChild(new HTMLNode("span", buffer));
					buffer = "";
				}
				if(word.startsWith("[a]") && word.contains("[/a]")) {
					buffer = word.replace("[a]", "").replace("[/a]", "");
					ownContentNode.addChild(parseWelcomeCreateLink(buffer));
					buffer = " ";
				} else if(word.startsWith("[a]") && !word.contains("[/a]")) {
					buffer = word.replace("[a]", "") + " ";
				} else if(word.contains("[/a]")) {
					buffer += word.replace("[/a]", "");
					ownContentNode.addChild(parseWelcomeCreateLink(buffer));
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
	
	private PageNode handleWriteMessage(ToadletContext ctx, String channel, String message) {
		// FIXME: check form password and max size! first dev release you know..
		// FIXME: max size is currently "checked" by using only the first 512 bytes from POST input.
		// FIXME: allow more than that, split it up and loop until everything is put into insert queue.
		// TODO: if insertmessage returns false show error in web ui <-- this can't happen actually..
		// TODO: check if first char == / and split by space afterwards
		if(message.toLowerCase().startsWith("/whois")) {
			message = message.substring(6).trim();
			mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, "[flircp] WHOIS " + message + "\n" + mStorage.getWhoIs(message), "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
		} else if(message.toLowerCase().startsWith("/topic")) {
			//FIXME: add topic validation
			String newTopic = message.substring(6).trim();
			mStorage.setTopic(channel, newTopic);
			//channel=#test
			//sentdate=2012-09-21 19:19:30
			//type=settopic
			//
			//super fancy topic
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
			String out = "channel=" + channel + "\n";
			out += "sentdate=" + sdf.format(new Date().getTime()) + "\n";
			out += "type=settopic\n\n";
			out += newTopic;
			mPtrWorker.insertMessage(mStorage.new PlainTextMessage("message", out, "", channel, 0, 0));
			mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, "changed topic to: " + newTopic, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
		} else if(message.toLowerCase().startsWith("/help")) {
			message = message.substring(5).trim();
			if(message.equals("")) {
				message = "[flircp] commands:\n";
				message += "/me [message] writes messages as third person\n";
				message += "/join [channel] changes channelwindow to [channel] or creates new channel\n";
				message += "/whois [nick] shows public key, connected channels and time since last message\n";
				message += "/topic [newTopic] changes topic of current channel to [newTopic]\n";
				message += "/version shows version of flircp";
				mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, message, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			}
		} else if(message.toLowerCase().startsWith("/version")) {
			message = "[flircp] current flircp version: " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_debug;
			mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, message, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
		} else if(message.toLowerCase().startsWith("/j")) {
			String newChannel;
			if(message.toLowerCase().startsWith("/join")) {
				newChannel = message.substring(5).trim().toLowerCase();
			} else {
				newChannel = message.substring(2).trim().toLowerCase();
			}
			if(mStorage.addNewChannel(newChannel)) {
				channel = newChannel;
				if(!mStorage.config.autojoinChannel && !mStorage.config.joinedChannels.contains(channel)) {
					mStorage.config.joinedChannels.add(channel);
				}
			} else {
				message = "[flircp] channelname '" + newChannel + "' is invalid";
				mStorage.getChannel(channel).messages.add(mStorage.new PlainTextMessage(mStorage.config.requestKey, message, "*", channel, new Date().getTime(), mStorage.getChannel(channel).lastMessageIndex));
			}
		} else if(message.length() > 0) {
			//channel=#test
			//sentdate=2012-08-25 12:42:58
			//type=channelmessage
			//
			//ping
			if(message.toLowerCase().startsWith("/me")) {
				message = (char) 1 + "ACTION" + message.substring(3, message.length()) + (char) 1;
			}
			message = message.replace("[b]", "" + (char) 0x02).replace("[/b]", "" + (char) 0x02);
			message = message.replace("[i]", "" + (char) 0x1D).replace("[/i]", "" + (char) 0x1D);
			message = message.replace("[u]", "" + (char) 0x1F).replace("[/u]", "" + (char) 0x1F);
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
		PageNode mPageNode = ctx.getPageMaker().getPageNode("flircp " + channel, true, ctx);
		mPageNode = createChannelWindow(mPageNode, channel);
		return mPageNode;
	}
	private String createIframeContent(String channel) {
		String iFrameHTML = "<html>\n";
		iFrameHTML += "<head>\n";
		iFrameHTML += "<meta http-equiv='refresh' content='" + mStorage.config.iFrameRefreshInverval + ";url=/flircp/show?channel=" + channel.replace("#", "") + "#lastLine'>\n";
		iFrameHTML += "</head>\n";
		iFrameHTML += "<body>\n";
		if(mStorage.getChannel(channel) != null && (mStorage.config.autojoinChannel
				|| (!mStorage.config.autojoinChannel && mStorage.config.joinedChannels.contains(channel)))
			) {
			iFrameHTML += "<form action='setValueFromIFrame' method='POST'>\n";
			iFrameHTML += "<input type='hidden' name='channel' value='" + channel + "' />\n";
			iFrameHTML += "<table border='0' cellspacing='0' cellpadding='0' height='100%' width='100%'>\n";
			iFrameHTML += "<tr>\n";
			iFrameHTML += "<td valign='top'>\n";
			iFrameHTML += "<input type='text' name='topic' readonly='readonly' title='to change the topic use /topic newTopic' value='Topic: " + mStorage.getChannel(channel).topic.replace("'", "´").replace("\"","´") + "' style='width: 100%;' />\n";
//			iFrameHTML += "<textarea style='height: 100%; width: 100%;' readonly='readonly' id='channelwindow'>\n";
			iFrameHTML += "<div style='height: " + (mStorage.config.iFrameHeight - 50) + "px; width: 99.7%; overflow: scroll; overflow-x: hidden; padding: 1px; border: 1px solid #666;'>\n";
			iFrameHTML += "<table cellspaing='0' cellpadding='0' width='100%'>\n";
			int messageIndex = 0;
			SimpleDateFormat sdf = new SimpleDateFormat("z HH:mm:ss");
			sdf.setTimeZone(TimeZone.getTimeZone(mStorage.config.timeZone));
			// ugly but necessary as we need a copy of the data not a reference. reason: concurrent modification exceptions.
			PlainTextMessage[] channelArray = mStorage.getChannel(channel).messages.toArray(new PlainTextMessage [mStorage.getChannel(channel).messages.size()]);
			String newContent = "";
			for(PlainTextMessage message : channelArray) {
				iFrameHTML += newContent;
				String user = message.nickname;
				if(!user.equals("*")) {
					if(mStorage.config.encapsulateNicks) {
						user = "<" + user + ">";
						user = basicHTMLencode(user);
					} else if(mStorage.config.useDelimeterForNicks) {
						user += " |";
						user = basicHTMLencode(user);
					}
				}
				if(!message.message.toLowerCase().contains(mStorage.config.nick.toLowerCase())) {
					newContent = "<tr><td valign='top' nowrap='nowrap' style='font-size: " + mStorage.config.iFrameFontSize + "pt;'>" + sdf.format(message.timeStamp) + "</td><td valign='top' nowrap='nowrap' style='font-size: " + mStorage.config.iFrameFontSize + "pt;' align='right'>&nbsp;" + user + "&nbsp;</td><td valign='top' width='100%' style='font-size: " + mStorage.config.iFrameFontSize + "pt;'><div style=\"word-wrap: break-word;\">" + basicHTMLencode(message.message) + "</div></td></tr>\n";					
				} else {
					// highlight
					newContent = "<tr><td valign='top' nowrap='nowrap' style='font-size: " + mStorage.config.iFrameFontSize + "pt;'>" + sdf.format(message.timeStamp) + "</td><td valign='top' nowrap='nowrap' style='font-size: " + mStorage.config.iFrameFontSize + "pt;' align='right'>&nbsp;" + user + "&nbsp;</td><td valign='top' width='100%' style='font-size: " + mStorage.config.iFrameFontSize + "pt;'><font color='red'><div style=\"word-wrap: break-word;\">" + basicHTMLencode(message.message) + "</div></font></td></tr>\n";
				}
				messageIndex = message.index;
			}
			if(newContent.length() > 0) {
				iFrameHTML += newContent.replace("</td></tr>\n", "<a name='lastLine'></a></td></tr>\n");
			}
			mStorage.getChannel(channel).lastShowedIndex = messageIndex;
			iFrameHTML += "</table>\n";
			iFrameHTML += "</div>\n";
//			iFrameHTML += "</textarea>";
			iFrameHTML += "</td>\n";
			iFrameHTML += "<td valign='top' width='" + (mStorage.config.userlistWidth + 2) + "px'>\n";
			iFrameHTML += "<table height='100%' width='100%' cellpadding='0' cellspacing='0'>\n";
			iFrameHTML += "<tr>\n";
			iFrameHTML += "<td valign='top'>\n";
			iFrameHTML += "<input type='submit' name='whois' value='whois'>\n";
			iFrameHTML += "<input type='submit' name='pm' value='PM' disabled='disabled'>\n";
			iFrameHTML += "<br />\n";
			iFrameHTML += "<input type='submit' name='operator' value='Operator' disabled='disabled'>\n";
			iFrameHTML += "<input type='submit' name='voice' value='Voice' disabled='disabled'>\n";
			iFrameHTML += "</td>\n";
			iFrameHTML += "</tr>\n";
			iFrameHTML += "<tr>\n";
			// FIXME: this is bad syntax and wrong, is it?
			iFrameHTML += "<td height='100%'>\n";
			iFrameHTML += "<select multiple='multiple' style='width: 100%; height: 100%;' name='user'>\n";

			// returns a new list => no concurrent modifications possible
			for(String user : mStorage.getUsersInChannel(channel)) {
				iFrameHTML += "<option>" + user.replace("/option","") + "</option>\n";
			}
			iFrameHTML += "</select>\n";
			iFrameHTML += "</td>\n";
			iFrameHTML += "</tr>\n";
			iFrameHTML += "</table>\n";
			iFrameHTML += "</td>\n";
			iFrameHTML += "</tr>\n";
			iFrameHTML += "</table>\n";
			iFrameHTML += "</form>\n";
//			if(mStorage.config.enableJavaScriptToScrollDownToLatestMessage) {
//				iFrameHTML += "<script>var textarea=document.getElementById('channelwindow'); textarea.scrollTop=textarea.scrollHeight;</script>";
//			}
		} else {
			iFrameHTML += "channel " + channel + " not found. If you configured flircp to not join channels automatically you need to join a channel with /join #channel or by using the channel list at the statistic page.";
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
			if(test.getDocName().equals("") && test1.getDocName().equals("")) {
				if(!mStorage.config.requestKey.equals(input)) {
					mStorage.setRequestKey(input);
					mStorage.setInsertKey(inputInsertKey);
					mPtrWorker.getIdentityFetcher().removeSingleFetcher(input);
					mPtrWorker.getMessageFetcher().removeSingleFetcher(input);
				}
			} else {
				error = true;
				errorMsg += "wrong format for request or insert key. must be SSK@blah,blah,blah/\n";
			}
		} catch (MalformedURLException e) {
			error = true;
			errorMsg += "wrong format for request or insert key. must be SSK@blah,blah,blah/ " + e.getMessage() + "\n";
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
		// js scroll down
//		input = req.getPartAsStringFailsafe("useJsToScrollDown", 10);
//		if(input.toLowerCase().equals("true")) {
//			mStorage.config.enableJavaScriptToScrollDownToLatestMessage = true;
//		} else if(input.toLowerCase().equals("false")) {
//			mStorage.config.enableJavaScriptToScrollDownToLatestMessage = false;
//		} else {
//			error = true;
//			errorMsg += "enable javascript to scroll down to latest message must be either true or false.\n";
//		}
		// autojoin channels
		input = req.getPartAsStringFailsafe("autojoinChannels", 10);
		if(input.toLowerCase().equals("true")) {
			mStorage.config.autojoinChannel = true;
		} else if(input.toLowerCase().equals("false")) {
			mStorage.config.autojoinChannel = false;
		} else {
			error = true;
			errorMsg += "autojoin channels must be either true or false.\n";
		}
		// irc style nicks
		input = req.getPartAsStringFailsafe("encapsulateNicks", 10);
		if(input.toLowerCase().equals("true")) {
			mStorage.config.encapsulateNicks = true;
			mStorage.config.useDelimeterForNicks = false;
		} else if(input.toLowerCase().equals("false")) {
			mStorage.config.encapsulateNicks = false;
			mStorage.config.useDelimeterForNicks = true;
		} else {
			error = true;
			errorMsg += "encapsulate nicks in < > must be either true or false.\n";
		}
		
		// iFrame font size
		input = req.getPartAsStringFailsafe("iframeFontSize", 10);
		try {
			mStorage.config.iFrameFontSize = Integer.parseInt(input);
		} catch (NumberFormatException e) {
			error = true;
			errorMsg += "wrong format for iFrame font size. must be an integer.\n";
		}
		
		// save to configuration file
		Properties configProps = new Properties();
		FileInputStream in;
		FileOutputStream out;
		try {
			in = new FileInputStream("flircp/config");
			configProps.load(in);
			in.close();
		} catch (FileNotFoundException e) {
			// configuration file does not yet exist or can't be opened for reading
		} catch (IOException e) {
			// file can't be read?
		} catch (IllegalArgumentException e) {
			// configuration file contains at least one invalid property
		}
		configProps.setProperty("freenet.key.request", mStorage.config.requestKey);
		configProps.setProperty("freenet.key.insert", mStorage.config.insertKey);
		configProps.setProperty("nick", mStorage.config.nick);
		configProps.setProperty("rsa.public", mStorage.config.RSApublicKey);
		configProps.setProperty("rsa.private", mStorage.config.RSAprivateKey);
		configProps.setProperty("irc.channels.autojoin", mStorage.config.autojoinChannel.toString());
		configProps.setProperty("ui.web.iframe.height", Integer.toString(mStorage.config.iFrameHeight));
		configProps.setProperty("ui.web.iframe.refreshinterval", Integer.toString(mStorage.config.iFrameRefreshInverval));
		configProps.setProperty("ui.web.chat.fontsize", Integer.toString(mStorage.config.iFrameFontSize));
		configProps.setProperty("ui.web.chat.userlist.width", Integer.toString(mStorage.config.userlistWidth));
		configProps.setProperty("ui.web.chat.timezone", mStorage.config.timeZone);
		configProps.setProperty("ui.web.chat.showJoinPart", mStorage.config.showJoinsParts.toString());
		configProps.setProperty("ui.web.chat.nickStyleIRC", mStorage.config.encapsulateNicks.toString());
		try {
			out = new FileOutputStream("flircp/config", false);
			configProps.store(out, "configuration for flircp " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_debug + " created at the configuration page.");
			out.close();
		} catch (FileNotFoundException e) {
			// out stream can't create file
			error = true;
			errorMsg += "failed to create or modify configuration file. please check your file permissions for freenet_directory/flircp/config. " + e.getMessage() + "\n";
		} catch (IOException e) {
			// configProps can't write to file
			error = true;
			errorMsg += "failed to create or modify configuration file. please check your file permissions for freenet_directory/flircp/config. " + e.getMessage() + "\n";
		} catch(ClassCastException e) {
			// at least one property is invalid 
			error = true;
			errorMsg += "at least one of your configuration values is invalid. please correct it and save again. " + e.getMessage() + "\n";
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
			if(mStorage.config.firstStart) {
				messageDiv.addChild("br");
				messageDiv.addChild("br");
				messageDiv.addChild(new HTMLNode("span", "You can now "));
				fontNode = new HTMLNode("a", "start chatting");
				fontNode.addAttribute("href", "channelWindow");
				messageDiv.addChild(fontNode);
				messageDiv.addChild(new HTMLNode("span", "."));
			}
			mStorage.config.firstStart = false;
		}
		configProps.setProperty("firstStart", mStorage.config.firstStart.toString());
		try {
			out = new FileOutputStream("flircp/config", false);
			configProps.store(out, "configuration for flircp " + mStorage.config.version_major + "." + mStorage.config.version_minor + "." + mStorage.config.version_debug + " created at the configuration page.");
			out.close();
		} catch (Exception e) {
			// ignore
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
			String channel = "#test";
			if(!mStorage.config.autojoinChannel && !mStorage.config.joinedChannels.contains(channel) && mStorage.config.joinedChannels.size() > 0) {
				channel =  mStorage.config.joinedChannels.get(0);
			}
			mPageNode = ctx.getPageMaker().getPageNode("flircp " + channel, true, ctx);
			createChannelWindow(mPageNode, channel);
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
		table.addChild(addConfigTR("freenet request key", "requestKey", 60, mStorage.config.requestKey, ""));
		table.addChild(addConfigTR("freenet insert key", "insertKey", 60, mStorage.config.insertKey, ""));
		table.addChild(addConfigTR("public RSA key", "RSApublicKey", 60, mStorage.config.RSApublicKey, ""));
		table.addChild(addConfigTR("private RSA key", "RSAprivateKey", 60, mStorage.config.RSAprivateKey, ""));
		//table.addChild(addConfigTR("topicline width", "topiclineWidth", 4, Integer.toString(mStorage.config.topiclineWidth), "topicline width in chars"));
		//table.addChild(addConfigTR("chatline width", "chatLineWidth", 4, Integer.toString(mStorage.config.chatLineWidth), "chatline width in chars"));
		table.addChild(addConfigTR("iFrame height", "iframeHeight", 4, Integer.toString(mStorage.config.iFrameHeight), "iFrame height in px"));
		table.addChild(addConfigTR("iFrame font size", "iframeFontSize", 4, Integer.toString(mStorage.config.iFrameFontSize), "iFrame font size in pt"));
		//table.addChild(addConfigTR("iFrame width", "iframeWidth", 4, Integer.toString(mStorage.config.iFrameWidth), "iFrame width in px"));
		table.addChild(addConfigTR("iFrame refresh interval", "iframeRefreshInverval", 4, Integer.toString(mStorage.config.iFrameRefreshInverval), "refresh interval in seconds"));
		//table.addChild(addConfigTR("textarea height", "textareaHeight", 4, Integer.toString(mStorage.config.textareaHeight), "textarea height in rows"));
		//table.addChild(addConfigTR("textarea width", "textareaWidth", 4, Integer.toString(mStorage.config.textareaWidth), "textarea width in cols (chars?)"));
		//table.addChild(addConfigTR("userlist height", "userlistHeight", 4, Integer.toString(mStorage.config.userlistHeight), "userlist height in px"));
		table.addChild(addConfigTR("userlist width", "userlistWidth", 4, Integer.toString(mStorage.config.userlistWidth), "userlist width in px"));
		table.addChild(addConfigTR("timezone", "timezone", 10, mStorage.config.timeZone, "timezone used for the chat window. messages will use UTC."));
		// TODO: add type to addConfigTR: text, check, select
		table.addChild(addConfigTR("show joins / parts", "showJoinsParts", 6, Boolean.toString(mStorage.config.showJoinsParts), ""));
//		table.addChild(addConfigTR("scroll down textarea", "useJsToScrollDown", 6, Boolean.toString(mStorage.config.enableJavaScriptToScrollDownToLatestMessage), "enable JavaScript to scroll down to the latest message"));
		table.addChild(addConfigTR("autojoin discovered channels", "autojoinChannels", 6, Boolean.toString(mStorage.config.autojoinChannel), ""));
		table.addChild(addConfigTR("encapsulate nicks in < >", "encapsulateNicks", 6, Boolean.toString(mStorage.config.encapsulateNicks), ""));
		
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
		if(mStorage.config.autojoinChannel) {
			for(Channel chan : mStorage.channelList) {
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
		} else {
			if(mStorage.config.joinedChannels.size() > 0 ) {
				Channel chan;
				for(String channelName : mStorage.config.joinedChannels) {
					chan = mStorage.getChannel(channelName);
					mPageNode.content.addChild(new HTMLNode("span", " | "));
					chanLinkFont = new HTMLNode("font", chan.name + " ");
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
					chanLink = new HTMLNode("a", "x");
					chanLink.addAttribute("href", "part?channel=" + basicHTMLencode(chan.name.replace("#","")));
					mPageNode.content.addChild(new HTMLNode("span", "["));
					mPageNode.content.addChild(chanLink);
					mPageNode.content.addChild(new HTMLNode("span", "]"));
				}
				mPageNode.content.addChild(new HTMLNode("span", " |"));
			} else {
				mPageNode.content.addChild(new HTMLNode("span","You did not join any channel, please head over to the "));
				HTMLNode aNode = new HTMLNode("a", "channel list");
				aNode.addAttribute("href", "stats");
				mPageNode.content.addChild(aNode);
				mPageNode.content.addChild(new HTMLNode("span", " or join a channel with /join #channel (if #channel does not exist yet it will be created)."));
			}
		}
		HTMLNode iframe = new HTMLNode("iframe");
		//iframe.addAttribute("width", Integer.toString(mStorage.config.iFrameWidth));
		iframe.addAttribute("height", Integer.toString(mStorage.config.iFrameHeight));
		iframe.addAttribute("width", "100%");
		//iframe.addAttribute("height", "100%");
		iframe.addAttribute("scrolling", "no");
		iframe.addAttribute("name", "flircp_iframe");
		iframe.addAttribute("src", "/flircp/show?channel="+ channel.replace("#","") + "#lastLine");
		iframe.setContent("you need to activate frames to use flircp");
		HTMLNode formMain = new HTMLNode("form");
		formMain.addAttribute("action", "sendMessage?channel=" + channel.replace("#",""));
		formMain.addAttribute("method", "POST");
		HTMLNode inputbox = new HTMLNode("input");
		inputbox.addAttribute("type", "text");
		inputbox.addAttribute("name", "messageinput");
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
		User user;
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
		
		for(Channel chan : mStorage.channelList) {
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
//			mPageNode.content.addChild("i", tmpGetRequestParts[i] + "-->");	
//			mPageNode.content.addChild("br");
//			for(String part : req.getMultipleParam(tmpGetRequestParts[i])) {
//				mPageNode.content.addChild("i", part);	
//				mPageNode.content.addChild("br");
//			}
				 //+ req.getPartAsStringFailsafe(tmpGetRequestParts[i], 255));
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

    @Override
    public boolean isLinkExcepted(URI link) {
        return true;
    }
}
