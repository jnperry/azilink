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
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * This is a generic interface used by the select() thread to dispatch socket events.
 * 
 * @author Jim Perry
 *
 */
public class SocketHandler {	
	public SocketHandler( SelectableChannel ch ) {
		mChannel = ch;		
	}
	
	public void onAccept( SelectionKey k ) throws IOException {}
	public void onConnect( SelectionKey k ) throws IOException {}		
	public void onRead( SelectionKey k ) throws IOException {}		
	public void onWrite( SelectionKey k ) throws IOException {}	
	
	public SelectableChannel mChannel;
}
