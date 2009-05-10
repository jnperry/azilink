/* AziLink: USB tethering for Android
 * Copyright (C) 2009 by James Perry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.lfx.azilink.net;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Callback used by TcpDriver to indicate various events.
 * 
 * @author Jim Perry
 *
 */
public interface TcpDriverCallback {	
	/**
	 * Begin a NIO connection to the specified address
	 */
	void onBeginBind(InetSocketAddress address) throws IOException;
	
	/**
	 * TCP driver has new data available to be written over NIO
	 */
	void onNewDataAvailable();
	
	/**
	 * TCP driver is now ready to receive more data from NIO
	 */
	void onRequestMoreData();
	
	/**
	 * The host requested the connection be closed.  After all pending NIO data has been sent,
	 * call TcpDriver.close()
	 */
	void onClosed();
	
	/**
	 * Connection should be immediately destroyed (RST received from host)
	 */
	void onDestroy();
}
