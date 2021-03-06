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
package org.opencloudb.route;

import java.sql.SQLNonTransientException;

import org.opencloudb.cache.CachePool;
import org.opencloudb.cache.CacheService;
import org.opencloudb.cache.LayerCachePool;
import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.config.model.SystemConfig;
import org.opencloudb.server.parser.ServerParse;

public class RouteService {
	private final CachePool sqlRouteCache;
	private final LayerCachePool tableId2DataNodeCache;

	public RouteService(CacheService cachService) {
		sqlRouteCache = cachService.getCachePool("SQLRouteCache");
		tableId2DataNodeCache = (LayerCachePool) cachService
				.getCachePool("TableID2DataNodeCache");
	}

	public LayerCachePool getTableId2DataNodeCache() {
		return tableId2DataNodeCache;
	}

	public RouteResultset route(SystemConfig sysconf, SchemaConfig schema,
			int sqlType, String stmt, String charset, Object info)
			throws SQLNonTransientException {
		RouteResultset rrs = null;
		String cacheKey = null;
		if (sqlType == ServerParse.SELECT) {
			cacheKey = schema.getName() + stmt;
			rrs = (RouteResultset) sqlRouteCache.get(cacheKey);
			if (rrs != null) {
				return rrs;
			}
		}
		// 处理自定义分片注释
		String mycatHint = "/*!mycat";
		/* !mycat: select name from aa */
		if (stmt.startsWith(mycatHint)) {
			int endPos = stmt.indexOf("*/");
			if (endPos > 0) {
				// 用!mycat内部的SQL来做路由分析
				String hintSQl = stmt.substring(mycatHint.length(), endPos)
						.trim();
				rrs = ServerRouterUtil.route(sysconf, schema, sqlType, hintSQl,
						charset, info, tableId2DataNodeCache);
				// 替换RRS中的SQL执行
				String realSQL = stmt.substring(endPos + "*/".length()).trim();
				RouteResultsetNode[] oldRsNodes = rrs.getNodes();
				RouteResultsetNode[] newRrsNodes = new RouteResultsetNode[oldRsNodes.length];
				for (int i = 0; i < newRrsNodes.length; i++) {
					newRrsNodes[i] = new RouteResultsetNode(
							oldRsNodes[i].getName(),
							oldRsNodes[i].getSqlType(), realSQL);
				}
				rrs.setNodes(newRrsNodes);
			}
		} else {
			stmt = stmt.trim();
			rrs = ServerRouterUtil.route(sysconf, schema, sqlType, stmt,
					charset, info, tableId2DataNodeCache);
		}

		if (sqlType == ServerParse.SELECT && rrs.isCacheAble()) {
			sqlRouteCache.putIfAbsent(cacheKey, rrs);
		}
		return rrs;
	}
}