/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package org.opencloudb.server.handler;

import java.nio.ByteBuffer;
import java.sql.SQLNonTransientException;

import org.apache.log4j.Logger;
import org.opencloudb.MycatServer;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.config.Fields;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.mysql.PacketUtil;
import org.opencloudb.net.mysql.EOFPacket;
import org.opencloudb.net.mysql.FieldPacket;
import org.opencloudb.net.mysql.ResultSetHeaderPacket;
import org.opencloudb.net.mysql.RowDataPacket;
import org.opencloudb.route.RouteResultset;
import org.opencloudb.route.RouteResultsetNode;
import org.opencloudb.server.ServerConnection;
import org.opencloudb.server.parser.ServerParse;
import org.opencloudb.util.StringUtil;

/**
 * @author mycat
 */
public class ExplainHandler {

	private static final Logger logger = Logger.getLogger(ExplainHandler.class);
	private static final RouteResultsetNode[] EMPTY_ARRAY = new RouteResultsetNode[0];
	private static final int FIELD_COUNT = 2;
	private static final FieldPacket[] fields = new FieldPacket[FIELD_COUNT];
	static {
		fields[0] = PacketUtil.getField("DATA_NODE",
				Fields.FIELD_TYPE_VAR_STRING);
		fields[1] = PacketUtil.getField("SQL", Fields.FIELD_TYPE_VAR_STRING);
	}

	public static void handle(String stmt, ServerConnection c, int offset) {
		stmt = stmt.substring(offset);

		RouteResultset rrs = getRouteResultset(c, stmt);
		if (rrs == null)
			return;

		ByteBuffer buffer = c.allocate();

		// write header
		ResultSetHeaderPacket header = PacketUtil.getHeader(FIELD_COUNT);
		byte packetId = header.packetId;
		buffer = header.write(buffer, c,true);

		// write fields
		for (FieldPacket field : fields) {
			field.packetId = ++packetId;
			buffer = field.write(buffer, c,true);
		}

		// write eof
		EOFPacket eof = new EOFPacket();
		eof.packetId = ++packetId;
		buffer = eof.write(buffer, c,true);

		// write rows
		RouteResultsetNode[] rrsn = (rrs != null) ? rrs.getNodes()
				: EMPTY_ARRAY;
		for (RouteResultsetNode node : rrsn) {
			RowDataPacket row = getRow(node, c.getCharset());
			row.packetId = ++packetId;
			buffer = row.write(buffer, c,true);
		}

		// write last eof
		EOFPacket lastEof = new EOFPacket();
		lastEof.packetId = ++packetId;
		buffer = lastEof.write(buffer, c,true);

		// post write
		c.write(buffer);

	}

	private static RowDataPacket getRow(RouteResultsetNode node, String charset) {
		RowDataPacket row = new RowDataPacket(FIELD_COUNT);
		row.add(StringUtil.encode(node.getName(), charset));
		row.add(StringUtil.encode(node.getStatement(), charset));
		return row;
	}

	private static RouteResultset getRouteResultset(ServerConnection c,
			String stmt) {
		String db = c.getSchema();
		if (db == null) {
			c.writeErrMessage(ErrorCode.ER_NO_DB_ERROR, "No database selected");
			return null;
		}
		SchemaConfig schema = MycatServer.getInstance().getConfig()
				.getSchemas().get(db);
		if (schema == null) {
			c.writeErrMessage(ErrorCode.ER_BAD_DB_ERROR, "Unknown database '"
					+ db + "'");
			return null;
		}
		try {
			int sqlType = ServerParse.parse(stmt) & 0xff;
			return MycatServer.getInstance().getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),schema, sqlType, stmt, c.getCharset(), c);
		} catch (SQLNonTransientException e) {
			StringBuilder s = new StringBuilder();
			logger.warn(s.append(c).append(stmt).toString()+" error:"+ e);
			String msg = e.getMessage();
			c.writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e
					.getClass().getSimpleName() : msg);
			return null;
		}
	}

}