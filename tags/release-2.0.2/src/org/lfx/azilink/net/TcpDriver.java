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

import java.nio.ByteBuffer;

/**
 * Represents a single TCP connection.  This class translates between a byte stream from the VPN (representing
 * raw packets) and a byte stream over a socket (representing the actual data stream).
 * 
 * @author Jim Perry
 *
 */
public interface TcpDriver {
	/**
	 * New TCP packet received from the VPN link.
	 * @param pkt the received tcp packet
	 */
	void newPacket(TcpPacket pkt);
	
	/**
	 * Return a hashkey that can be used to represent this connection.
	 * @return hash key
	 */
	TcpKey getKey();
	
	/**
	 * Call when the TCP link has been established (the socket's connect() call has completed).
	 * @param success whether the connection was successful or refused
	 */
	void onBindComplete( boolean success );
	
	/**
	 * Returns pending data for the TCP stream. 
	 * @param data data to be sent over NIO
	 */
	void read(ByteBuffer data);
	
	/**
	 * Adds data read out of the TCP NIO stream.  May not pull the entire buffer -- check data's length
	 * after this call.
	 * @param data data received from NIO
	 */
	void write(ByteBuffer data);
	
	/**
	 * Returns the maximum number of bytes that read() could return.
	 * @return maximum bytes read could return.
	 */
	int getReadAvailableSize();
	
	// Return maximum number of bytes we can write (more will throw an exception)
	/**
	 * Returns the maximum number of bytes that can be written.
	 * @return maximum bytes write can accept.
	 */
	int getWriteAvailableSize();
	
	/**
	 * Cleanly close the connection.  This will not be immediate, since the TCP buffer will need
	 * to be flushed and the FIN handshake will need to occur.
	 */
	void close();
	
	/**
	 * Immediately teardown the link.  If a FIN hasn't occured yet, then a RST will be immediately issued.
	 */
	void destroy();
}
