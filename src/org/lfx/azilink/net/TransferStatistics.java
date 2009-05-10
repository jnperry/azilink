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

/**
 * Interface that indicates additional bytes have been received/sent.
 * 
 * @author Jim Perry
 *
 */
public interface TransferStatistics {
	/**
	 * Announce new bytes that have been recv/sent.
	 * 
	 * @param recv bytes received
	 * @param sent bytes sent
	 */
	void addBytes( int recv, int sent );
}
