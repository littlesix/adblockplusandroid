package org.adblockplus.brazil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Properties;

import sunlabs.brazil.server.Handler;
import sunlabs.brazil.server.Request;
import sunlabs.brazil.server.Server;
import sunlabs.brazil.util.MatchString;
import android.util.Log;

/**
 * <code>RequestHandler</code> implements a SSL tunnel.
 * 
 * The following configuration parameters are used to initialize this
 * <code>Handler</code>:
 * <dl class=props>
 * 
 * <dt>prefix, suffix, glob, match
 * <dd>Specify the URL that triggers this handler. (See {@link MatchString}).
 * <dt>auth
 * <dd>The value of the proxy-authenticate header (if any) sent to the upstream
 * proxy
 * <dt>proxyHost
 * <dd>If specified, the name of the upstream proxy
 * <dt>proxyPort
 * <dd>The upstream proxy port, if a proxyHost is specified (defaults to 80)
 * 
 * </dl>
 * 
 * A sample set of configuration parameters illustrating how to use this
 * handler follows:
 * 
 * <pre>
 * handler=https
 * https.class=org.adblockplus.brazil.SSLConnectionHandler
 * </pre>
 * 
 * See the description under {@link sunlabs.brazil.server.Handler#respond
 * respond} for a more detailed explanation.
 */

public class SSLConnectionHandler implements Handler
{
	public static final String PROXY_HOST = "proxyHost";
	public static final String PROXY_PORT = "proxyPort";
	public static final String AUTH = "auth";

	private String prefix;

	private String proxyHost;
	private int proxyPort = 80;
	private String auth;

	@Override
	public boolean init(Server server, String prefix)
	{
		this.prefix = prefix;

		Properties props = server.props;

		proxyHost = props.getProperty(prefix + PROXY_HOST);

		String s = props.getProperty(prefix + PROXY_PORT);
		try
		{
			proxyPort = Integer.decode(s).intValue();
		}
		catch (Exception e)
		{
		}

		auth = props.getProperty(prefix + AUTH);

		return true;
	}

	@Override
	public boolean respond(Request request) throws IOException
	{
		if (!request.method.equals("CONNECT"))
			return false;

		request.log(Server.LOG_LOG, prefix, "SSL connection to " + request.url);

		String host = null;
		int port = 0;

		Socket serverSocket;
		try
		{
			if (proxyHost != null)
			{
				host = proxyHost;
				port = proxyPort;
				if (auth != null)
				{
					request.headers.add("Proxy-Authorization", auth);
				}
			}
			else
			{
				int c = request.url.indexOf(':');
				host = request.url.substring(0, c);
				port = Integer.parseInt(request.url.substring(c+1));
			}

			// Connect to server or upstream proxy
			InetAddress addr = InetAddress.getByName(host);
			serverSocket = new Socket(addr, port);
		}
		catch (Exception e)
		{
			request.sendError(500, "SSL connection failure");
			return true;
		}

		try
		{
			if (proxyHost != null)
			{
				// Forward request to upstream proxy
				OutputStream out = serverSocket.getOutputStream();
				out.write((request.method + " " + request.url + " " + request.protocol + "\r\n").getBytes());
				request.headers.print(out);
				out.write("\r\n".getBytes());
				out.flush();
			}
			else
			{
				// Send response to client
				OutputStream out = request.sock.getOutputStream();
				out.write((request.protocol + " 200 Connection established\r\n\r\n").getBytes());
				out.flush();
			}

			// Start bi-directional data transfer
			ConnectionHandler client = new ConnectionHandler(request.sock, serverSocket);
			ConnectionHandler server = new ConnectionHandler(serverSocket, request.sock);
			client.start();
			server.start();

			// Wait for connections to close
			client.join();
			server.join();
		}
		catch (InterruptedException e)
		{
			Log.e(prefix, "Data exchange error", e);
		}

		// Close connection
		serverSocket.close();
		request.log(Server.LOG_LOG, prefix, "SSL connection closed");

		return true;
	}

	private class ConnectionHandler extends Thread
	{
		private InputStream in;
		private OutputStream out;

		ConnectionHandler(Socket sin, Socket sout) throws IOException
		{
			in = sin.getInputStream();
			out = sout.getOutputStream();
		}

		@Override
		public void run()
		{
			byte[] buf = new byte[4096];
			int count;

			try
			{
				while ((count = in.read(buf, 0, buf.length)) != -1)
				{
					out.write(buf, 0, count);
				}
				out.flush();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}
}